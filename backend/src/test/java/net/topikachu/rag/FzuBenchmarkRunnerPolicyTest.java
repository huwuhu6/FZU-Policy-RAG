package net.topikachu.rag;

import net.topikachu.rag.evaluation.BenchmarkVariant;
import net.topikachu.rag.service.chat.RetrievalPipeline;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FzuBenchmarkRunnerPolicyTest {

    @Test
    void retriesFallbackOnlyForRerankVariants() {
        RetrievalPipeline.RetrievalOutcome fallback = new RetrievalPipeline.RetrievalOutcome(
                List.of(new Document("fallback")), true, false, true, "missing_rerank_score");
        RetrievalPipeline.RetrievalOutcome applied = new RetrievalPipeline.RetrievalOutcome(
                List.of(new Document("reranked")), true, true, false, null);

        assertTrue(FzuAblationStudyRunner.shouldRetryRerankFallback(BenchmarkVariant.DENSE_RERANK, fallback));
        assertTrue(FzuAblationStudyRunner.shouldRetryRerankFallback(BenchmarkVariant.HYBRID_RERANK, fallback));
        assertFalse(FzuAblationStudyRunner.shouldRetryRerankFallback(BenchmarkVariant.DENSE_NO_RERANK, fallback));
        assertFalse(FzuAblationStudyRunner.shouldRetryRerankFallback(BenchmarkVariant.DENSE_RERANK, applied));
    }
}
