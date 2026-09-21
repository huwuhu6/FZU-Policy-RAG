package net.topikachu.rag.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure retrieval metrics for the FZU benchmark. It has no database, vector
 * store, or model dependency and can therefore be verified independently.
 */
public final class FzuRetrievalMetrics {

    private FzuRetrievalMetrics() {
    }

    public record CaseInput(List<String> retrievedDocumentKeys,
                            Map<String, Integer> qrels,
                            long latencyMs,
                            boolean success) {
    }

    public record Summary(int totalCases,
                          int successCount,
                          int failureCount,
                          double successRate,
                          double recallAt5,
                          double recallAt10,
                          double mrrAt10,
                          double ndcgAt10,
                          double hitRateAt5,
                          double hitRateAt10,
                          double averageLatencyMs,
                          long p50LatencyMs,
                          long p95LatencyMs) {
    }

    public static Summary summarize(List<CaseInput> cases) {
        List<CaseInput> inputs = cases == null ? List.of() : cases;
        int successCount = (int) inputs.stream().filter(CaseInput::success).count();
        int failureCount = inputs.size() - successCount;
        return new Summary(
                inputs.size(),
                successCount,
                failureCount,
                inputs.isEmpty() ? 0.0 : successCount / (double) inputs.size(),
                averageRecallAtK(inputs, 5),
                averageRecallAtK(inputs, 10),
                averageMrrAtK(inputs, 10),
                averageNdcgAtK(inputs, 10),
                averageHitRateAtK(inputs, 5),
                averageHitRateAtK(inputs, 10),
                averageLatency(inputs),
                percentile(successfulLatencies(inputs), 0.50),
                percentile(successfulLatencies(inputs), 0.95));
    }

    private static double averageRecallAtK(List<CaseInput> cases, int k) {
        double sum = 0.0;
        int count = 0;
        for (CaseInput input : cases) {
            if (!input.success() || input.qrels() == null || input.qrels().isEmpty()) {
                continue;
            }
            Set<String> relevant = relevantKeys(input.qrels());
            if (relevant.isEmpty()) {
                continue;
            }
            long hits = rankedKeys(input).stream().limit(k).filter(relevant::contains).count();
            sum += hits / (double) relevant.size();
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double averageMrrAtK(List<CaseInput> cases, int k) {
        double sum = 0.0;
        int count = 0;
        for (CaseInput input : cases) {
            if (!input.success() || input.qrels() == null || input.qrels().isEmpty()) {
                continue;
            }
            Set<String> relevant = relevantKeys(input.qrels());
            if (relevant.isEmpty()) {
                continue;
            }
            List<String> ranked = rankedKeys(input);
            double reciprocalRank = 0.0;
            for (int i = 0; i < Math.min(k, ranked.size()); i++) {
                if (relevant.contains(ranked.get(i))) {
                    reciprocalRank = 1.0 / (i + 1);
                    break;
                }
            }
            sum += reciprocalRank;
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double averageNdcgAtK(List<CaseInput> cases, int k) {
        double sum = 0.0;
        int count = 0;
        for (CaseInput input : cases) {
            if (!input.success() || input.qrels() == null || input.qrels().isEmpty()) {
                continue;
            }
            List<String> ranked = rankedKeys(input);
            double dcg = 0.0;
            for (int i = 0; i < Math.min(k, ranked.size()); i++) {
                int relevance = Math.max(0, input.qrels().getOrDefault(ranked.get(i), 0));
                dcg += gain(relevance) / log2(i + 2.0);
            }
            List<Integer> ideal = input.qrels().values().stream()
                    .filter(relevance -> relevance != null && relevance > 0)
                    .sorted(Collections.reverseOrder())
                    .limit(k)
                    .toList();
            if (ideal.isEmpty()) {
                continue;
            }
            double idcg = 0.0;
            for (int i = 0; i < ideal.size(); i++) {
                idcg += gain(ideal.get(i)) / log2(i + 2.0);
            }
            if (idcg > 0.0) {
                sum += dcg / idcg;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double averageHitRateAtK(List<CaseInput> cases, int k) {
        int count = 0;
        int hits = 0;
        for (CaseInput input : cases) {
            if (!input.success() || input.qrels() == null || input.qrels().isEmpty()) {
                continue;
            }
            Set<String> relevant = relevantKeys(input.qrels());
            if (relevant.isEmpty()) {
                continue;
            }
            count++;
            if (rankedKeys(input).stream().limit(k).anyMatch(relevant::contains)) {
                hits++;
            }
        }
        return count == 0 ? 0.0 : hits / (double) count;
    }

    private static double averageLatency(List<CaseInput> cases) {
        return successfulLatencies(cases).stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    public static long percentile(List<Long> sortedLatencies, double percentile) {
        if (sortedLatencies == null || sortedLatencies.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(sortedLatencies);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static List<Long> successfulLatencies(List<CaseInput> cases) {
        return cases.stream().filter(CaseInput::success).map(CaseInput::latencyMs).toList();
    }

    private static List<String> rankedKeys(CaseInput input) {
        return input.retrievedDocumentKeys() == null ? List.of() : input.retrievedDocumentKeys();
    }

    private static Set<String> relevantKeys(Map<String, Integer> qrels) {
        Set<String> relevant = new HashSet<>();
        qrels.forEach((key, value) -> {
            if (key != null && value != null && value > 0) {
                relevant.add(key);
            }
        });
        return relevant;
    }

    private static double gain(int relevance) {
        return Math.pow(2.0, relevance) - 1.0;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }
}
