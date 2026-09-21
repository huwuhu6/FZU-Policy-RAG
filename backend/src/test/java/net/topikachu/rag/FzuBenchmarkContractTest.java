package net.topikachu.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FzuBenchmarkContractTest {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    @Test
    void frozenDatasetHasThirtyAuditableCasesAndStableQrelFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<FzuAblationStudyRunner.FzuBenchmarkCase> cases;
        try (InputStream input = resource("evaluation/fzu_policy_retrieval_v1.json")) {
            cases = objectMapper.readValue(input,
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, FzuAblationStudyRunner.FzuBenchmarkCase.class));
        }

        assertEquals(30, cases.size());
        Set<String> qids = new HashSet<>();
        Set<String> documentKeys = new HashSet<>();
        for (FzuAblationStudyRunner.FzuBenchmarkCase benchmarkCase : cases) {
            assertTrue(qids.add(benchmarkCase.qid()), "duplicate qid: " + benchmarkCase.qid());
            assertFalse(benchmarkCase.relevantDocuments().isEmpty());
            assertNotNull(benchmarkCase.groundTruthAnswer());
            assertNotNull(benchmarkCase.evidence());
            benchmarkCase.relevantDocuments().forEach(relevant -> {
                assertTrue(SHA256.matcher(relevant.documentKey()).matches());
                assertFalse(relevant.docUuid().isBlank());
                assertTrue(relevant.relevance() == 1 || relevant.relevance() == 2);
                documentKeys.add(relevant.documentKey());
            });
        }
        assertTrue(documentKeys.size() >= 10);
    }

    @Test
    void manifestAndBenchmarkProfileDeclareTheSameFrozenDataset() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream input = resource("evaluation/fzu_policy_retrieval_v1.manifest.json")) {
            assertEquals(30, objectMapper.readTree(input).path("caseCount").asInt());
        }

        Properties properties = new Properties();
        try (InputStream input = resource("application-benchmark-fzu.properties")) {
            properties.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
        }
        assertEquals("fzu_policy_rag_baseline_v1", properties.getProperty("spring.ai.vectorstore.milvus.collection-name"));
        assertEquals("classpath:evaluation/fzu_policy_retrieval_v1.json", properties.getProperty("rag.benchmark.dataset-file"));
        assertEquals("10", properties.getProperty("rag.benchmark.top-k"));
        assertEquals("40", properties.getProperty("rag.benchmark.rerank-top-k"));
        assertEquals("false", properties.getProperty("rag.benchmark.auto-import-corpus"));
        assertFalse(properties.stringPropertyNames().stream()
                .anyMatch(name -> properties.getProperty(name).contains("C:/Users/")
                        || properties.getProperty(name).contains("C:\\Users\\")));
    }

    private InputStream resource(String path) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(input, "missing resource: " + path);
        return input;
    }
}
