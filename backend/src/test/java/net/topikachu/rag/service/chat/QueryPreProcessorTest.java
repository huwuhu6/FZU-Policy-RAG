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
import org.springframework.ai.chat.memory.ChatMemory;
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
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryPreProcessorTest {

    @Mock
    private ChatMemory chatMemory;
    @Mock
    private ConversationIntentRouter conversationIntentRouter;
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
    @Mock
    private FaqSemanticMatcher faqSemanticMatcher;

    @BeforeEach
    void setUp() {
        lenient().when(tracingSupport.getCurrentTraceId()).thenReturn("trace-1");
        lenient().when(tracingSupport.traceMono(anyString(), anyMap(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        lenient().when(faqSemanticMatcher.match(anyString(), any()))
                .thenReturn(Mono.just(FaqSemanticMatcher.Match.miss()));
    }

    @Test
    void exactAliasIsHandledWithoutRouterOrRetrieval() {
        StepVerifier.create(processor().process("  你是谁？？  ", "conversation-1", "qwen"))
                .assertNext(result -> {
                    assertEquals(QueryPreProcessor.PreprocessRoute.EXACT, result.route());
                    assertEquals("同学你好！我是福州大学教务问答助手，可以向我咨询选课、转专业、缓考、推免或培养方案等相关事宜。",
                            result.directReply());
                })
                .verifyComplete();

        verify(chatMemory, never()).get(anyString());
        verify(conversationIntentRouter, never()).route(anyString(), any(), anyString(), any());
        verify(faqSemanticMatcher, never()).match(anyString(), any());
    }

    @Test
    void greetingWithKnowledgeIntentIsRetrieved() {
        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(conversationIntentRouter.route(eq("你好，我想问转专业政策"), eq(List.of()), eq("qwen"), any()))
                .thenReturn(Mono.just(new ConversationRouteResult(
                        ConversationRouteResult.Route.RETRIEVE, "", "你好，我想问转专业政策")));

        StepVerifier.create(processor().process("你好，我想问转专业政策？", "conversation-1", "qwen"))
                .assertNext(result -> {
                    assertEquals(QueryPreProcessor.PreprocessRoute.RETRIEVE, result.route());
                    assertEquals("你好，我想问转专业政策", result.searchTargetQuery());
                })
                .verifyComplete();

        verify(faqSemanticMatcher).match(eq("你好，我想问转专业政策"), any());
    }

    @Test
    void modelSmalltalkDoesNotCallFaqOrRetrieval() {
        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(conversationIntentRouter.route(eq("哈哈"), eq(List.of()), eq("qwen"), any()))
                .thenReturn(Mono.just(new ConversationRouteResult(
                        ConversationRouteResult.Route.SMALLTALK, "同学你好。", "")));

        StepVerifier.create(processor().process("哈哈", "conversation-1", "qwen"))
                .assertNext(result -> {
                    assertEquals(QueryPreProcessor.PreprocessRoute.SMALLTALK, result.route());
                    assertEquals("同学你好。", result.directReply());
                })
                .verifyComplete();

        verify(faqSemanticMatcher, never()).match(anyString(), any());
    }

    @Test
    void vagueQuestionWithoutHistoryIsClarified() {
        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(conversationIntentRouter.route(eq("什么意思"), eq(List.of()), eq("qwen"), any()))
                .thenReturn(Mono.just(new ConversationRouteResult(
                        ConversationRouteResult.Route.CLARIFY, "请补充你想了解的具体政策或问题。", "")));

        StepVerifier.create(processor().process("什么意思？", "conversation-1", "qwen"))
                .assertNext(result -> assertEquals(QueryPreProcessor.PreprocessRoute.CLARIFY, result.route()))
                .verifyComplete();

        verify(faqSemanticMatcher, never()).match(anyString(), any());
    }

    @Test
    void historyCanResolveVagueFollowupIntoRetrievalQuery() {
        List<Message> history = List.of(
                new UserMessage("转专业"), new AssistantMessage("可以咨询申请条件。"));
        when(chatMemory.get("conversation-1")).thenReturn(history);
        when(conversationIntentRouter.route(eq("那条件呢"), eq(history), eq("qwen"), any()))
                .thenReturn(Mono.just(new ConversationRouteResult(
                        ConversationRouteResult.Route.RETRIEVE, "", "转专业申请条件")));

        StepVerifier.create(processor().process("那条件呢？", "conversation-1", "qwen"))
                .assertNext(result -> {
                    assertEquals(QueryPreProcessor.PreprocessRoute.RETRIEVE, result.route());
                    assertEquals("转专业申请条件", result.searchTargetQuery());
                    assertEquals(true, result.queryChanged());
                })
                .verifyComplete();
    }

    @Test
    void faqHitShortCircuitsNormalRetrieval() {
        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(conversationIntentRouter.route(anyString(), eq(List.of()), eq("qwen"), any()))
                .thenReturn(Mono.just(new ConversationRouteResult(
                        ConversationRouteResult.Route.RETRIEVE, "", "转专业什么时候申请")));
        when(faqSemanticMatcher.match(eq("转专业什么时候申请"), any()))
                .thenReturn(Mono.just(new FaqSemanticMatcher.Match(
                        true, "faq-transfer-frequency", "每学年办理一次。", 0.98d)));

        StepVerifier.create(processor().process("转专业什么时候申请", "conversation-1", "qwen"))
                .assertNext(result -> {
                    assertEquals(QueryPreProcessor.PreprocessRoute.FAQ, result.route());
                    assertEquals("faq-transfer-frequency", result.faqId());
                    assertEquals("每学年办理一次。", result.directReply());
                })
                .verifyComplete();
    }

    @Test
    void routerFailureFallsBackToOriginalRagPath() {
        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(conversationIntentRouter.route(anyString(), any(), eq("qwen"), any()))
                .thenReturn(Mono.error(new IllegalStateException("router timeout")));

        StepVerifier.create(processor().process("转专业政策", "conversation-1", "qwen"))
                .assertNext(result -> {
                    assertEquals(QueryPreProcessor.PreprocessRoute.RETRIEVE, result.route());
                    assertEquals("转专业政策", result.searchTargetQuery());
                    assertEquals(true, result.fallback());
                })
                .verifyComplete();

        verify(faqSemanticMatcher).match(eq("转专业政策"), any());
    }

    @Test
    void routerFailureWithHistoryUsesLegacyRewriteFallback() {
        List<Message> history = List.of(
                new UserMessage("转专业"), new AssistantMessage("可以咨询申请条件。"));
        when(chatMemory.get("conversation-1")).thenReturn(history);
        when(conversationIntentRouter.route(anyString(), any(), eq("qwen"), any()))
                .thenReturn(Mono.error(new IllegalStateException("router timeout")));
        when(strategyFactory.getStrategy("qwen")).thenReturn(strategy);
        when(strategy.getChatClient()).thenReturn(chatClient);
        when(reactiveChatGateway.callBufferedStream(
                same(chatClient), anyString(), anyMap(), eq("那条件呢")))
                .thenReturn(Mono.just("转专业申请条件"));

        StepVerifier.create(processor().process("那条件呢？", "conversation-1", "qwen"))
                .assertNext(result -> assertEquals("转专业申请条件", result.searchTargetQuery()))
                .verifyComplete();
    }

    private QueryPreProcessor processor() {
        return new QueryPreProcessor(
                chatMemory,
                conversationIntentRouter,
                tracingSupport,
                faqSemanticMatcher,
                strategyFactory,
                reactiveChatGateway);
    }
}
