package net.topikachu.rag.service.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RerankServiceTest {

    @Test
    void sortsByRelevanceScoreAndWritesScoreMetadata() {
        RerankService service = service(response("""
                {
                  "output": {
                    "results": [
                      {"index": 2, "relevance_score": 0.95},
                      {"index": 0, "relevance_score": 0.80}
                    ]
                  },
                  "usage": {"prompt_tokens": 10, "total_tokens": 10}
                }
                """));
        List<Document> docs = documents();

        List<Document> result = service.rerank("question", docs, 2).block();

        assertEquals("doc-2", result.get(0).getMetadata().get("doc_uuid"));
        assertEquals("doc-0", result.get(1).getMetadata().get("doc_uuid"));
        assertEquals(0.95, result.get(0).getMetadata().get("rerank_score"));
        assertEquals(0.80, result.get(1).getMetadata().get("rerank_score"));
    }

    @Test
    void limitsResultsToTopN() {
        RerankService service = service(response("""
                {
                  "output": {
                    "results": [
                      {"index": 2, "relevance_score": 0.95},
                      {"index": 0, "relevance_score": 0.80},
                      {"index": 1, "relevance_score": 0.70}
                    ]
                  }
                }
                """));

        List<Document> result = service.rerank("question", documents(), 2).block();

        assertEquals(2, result.size());
        assertEquals(List.of("doc-2", "doc-0"), result.stream()
                .map(document -> document.getMetadata().get("doc_uuid"))
                .toList());
    }

    @Test
    void missingResultsProducesProtocolError() {
        RerankService service = service(response("{\"output\":{}}"));

        StepVerifier.create(service.rerank("question", documents(), 2))
                .expectErrorMessage("DashScope rerank response missing results")
                .verify();
    }

    @Test
    void outOfBoundsIndexProducesProtocolError() {
        RerankService service = service(response("""
                {"output":{"results":[{"index":999,"relevance_score":0.9}]}}
                """));

        StepVerifier.create(service.rerank("question", documents(), 2))
                .expectErrorMessage("DashScope rerank result index out of bounds: 999")
                .verify();
    }

    @Test
    void fallbackReturnsOriginalTopN() {
        List<Document> docs = documents();

        List<Document> result = service(mock(ExchangeFunction.class))
                .rerankFallback("question", docs, 2, new IllegalStateException("upstream unavailable"))
                .block();

        assertEquals(List.of("doc-0", "doc-1"), result.stream()
                .map(document -> document.getMetadata().get("doc_uuid"))
                .toList());
    }

    private RerankService service(String responseBody) {
        ExchangeFunction exchangeFunction = mock(ExchangeFunction.class);
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response(responseBody)));
        return service(exchangeFunction);
    }

    private RerankService service(ClientResponse response) {
        ExchangeFunction exchangeFunction = mock(ExchangeFunction.class);
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));
        return service(exchangeFunction);
    }

    private RerankService service(ExchangeFunction exchangeFunction) {
        RerankService service = new RerankService(WebClient.builder().exchangeFunction(exchangeFunction));
        ReflectionTestUtils.setField(service, "rerankUrl", "http://dashscope.test/rerank");
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "model", "qwen3.7-text-rerank");
        ReflectionTestUtils.setField(service, "timeoutMs", 1000);
        return service;
    }

    private List<Document> documents() {
        return List.of(
                new Document("document 0", Map.of("doc_uuid", "doc-0")),
                new Document("document 1", Map.of("doc_uuid", "doc-1")),
                new Document("document 2", Map.of("doc_uuid", "doc-2")));
    }

    private static ClientResponse response(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
