package net.topikachu.rag.api;

import net.topikachu.rag.agent.AgentChatService;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.CurrentUserContextService;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.service.DocumentService;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.ChatService;
import net.topikachu.rag.service.etl.EtlPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestApiStreamingTest {

    @Mock
    private ChatService chatService;

    @Mock
    private AgentChatService agentChatService;

    @Mock
    private TracingSupport tracingSupport;

    @Mock
    private CurrentUserContextService currentUserContextService;

    @Mock
    private DocumentService documentService;

    @Mock
    private EtlPipeline etlPipeline;

    private RestApi restApi;

    @BeforeEach
    void setUp() {
        restApi = new RestApi(
                chatService,
                agentChatService,
                tracingSupport,
                currentUserContextService,
                documentService,
                etlPipeline);
    }

    @Test
    void emitsSourcesThenEveryAnswerChunkThenDone() {
        CurrentUserContext user = user();
        configureScope(user);
        when(chatService.streamWithSources(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(new ChatService.ChatStreamResponse(
                        Flux.just("第一段", "第二段"), List.of())));

        List<ServerSentEvent<Object>> events = chat("question").collectList().block();

        assertEquals(List.of("sources", "message", "message", "done"),
                events.stream().map(ServerSentEvent::event).toList());
        assertEquals("第一段", events.get(1).data());
        assertEquals("第二段", events.get(2).data());
    }

    @Test
    void midStreamFailureEmitsErrorAndDoneWithoutFallbackMessage() {
        CurrentUserContext user = user();
        configureScope(user);
        when(chatService.streamWithSources(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(new ChatService.ChatStreamResponse(
                        Flux.concat(Flux.just("第一段"), Flux.error(new IllegalStateException("stream failed"))),
                        List.of())));

        List<ServerSentEvent<Object>> events = chat("question").collectList().block();

        assertEquals(List.of("sources", "message", "error", "done"),
                events.stream().map(ServerSentEvent::event).toList());
        assertEquals("第一段", events.get(1).data());
        assertFalse(events.stream().anyMatch(event -> "系统繁忙，请稍后重试。".equals(event.data())));
    }

    private Flux<ServerSentEvent<Object>> chat(String question) {
        Principal principal = () -> "user-1";
        return restApi.chat(
                new RestApi.ChatRequest(question, List.of(), List.of(), "qwen", "rag", "msg-1"),
                "conversation-1",
                null,
                null,
                null,
                Mono.just(principal));
    }

    private void configureScope(CurrentUserContext user) {
        when(currentUserContextService.resolveByUsername("user-1")).thenReturn(user);
        when(documentService.resolveEffectiveSearchScope(any(), any())).thenReturn(Mono.just(SearchScope.empty()));
    }

    private CurrentUserContext user() {
        return new CurrentUserContext(
                "user-1", "user-1", "USER", "dept-1", "Dept", "public", false);
    }
}
