package net.topikachu.rag.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FzuRetrievalMetricsTest {

    @Test
    void computesGradedMetricsAndLatencyPercentiles() {
        List<FzuRetrievalMetrics.CaseInput> cases = List.of(
                new FzuRetrievalMetrics.CaseInput(
                        List.of("a", "x", "b"), Map.of("a", 2, "b", 1), 10, true),
                new FzuRetrievalMetrics.CaseInput(
                        List.of("x", "b", "a"), Map.of("a", 2, "b", 1), 30, true),
                new FzuRetrievalMetrics.CaseInput(
                        List.of(), Map.of("a", 2), 99, false));

        FzuRetrievalMetrics.Summary summary = FzuRetrievalMetrics.summarize(cases);

        assertEquals(3, summary.totalCases());
        assertEquals(2, summary.successCount());
        assertEquals(1, summary.failureCount());
        assertEquals(1.0, summary.recallAt5(), 0.0001);
        assertEquals(1.0, summary.recallAt10(), 0.0001);
        assertEquals(0.75, summary.mrrAt10(), 0.0001);
        assertEquals(1.0, summary.hitRateAt5(), 0.0001);
        assertEquals(20.0, summary.averageLatencyMs(), 0.0001);
        assertEquals(10L, summary.p50LatencyMs());
        assertEquals(30L, summary.p95LatencyMs());
    }

    @Test
    void deduplicatesDocumentKeysBeforeDocumentLevelScoring() {
        FzuRetrievalMetrics.Summary summary = FzuRetrievalMetrics.summarize(List.of(
                new FzuRetrievalMetrics.CaseInput(
                        List.of("A", "A", "A", "B"), Map.of("A", 2, "B", 1), 10, true)));

        assertEquals(1.0, summary.recallAt5(), 0.0001);
        assertEquals(1.0, summary.ndcgAt10(), 0.0001);
        assertTrue(summary.recallAt5() <= 1.0);
        assertTrue(summary.recallAt10() <= 1.0);
        assertTrue(summary.ndcgAt10() <= 1.0);
    }
}
