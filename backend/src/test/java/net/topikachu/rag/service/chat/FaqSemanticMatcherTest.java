package net.topikachu.rag.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.etl.DashScopeEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaqSemanticMatcherTest {

    @Mock
    private DashScopeEmbeddingClient embeddingClient;

    @Mock
    private TracingSupport tracingSupport;

    @Test
    void matchesOnlyWhenThresholdAndMarginPass() {
        FaqSemanticMatcher matcher = matcher("""
                [{"id":"faq-1","questions":["转专业什么时候申请"],"answer":"请查看教务处通知。"}]
                """, 0.95, 0.03);
        when(embeddingClient.embedDenseQuery("转专业什么时候申请"))
                .thenReturn(Mono.just(List.of(1.0f, 0.0f)));
        when(embeddingClient.embedDenseQuery("什么时候可以申请转专业"))
                .thenReturn(Mono.just(List.of(1.0f, 0.0f)));

        StepVerifier.create(matcher.match("什么时候可以申请转专业"))
                .assertNext(match -> {
                    org.junit.jupiter.api.Assertions.assertTrue(match.matched());
                    org.junit.jupiter.api.Assertions.assertEquals("faq-1", match.faqId());
                })
                .verifyComplete();
    }

    @Test
    void rejectsLowSimilarity() {
        FaqSemanticMatcher matcher = matcher("""
                [{"id":"faq-1","questions":["转专业什么时候申请"],"answer":"请查看教务处通知。"}]
                """, 0.95, 0.03);
        when(embeddingClient.embedDenseQuery("转专业什么时候申请"))
                .thenReturn(Mono.just(List.of(1.0f, 0.0f)));
        when(embeddingClient.embedDenseQuery("奖学金申请条件"))
                .thenReturn(Mono.just(List.of(0.0f, 1.0f)));

        StepVerifier.create(matcher.match("奖学金申请条件"))
                .assertNext(match -> org.junit.jupiter.api.Assertions.assertFalse(match.matched()))
                .verifyComplete();
    }

    @Test
    void rejectsAmbiguousTopTwoCandidates() {
        FaqSemanticMatcher matcher = matcher("""
                [
                  {"id":"faq-1","questions":["转专业什么时候申请"],"answer":"回答一"},
                  {"id":"faq-2","questions":["什么时候可以申请转专业"],"answer":"回答二"}
                ]
                """, 0.90, 0.03);
        when(embeddingClient.embedDenseQuery("转专业什么时候申请"))
                .thenReturn(Mono.just(List.of(1.0f, 0.0f)));
        when(embeddingClient.embedDenseQuery("什么时候可以申请转专业"))
                .thenReturn(Mono.just(List.of(1.0f, 0.0f)));
        when(embeddingClient.embedDenseQuery("什么时候申请转专业"))
                .thenReturn(Mono.just(List.of(1.0f, 0.0f)));

        StepVerifier.create(matcher.match("什么时候申请转专业"))
                .assertNext(match -> org.junit.jupiter.api.Assertions.assertFalse(match.matched()))
                .verifyComplete();
    }

    @Test
    void embeddingFailureFailsOpen() {
        FaqSemanticMatcher matcher = matcher("""
                [{"id":"faq-1","questions":["转专业什么时候申请"],"answer":"回答"}]
                """, 0.95, 0.03);
        when(embeddingClient.embedDenseQuery(anyString()))
                .thenReturn(Mono.error(new IllegalStateException("embedding unavailable")));

        StepVerifier.create(matcher.match("什么时候申请转专业"))
                .assertNext(match -> org.junit.jupiter.api.Assertions.assertFalse(match.matched()))
                .verifyComplete();
    }

    private FaqSemanticMatcher matcher(String json, double threshold, double margin) {
        when(tracingSupport.traceMono(anyString(), anyMap(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        return new FaqSemanticMatcher(
                embeddingClient,
                new ObjectMapper(),
                tracingSupport,
                new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)),
                true,
                threshold,
                margin);
    }
}
