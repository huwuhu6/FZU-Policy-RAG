package net.topikachu.rag.service.chat;

import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationIntentRouterTest {

    @Mock
    private ChatModelStrategyFactory strategyFactory;
    @Mock
    private ChatModelStrategy strategy;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ReactiveChatGateway reactiveChatGateway;
    @Mock
    private TracingSupport tracingSupport;

    private ConversationIntentRouter router;

    @BeforeEach
    void setUp() {
        when(tracingSupport.traceMono(anyString(), anyMap(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(strategyFactory.getStrategy("qwen")).thenReturn(strategy);
        when(strategy.getChatClient()).thenReturn(chatClient);
        router = new ConversationIntentRouter(strategyFactory, reactiveChatGateway, tracingSupport);
    }

    @Test
    void usesSelectedModelAndHistoryForRetrieveRoute() {
        List<Message> history = List.of(
                new UserMessage("我想转专业"),
                new AssistantMessage("可以继续问申请条件。"));
        when(reactiveChatGateway.callBufferedConversationRoute(
                eq(chatClient), anyString(),
                org.mockito.ArgumentMatchers.argThat(params ->
                        "那条件呢".equals(params.get("question"))
                                && String.valueOf(params.get("history")).contains("我想转专业")
                                && String.valueOf(params.get("history")).contains("申请条件")),
                eq(history), eq("那条件呢"), eq("conversation-1")))
                .thenReturn(Mono.just(new ConversationRouteResult(
                        ConversationRouteResult.Route.RETRIEVE, "", "转专业申请条件")));

        StepVerifier.create(router.route("那条件呢", history, "qwen",
                        RagRequestContext.create("trace-1", "conversation-1", "msg-1", "qwen")))
                .assertNext(result -> {
                    assertEquals(ConversationRouteResult.Route.RETRIEVE, result.route());
                    assertEquals("转专业申请条件", result.searchTargetQuery());
                })
                .verifyComplete();
    }

    @Test
    void fillsSafeDirectReplyWhenModelReturnsBlankReply() {
        when(reactiveChatGateway.callBufferedConversationRoute(
                eq(chatClient), anyString(), anyMap(), eq(List.of()), eq("哈哈"), eq("conversation-1")))
                .thenReturn(Mono.just(new ConversationRouteResult(
                        ConversationRouteResult.Route.SMALLTALK, "  ", "")));

        StepVerifier.create(router.route("哈哈", List.of(), "qwen",
                        RagRequestContext.create("trace-1", "conversation-1", "msg-1", "qwen")))
                .assertNext(result -> {
                    assertEquals(ConversationRouteResult.Route.SMALLTALK, result.route());
                    assertEquals(ConversationIntentRouter.SMALLTALK_FALLBACK_REPLY, result.directReply());
                })
                .verifyComplete();
    }

    @Test
    void replacesInternalRouterReplyWithUserFacingAssistantReply() {
        when(reactiveChatGateway.callBufferedConversationRoute(
                eq(chatClient), anyString(), anyMap(), eq(List.of()), eq("你是谁啊"), eq("conversation-1")))
                .thenReturn(Mono.just(new ConversationRouteResult(
                        ConversationRouteResult.Route.SMALLTALK,
                        "我是福州大学教务知识库的会话路由器。",
                        "")));

        StepVerifier.create(router.route("你是谁啊", List.of(), "qwen",
                        RagRequestContext.create("trace-1", "conversation-1", "msg-1", "qwen")))
                .assertNext(result -> {
                    assertEquals(ConversationIntentRouter.SMALLTALK_FALLBACK_REPLY, result.directReply());
                })
                .verifyComplete();
    }
}
