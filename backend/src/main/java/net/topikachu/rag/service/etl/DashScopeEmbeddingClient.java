package net.topikachu.rag.service.etl;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * DashScope Native API client for dense and learned sparse embeddings.
 */
@Component
@Slf4j
public class DashScopeEmbeddingClient {

    private static final String EMBEDDING_PATH = "/services/embeddings/text-embedding/text-embedding";
    private static final String DENSE_AND_SPARSE = "dense&sparse";
    private static final String DENSE_ONLY = "dense";

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final int dimension;
    private final Duration timeout;

    public DashScopeEmbeddingClient(
            @Value("${rag.embedding.base-url:https://dashscope.aliyuncs.com/api/v1}") String baseUrl,
            @Value("${rag.embedding.api-key:}") String apiKey,
            @Value("${rag.embedding.model:qwen3.7-text-embedding-flash}") String model,
            @Value("${rag.embedding.dimension:1024}") int dimension,
            @Value("${rag.embedding.timeout-ms:30000}") int timeoutMs,
            WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
        this.timeout = Duration.ofMillis(timeoutMs);
        log.info("DashScopeEmbeddingClient initialized: model={}, dimension={}, timeout={}ms",
                model, dimension, timeoutMs);
    }

    public Mono<HybridEmbedding> embedDocument(String text) {
        return embed(text, "document", DENSE_AND_SPARSE, true);
    }

    public Mono<HybridEmbedding> embedQuery(String text) {
        return embed(text, "query", DENSE_AND_SPARSE, true);
    }

    public Mono<List<Float>> embedDenseQuery(String text) {
        return embed(text, "query", DENSE_ONLY, false)
                .map(HybridEmbedding::denseVector);
    }

    private Mono<HybridEmbedding> embed(String text, String textType, String outputType, boolean requireSparse) {
        TextSanitizer.SanitizationResult result = TextSanitizer.sanitize(text);
        if (result.wasModified()) {
            log.info(
                    "Sanitized embedding input: removedChars={}, normalizedWhitespace={}, originalLength={}, sanitizedLength={}, preview={}",
                    result.removedChars(),
                    result.normalizedWhitespace(),
                    result.originalLength(),
                    result.text().length(),
                    TextSanitizer.preview(result.text()));
        }
        if (result.isEffectivelyEmpty()) {
            log.warn("Rejecting embedding input after sanitization: preview={}", TextSanitizer.preview(text));
            return Mono.error(new IllegalArgumentException("Embedding input is empty after sanitization"));
        }
        if (TextSanitizer.containsIllegalCodePoints(result.text())) {
            log.warn("Rejecting embedding input that still contains illegal Unicode after sanitization: preview={}",
                    TextSanitizer.preview(result.text()));
            return Mono.error(new IllegalArgumentException("Embedding input still contains illegal Unicode code points"));
        }
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new IllegalStateException("DashScope embedding API key is not configured"));
        }

        EmbeddingRequest request = new EmbeddingRequest(
                model,
                new EmbeddingInput(List.of(result.text())),
                new EmbeddingParameters(dimension, outputType, textType));

        return webClient.post()
                .uri(EMBEDDING_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(apiKey))
                .bodyValue(request)
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .timeout(timeout)
                .map(response -> toHybridEmbedding(response, requireSparse))
                .doOnError(error -> log.error("DashScope embedding request failed: {}", error.getMessage()));
    }

    private HybridEmbedding toHybridEmbedding(EmbeddingResponse response, boolean requireSparse) {
        if (response == null || response.output() == null
                || response.output().embeddings() == null
                || response.output().embeddings().isEmpty()
                || response.output().embeddings().get(0) == null) {
            throw new IllegalStateException("DashScope embedding response missing dense vector");
        }

        EmbeddingItem item = response.output().embeddings().get(0);
        List<Float> denseVector = item.embedding();
        if (denseVector == null) {
            throw new IllegalStateException("DashScope embedding response missing dense vector");
        }
        if (denseVector.size() != dimension) {
            throw new IllegalStateException(String.format(
                    "DashScope dense vector dimension mismatch: expected=%d, actual=%d",
                    dimension, denseVector.size()));
        }

        SortedMap<Long, Float> sparseVector = new TreeMap<>();
        if (item.sparseEmbedding() != null) {
            for (SparseEmbeddingItem sparseItem : item.sparseEmbedding()) {
                if (sparseItem == null || sparseItem.index() == null || sparseItem.value() == null) {
                    throw new IllegalStateException("DashScope sparse vector contains invalid index/value");
                }
                sparseVector.put(sparseItem.index(), sparseItem.value());
            }
        }
        if (requireSparse && sparseVector.isEmpty()) {
            throw new IllegalStateException("DashScope embedding response missing sparse vector");
        }

        return new HybridEmbedding(denseVector, sparseVector);
    }

    public record HybridEmbedding(List<Float> denseVector, SortedMap<Long, Float> sparseVector) {

        public HybridEmbedding {
            denseVector = denseVector == null ? List.of() : List.copyOf(denseVector);
            sparseVector = sparseVector == null
                    ? new TreeMap<>()
                    : new TreeMap<>(sparseVector);
        }
    }

    private record EmbeddingRequest(
            String model,
            EmbeddingInput input,
            EmbeddingParameters parameters) {
    }

    private record EmbeddingInput(List<String> texts) {
    }

    private record EmbeddingParameters(
            int dimension,
            @JsonProperty("output_type") String outputType,
            @JsonProperty("text_type") String textType) {
    }

    private record EmbeddingResponse(EmbeddingOutput output) {
    }

    private record EmbeddingOutput(List<EmbeddingItem> embeddings) {
    }

    private record EmbeddingItem(
            List<Float> embedding,
            @JsonProperty("sparse_embedding") List<SparseEmbeddingItem> sparseEmbedding) {
    }

    private record SparseEmbeddingItem(Long index, Float value, String token) {
    }
}
