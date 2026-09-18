package net.topikachu.rag.service.etl;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashScopeEmbeddingClientTest {

    @Test
    void mapsDashScopeSparseIndexAndValueToMilvusSparseVector() {
        ExchangeFunction exchangeFunction = mock(ExchangeFunction.class);
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response("""
                {
                  "output": {
                    "embeddings": [{
                      "text_index": 0,
                      "embedding": [0.1, 0.2],
                      "sparse_embedding": [
                        {"index": 7149, "value": 0.829, "token": "政策"},
                        {"index": 42, "value": 0.125, "token": "办法"}
                      ]
                    }]
                  }
                }
                """)));

        DashScopeEmbeddingClient client = client(exchangeFunction, 2);

        DashScopeEmbeddingClient.HybridEmbedding result = client.embedDocument("政策办法").block();

        assertEquals(List.of(0.1f, 0.2f), result.denseVector());
        assertEquals(List.of(42L, 7149L), result.sparseVector().keySet().stream().toList());
        assertEquals(0.829f, result.sparseVector().get(7149L));
        assertEquals(0.125f, result.sparseVector().get(42L));
    }

    @Test
    void rejectsDenseDimensionMismatchAtClientBoundary() {
        ExchangeFunction exchangeFunction = mock(ExchangeFunction.class);
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response("""
                {
                  "output": {
                    "embeddings": [{
                      "embedding": [0.1],
                      "sparse_embedding": [{"index": 1, "value": 0.2}]
                    }]
                  }
                }
                """)));

        DashScopeEmbeddingClient client = client(exchangeFunction, 2);

        StepVerifier.create(client.embedQuery("政策"))
                .expectErrorMessage("DashScope dense vector dimension mismatch: expected=2, actual=1")
                .verify();
    }

    @Test
    void rejectsMissingApiKeyWithoutSendingRequest() {
        ExchangeFunction exchangeFunction = mock(ExchangeFunction.class);
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(
                "http://dashscope.test",
                "",
                "qwen3.7-text-embedding-flash",
                2,
                1000,
                WebClient.builder().exchangeFunction(exchangeFunction));

        StepVerifier.create(client.embedQuery("政策"))
                .expectErrorMessage("DashScope embedding API key is not configured")
                .verify();
    }

    private DashScopeEmbeddingClient client(ExchangeFunction exchangeFunction, int dimension) {
        return new DashScopeEmbeddingClient(
                "http://dashscope.test",
                "test-key",
                "qwen3.7-text-embedding-flash",
                dimension,
                1000,
                WebClient.builder().exchangeFunction(exchangeFunction));
    }

    private static ClientResponse response(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
