package net.topikachu.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.evaluation.BenchmarkVariant;
import net.topikachu.rag.evaluation.FzuRetrievalMetrics;
import net.topikachu.rag.service.chat.ChatService;
import net.topikachu.rag.service.chat.RetrievalPipeline;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * FZU Retrieval Evaluation v1 runner.
 *
 * The test is deliberately disabled: enabling it is the explicit action that
 * starts the 30-case x 4-variant benchmark against the real Milvus/DashScope
 * services. Dataset/qrels validation and metric tests remain runnable without
 * external services.
 */
@SpringBootTest
@ActiveProfiles("benchmark-fzu")
@Slf4j
@Disabled("正式 Benchmark 需人工审核数据集后显式启用")
class FzuAblationStudyRunner {

    @Autowired
    private ChatService chatService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${rag.benchmark.dataset-file:classpath:evaluation/fzu_policy_retrieval_v1.json}")
    private Resource datasetResource;

    @Value("${rag.benchmark.dataset-manifest:classpath:evaluation/fzu_policy_retrieval_v1.manifest.json}")
    private Resource manifestResource;

    @Value("${rag.benchmark.dataset-name:fzu-policy-retrieval-v1}")
    private String datasetName;

    @Value("${rag.benchmark.top-k:10}")
    private int topK;

    @Value("${rag.benchmark.rerank-top-k:40}")
    private int rerankTopK;

    @Value("${rag.benchmark.retrieval-timeout-ms:30000}")
    private long retrievalTimeoutMs;

    @Value("${rag.benchmark.retries:1}")
    private int retries;

    @Value("${rag.benchmark.output-dir:evaluation_results/fzu}")
    private String outputDirectory;

