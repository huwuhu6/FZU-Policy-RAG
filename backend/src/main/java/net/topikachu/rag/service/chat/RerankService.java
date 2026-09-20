package net.topikachu.rag.service.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Rerank service using the DashScope Native Rerank API.
 * Includes circuit breaker for resilience.
 */
@Service
@Slf4j
public class RerankService {

    @Value("${rag.rerank.url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
    private String rerankUrl;

    @Value("${rag.rerank.api-key:}")
    private String apiKey;

    @Value("${rag.rerank.model:qwen3.7-text-rerank}")
    private String model;

    @Value("${rag.rerank.timeout-ms:10000}")
    private int timeoutMs;

    private final WebClient webClient;

    public RerankService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    // Reactive rerank with hard timeout to avoid blocking request threads
    // indefinitely.
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "rerankService", fallbackMethod = "rerankFallback")
    @io.github.resilience4j.bulkhead.annotation.Bulkhead(name = "rerankService", type = io.github.resilience4j.bulkhead.annotation.Bulkhead.Type.SEMAPHORE, fallbackMethod = "rerankFallback")
    public Mono<List<Document>> rerank(String query, List<Document> docs, int topN) {
        if (docs == null || docs.isEmpty()) {
            return Mono.just(new ArrayList<>());
        }

        long startTime = System.currentTimeMillis();

        List<String> texts = docs.stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new IllegalStateException("DashScope rerank API key is not configured"));
        }

        int requestedTopN = Math.min(topN, docs.size());
        RerankRequest requestBody = new RerankRequest(
                model,
                new RerankInput(query, texts),
                new RerankParameters(requestedTopN));

        return webClient.post()
                .uri(rerankUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(apiKey))
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(RerankResponse.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(response -> mapResponse(response, docs, topN))
                .doOnNext(rerankedDocs -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.debug("Rerank completed in {}ms, returned {} docs", elapsed, rerankedDocs.size());
                })
                .doOnError(e -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (e instanceof TimeoutException) {
                        log.warn("Rerank timeout after {}ms limit={}ms", elapsed, timeoutMs);
                    } else {
                        log.warn("Rerank failed after {}ms errorType={}", elapsed, e.getClass().getSimpleName());
                    }
                });
    }

    private List<Document> mapResponse(RerankResponse response, List<Document> docs, int topN) {
        if (response == null || response.output() == null || response.output().results() == null) {
            throw new IllegalStateException("DashScope rerank response missing results");
        }

        List<RerankResult> scored = new ArrayList<>(response.output().results());
        for (RerankResult result : scored) {
            if (result == null || result.index() == null || result.relevanceScore() == null) {
                throw new IllegalStateException("DashScope rerank response contains invalid result");
            }
            if (result.index() < 0 || result.index() >= docs.size()) {
                throw new IllegalStateException("DashScope rerank result index out of bounds: " + result.index());
            }
        }
        scored.sort((left, right) -> Double.compare(right.relevanceScore(), left.relevanceScore()));

        List<Document> rerankedDocs = new ArrayList<>();
        for (int i = 0; i < Math.min(scored.size(), topN); i++) {
            RerankResult result = scored.get(i);
            Document originalDoc = docs.get(result.index());
            originalDoc.getMetadata().put("rerank_score", result.relevanceScore());
            rerankedDocs.add(originalDoc);
        }
        return rerankedDocs;
    }

    private record RerankRequest(
            String model,
            RerankInput input,
            RerankParameters parameters) {
    }

    private record RerankInput(String query, List<String> documents) {
    }

    private record RerankParameters(@JsonProperty("top_n") int topN) {
    }

    private record RerankResponse(RerankOutput output) {
    }

    private record RerankOutput(List<RerankResult> results) {
    }

    private record RerankResult(
            Integer index,
            @JsonProperty("relevance_score") Double relevanceScore) {
    }

    public Mono<List<Document>> rerankFallback(String query, List<Document> docs, int topN, Throwable t) {
        log.warn("[RAG] rerank fallback=true topN={} errorType={}", topN,
                t == null ? "unknown" : t.getClass().getSimpleName());

        if (docs == null || docs.isEmpty()) {
            return Mono.just(List.of());
        }
        return Mono.just(docs.subList(0, Math.min(docs.size(), topN)));
    }
}
