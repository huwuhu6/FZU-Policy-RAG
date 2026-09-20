package net.topikachu.rag.service.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.etl.DashScopeEmbeddingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Matches a small, manually reviewed FAQ set in memory using dense cosine
 * similarity. It is deliberately independent of Milvus and the normal RAG
 * retrieval path.
 */
@Component
@Slf4j
public class FaqSemanticMatcher {

    private final DashScopeEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final TracingSupport tracingSupport;
    private final Resource faqResource;
    private final boolean enabled;
    private final double similarityThreshold;
    private final double similarityMargin;
    private final Mono<List<IndexedAlias>> index;

    public FaqSemanticMatcher(
            DashScopeEmbeddingClient embeddingClient,
            ObjectMapper objectMapper,
            TracingSupport tracingSupport,
            @Value("${rag.faq.resource:classpath:rag/faq.json}") Resource faqResource,
            @Value("${rag.faq.enabled:true}") boolean enabled,
            @Value("${rag.faq.similarity-threshold:0.95}") double similarityThreshold,
            @Value("${rag.faq.similarity-margin:0.03}") double similarityMargin) {
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
        this.tracingSupport = tracingSupport;
        this.faqResource = faqResource;
        this.enabled = enabled;
        this.similarityThreshold = similarityThreshold;
        this.similarityMargin = similarityMargin;
        this.index = buildIndex().cache();
    }

    public Mono<Match> match(String query, RagRequestContext context) {
        if (!enabled || query == null || query.isBlank()) {
            return Mono.just(Match.miss());
        }

        long startNanos = System.nanoTime();
        Map<String, Object> tags = context == null ? Map.of() : context.traceTags();
        return tracingSupport.traceMono("rag.faq_match", tags,
                        index.flatMap(aliases -> {
                            if (aliases.isEmpty()) {
                                return Mono.just(Match.miss());
                            }
                            return embeddingClient.embedDenseQuery(query)
                                    .map(queryVector -> selectMatch(queryVector, aliases));
                        }))
                .doOnNext(match -> log.info(
                        "[RAG] faq-match hit={} faqId={} top1Score={} margin={} elapsedMs={}",
                        match.matched(), match.faqId(), match.top1Score(), match.margin(),
                        (System.nanoTime() - startNanos) / 1_000_000L))
                .onErrorResume(error -> {
                    log.warn("[RAG] faq failed fallback=true errorType={}",
                            error.getClass().getSimpleName());
                    return Mono.just(Match.miss());
                });
    }

    public Mono<Match> match(String query) {
        return match(query, null);
    }

    private Mono<List<IndexedAlias>> buildIndex() {
        return Mono.fromCallable(this::loadFaqEntries)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .flatMap(entry -> Flux.fromIterable(entry.questions())
                        .filter(question -> question != null && !question.isBlank())
                        .concatMap(question -> embeddingClient.embedDenseQuery(question)
                                .map(vector -> new IndexedAlias(entry.id(), entry.answer(), question, vector))))
                .collectList()
                .doOnNext(aliases -> log.info("[RAG] faq index initialized entries={}",
                        aliases.stream().map(IndexedAlias::faqId).distinct().count()))
                .doOnError(error -> log.warn("[RAG] faq initialization failed fallback=true errorType={}",
                        error.getClass().getSimpleName()));
    }

    private List<FaqEntry> loadFaqEntries() throws IOException {
        try (InputStream inputStream = faqResource.getInputStream()) {
            List<FaqEntry> entries = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            if (entries == null) {
                return List.of();
            }
            return entries.stream()
                    .filter(entry -> entry != null
                            && entry.id() != null && !entry.id().isBlank()
                            && entry.answer() != null && !entry.answer().isBlank()
                            && entry.questions() != null && !entry.questions().isEmpty())
                    .toList();
        }
    }

    private Match selectMatch(List<Float> queryVector, List<IndexedAlias> aliases) {
        List<ScoredAlias> ranked = aliases.stream()
                .map(alias -> new ScoredAlias(alias, cosine(queryVector, alias.vector())))
                .sorted(Comparator.comparingDouble(ScoredAlias::similarity).reversed())
                .toList();
        if (ranked.isEmpty()) {
            return Match.miss();
        }
        ScoredAlias top = ranked.get(0);
        double second = ranked.size() == 1 ? Double.NEGATIVE_INFINITY : ranked.get(1).similarity();
        boolean marginPass = ranked.size() == 1 || top.similarity() - second >= similarityMargin;
        double margin = ranked.size() == 1 ? Double.POSITIVE_INFINITY : top.similarity() - second;
        if (top.similarity() < similarityThreshold || !marginPass) {
            return Match.miss(top.similarity(), margin);
        }
        return new Match(true, top.alias().faqId(), top.alias().answer(), top.similarity(),
                top.similarity(), margin);
    }

    static double cosine(List<Float> left, List<Float> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) {
            return 0.0d;
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int i = 0; i < left.size(); i++) {
            double l = left.get(i) == null ? 0.0d : left.get(i);
            double r = right.get(i) == null ? 0.0d : right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    public record Match(boolean matched,
                        String faqId,
                        String answer,
                        Double similarity,
                        Double top1Score,
                        Double margin) {
        public Match(boolean matched, String faqId, String answer, Double similarity) {
            this(matched, faqId, answer, similarity, similarity, null);
        }

        public static Match miss() {
            return new Match(false, null, null, null, null, null);
        }

        public static Match miss(Double top1Score, Double margin) {
            return new Match(false, null, null, null, top1Score, margin);
        }
    }

    public record FaqEntry(String id, List<String> questions, String answer, Source source) {
        public FaqEntry(String id, List<String> questions, String answer) {
            this(id, questions, answer, null);
        }
    }

    public record Source(String objectName, String fileName, String location) {
    }

    private record IndexedAlias(String faqId, String answer, String question, List<Float> vector) {
    }

    private record ScoredAlias(IndexedAlias alias, double similarity) {
    }
}