    @Test
    void runFzuRetrievalBenchmark() throws Exception {
        List<FzuBenchmarkCase> cases = loadDataset();
        validateDataset(cases);
        Map<String, String> fileHashByDocUuid = loadStableDocumentMapping();
        validateQrelsMapping(cases, fileHashByDocUuid);

        String gitSha = currentGitSha();
        Path outputDir = Paths.get(outputDirectory);
        Files.createDirectories(outputDir);
        writeRunManifest(outputDir.resolve("run-manifest.json"), gitSha, cases.size());

        for (BenchmarkVariant variant : BenchmarkVariant.fixedMatrix()) {
            List<FzuCaseRun> runs = new ArrayList<>();
            Path jsonl = outputDir.resolve(variant.id() + ".jsonl");
            Files.deleteIfExists(jsonl);

            for (FzuBenchmarkCase benchmarkCase : cases) {
                FzuCaseRun run = executeCase(benchmarkCase, variant, fileHashByDocUuid, gitSha);
                runs.add(run);
                Files.writeString(jsonl,
                        objectMapper.writeValueAsString(run.result()) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }

            FzuRetrievalMetrics.Summary metrics = FzuRetrievalMetrics.summarize(
                    runs.stream().map(FzuCaseRun::metricsInput).toList());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("dataset", datasetName);
            summary.put("gitSha", gitSha);
            summary.put("profile", "benchmark-fzu");
            summary.put("variant", variant.id());
            summary.put("topK", topK);
            summary.put("rerankTopK", variant.useRerank() ? rerankTopK : topK);
            summary.put("metrics", metrics);
            summary.put("rerankRequested", runs.stream().filter(run -> run.result().rerankRequested()).count());
            summary.put("rerankApplied", runs.stream().filter(run -> run.result().rerankApplied()).count());
            summary.put("rerankFallbackCount", runs.stream().filter(run -> run.result().rerankFallback()).count());
            Files.writeString(outputDir.resolve(variant.id() + "-summary.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                    StandardCharsets.UTF_8);
        }
    }

    private FzuCaseRun executeCase(FzuBenchmarkCase benchmarkCase,
                                   BenchmarkVariant variant,
                                   Map<String, String> fileHashByDocUuid,
                                   String gitSha) {
        Throwable lastError = null;
        long elapsedMs = 0L;
        RetrievalPipeline.RetrievalOutcome outcome = null;
        for (int attempt = 0; attempt <= Math.max(0, retries); attempt++) {
            long started = System.nanoTime();
            try {
                outcome = chatService.retrieveForEvaluationWithOutcome(
                                benchmarkCase.query(),
                                variant.useSparseSearch(),
                                variant.useRerank(),
                                topK,
                                rerankTopK)
                        .block(Duration.ofMillis(retrievalTimeoutMs));
                elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                if (outcome == null) {
                    throw new IllegalStateException("retrieval returned null");
                }
                List<String> retrievedKeys = mapRetrievedDocumentKeys(outcome.documents(), fileHashByDocUuid);
                FzuCaseResult result = new FzuCaseResult(
                        datasetName + ":" + variant.id() + ":" + benchmarkCase.qid(),
                        datasetName,
                        variant.id(),
                        benchmarkCase.qid(),
                        benchmarkCase.query(),
                        retrievedKeys,
                        topK,
                        gitSha,
                        elapsedMs,
                        true,
                        null,
                        outcome.rerankRequested(),
                        outcome.rerankApplied(),
                        outcome.rerankFallback(),
                        outcome.rerankFallbackReason());
                return new FzuCaseRun(result, new FzuRetrievalMetrics.CaseInput(
                        retrievedKeys, qrels(benchmarkCase), elapsedMs, true));
            } catch (Throwable error) {
                elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                lastError = error;
                log.warn("FZU benchmark failed qid={} variant={} attempt={}/{} errorType={}",
                        benchmarkCase.qid(), variant.id(), attempt + 1, retries + 1,
                        error.getClass().getSimpleName());
            }
        }

        String message = lastError == null ? "unknown retrieval failure" : safeMessage(lastError);
        FzuCaseResult result = new FzuCaseResult(
                datasetName + ":" + variant.id() + ":" + benchmarkCase.qid(),
                datasetName,
                variant.id(),
                benchmarkCase.qid(),
                benchmarkCase.query(),
                List.of(),
                topK,
                gitSha,
                elapsedMs,
                false,
                message,
                variant.useRerank(),
                false,
                false,
                null);
        return new FzuCaseRun(result, new FzuRetrievalMetrics.CaseInput(
                List.of(), qrels(benchmarkCase), elapsedMs, false));
    }

    private List<FzuBenchmarkCase> loadDataset() throws IOException {
        return objectMapper.readValue(datasetResource.getInputStream(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, FzuBenchmarkCase.class));
    }

    private Map<String, String> loadStableDocumentMapping() {
        String sql = """
                SELECT DISTINCT d.doc_uuid, d.file_hash
                FROM knowledge_document d
                JOIN etl_job e ON e.doc_uuid = d.doc_uuid
                WHERE d.status = 'COMPLETED'
                  AND e.status = 'SUCCESS'
                  AND d.doc_uuid IS NOT NULL
                  AND d.file_hash IS NOT NULL
                """;
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            mapping.put(String.valueOf(row.get("doc_uuid")), String.valueOf(row.get("file_hash")));
        }
        if (mapping.isEmpty()) {
            throw new IllegalStateException("No COMPLETED + ETL SUCCESS document mapping found");
        }
        return Map.copyOf(mapping);
    }

    private List<String> mapRetrievedDocumentKeys(List<Document> documents,
                                                  Map<String, String> fileHashByDocUuid) {
        List<String> keys = new ArrayList<>();
        for (Document document : documents == null ? List.<Document>of() : documents) {
            Object rawDocUuid = document.getMetadata().get("doc_uuid");
            if (!(rawDocUuid instanceof String docUuid) || docUuid.isBlank()) {
                throw new IllegalStateException("retrieved Document is missing metadata.doc_uuid");
            }
            String fileHash = fileHashByDocUuid.get(docUuid);
            if (fileHash == null || fileHash.isBlank()) {
                throw new IllegalStateException("metadata.doc_uuid is not mapped to knowledge_document.file_hash: " + docUuid);
            }
            keys.add(fileHash);
        }
        return keys;
    }

    private void validateDataset(List<FzuBenchmarkCase> cases) throws IOException {
        if (cases.size() != 30) {
            throw new IllegalStateException("Expected 30 FZU cases, got " + cases.size());
        }
        Set<String> qids = cases.stream().map(FzuBenchmarkCase::qid).collect(Collectors.toSet());
        if (qids.size() != cases.size() || qids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("FZU dataset qids must be unique and non-null");
        }
        for (FzuBenchmarkCase benchmarkCase : cases) {
            if (benchmarkCase.relevantDocuments() == null || benchmarkCase.relevantDocuments().isEmpty()) {
                throw new IllegalStateException("Case has no qrels: " + benchmarkCase.qid());
            }
            if (benchmarkCase.groundTruthAnswer() == null || benchmarkCase.evidence() == null) {
                throw new IllegalStateException("Case is missing audit fields: " + benchmarkCase.qid());
            }
        }
        JsonNode manifest = objectMapper.readTree(manifestResource.getInputStream());
        if (manifest.path("caseCount").asInt() != cases.size()) {
            throw new IllegalStateException("Dataset and manifest case counts differ");
        }
    }

    private void validateQrelsMapping(List<FzuBenchmarkCase> cases, Map<String, String> actualMapping) {
        for (FzuBenchmarkCase benchmarkCase : cases) {
            for (FzuRelevantDocument relevant : benchmarkCase.relevantDocuments()) {
                String actualHash = actualMapping.get(relevant.docUuid());
                if (actualHash == null) {
                    throw new IllegalStateException("qrels doc_uuid is not a current COMPLETED + SUCCESS document: "
                            + relevant.docUuid());
                }
                if (!actualHash.equals(relevant.documentKey())) {
                    throw new IllegalStateException("qrels file_hash mismatch for doc_uuid " + relevant.docUuid());
                }
            }
        }
    }

    private Map<String, Integer> qrels(FzuBenchmarkCase benchmarkCase) {
        return benchmarkCase.relevantDocuments().stream().collect(Collectors.toMap(
                FzuRelevantDocument::documentKey,
                FzuRelevantDocument::relevance,
                Math::max,
                LinkedHashMap::new));
    }

    private void writeRunManifest(Path output, String gitSha, int caseCount) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("dataset", datasetName);
        manifest.put("datasetResource", datasetResource.getDescription());
        manifest.put("gitSha", gitSha);
        manifest.put("profile", "benchmark-fzu");
        manifest.put("caseCount", caseCount);
        manifest.put("variants", BenchmarkVariant.fixedMatrix().stream().map(BenchmarkVariant::id).toList());
        manifest.put("topK", topK);
        manifest.put("rerankTopK", rerankTopK);
        manifest.put("retrievalTimeoutMs", retrievalTimeoutMs);
        manifest.put("retries", retries);
        Files.writeString(output,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8);
    }

    private String currentGitSha() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 && !value.isBlank() ? value : "unknown";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    record FzuBenchmarkCase(String qid,
                            String query,
                            String category,
                            List<FzuRelevantDocument> relevantDocuments,
                            String groundTruthAnswer,
                            String evidence,
                            String notes) {
    }

    record FzuRelevantDocument(String documentKey,
                               String docUuid,
                               String fileName,
                               int relevance) {
    }

    record FzuCaseResult(String cacheKey,
                         String dataset,
                         String variant,
                         String qid,
                         String query,
                         List<String> retrievedDocumentKeys,
                         int topK,
                         String gitSha,
                         long latencyMs,
                         boolean success,
                         String error,
                         boolean rerankRequested,
                         boolean rerankApplied,
                         boolean rerankFallback,
                         String rerankFallbackReason) {
    }

    private record FzuCaseRun(FzuCaseResult result, FzuRetrievalMetrics.CaseInput metricsInput) {
    }
}
