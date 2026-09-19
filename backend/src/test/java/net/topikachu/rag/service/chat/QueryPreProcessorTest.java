package net.topikachu.rag.service.chat;

import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import net.topikachu.rag.observability.TracingSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryPreProcessorTest {

    @Mock
    private ChatMemory chatMemory;

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

    @BeforeEach
    void setUp() {
        lenient().when(tracingSupport.traceMono(anyString(), anyMap(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    @Test
    void interceptsOnlyExactChitChatInputs() {
        QueryPreProcessor processor = new QueryPreProcessor(chatMemory, strategyFactory, reactiveChatGateway, tracingSupport);

        StepVerifier.create(processor.process("  你好  ", "conversation-1", "qwen"))
                .assertNext(result -> {
                    assertEquals(true, result.isChitChat());
                    assertEquals("同学你好！我是福州大学教务问答助手，可以向我咨询选课、转专业、缓考、推免或培养方案等相关事宜。",
                            result.directReply());
                })
                .verifyComplete();

        verify(chatMemory, never()).get(anyString());
        verifyNoModelInteractions();
    }

    @Test
    void rewritesUsingOnlyTheMostRecentFourConversationMessages() {
        QueryPreProcessor processor = new QueryPreProcessor(chatMemory, strategyFactory, reactiveChatGateway, tracingSupport);
        when(chatMemory.get("conversation-1")).thenReturn(List.of(
                new UserMessage("old-1"),
                new AssistantMessage("old-2"),
                new UserMessage("current topic"),
                new AssistantMessage("context answer"),
                new UserMessage("follow-up"),
                new AssistantMessage("follow-up answer")));
        when(strategyFactory.getStrategy("qwen")).thenReturn(strategy);
        when(strategy.getChatClient()).thenReturn(chatClient);
        when(reactiveChatGateway.callBufferedStream(
                same(chatClient), anyString(),
                org.mockito.ArgumentMatchers.argThat(params -> {
                    String history = String.valueOf(params.get("history"));
                    return history.contains("current topic")
                            && history.contains("context answer")
                            && history.contains("follow-up")
                            && history.contains("follow-up answer")
                            && !history.contains("old-1")
                            && !history.contains("old-2");
                }),
                eq("那它的条件是什么？")))
                .thenReturn(Mono.just("转专业申请条件"));

        StepVerifier.create(processor.process("那它的条件是什么？", "conversation-1", "qwen"))
                .assertNext(result -> {
                    assertEquals(false, result.isChitChat());
                    assertEquals("转专业申请条件", result.searchTargetQuery());
                })
                .verifyComplete();

        verify(strategyFactory).getStrategy("qwen");
        verify(reactiveChatGateway).callBufferedStream(same(chatClient), anyString(), anyMap(), eq("那它的条件是什么？"));
    }

    @Test
    void keepsStandaloneQueryWithoutHistory() {
        QueryPreProcessor processor = new QueryPreProcessor(chatMemory, strategyFactory, reactiveChatGateway, tracingSupport);
        when(chatMemory.get("conversation-1")).thenReturn(List.of());

        StepVerifier.create(processor.process("  转专业政策  ", "conversation-1", "qwen"))
                .assertNext(result -> {
                    assertEquals(false, result.isChitChat());
                    assertEquals("转专业政策", result.searchTargetQuery());
                })
                .verifyComplete();

        verifyNoModelInteractions();
    }

    @Test
    void rewriteFailureFallsBackToOriginalQuery() {
        QueryPreProcessor processor = new QueryPreProcessor(chatMemory, strategyFactory, reactiveChatGateway, tracingSupport);
        when(chatMemory.get("conversation-1")).thenReturn(List.of(
                new UserMessage("转专业"),
                new AssistantMessage("请问具体哪一方面？")));
        when(strategyFactory.getStrategy("qwen")).thenReturn(strategy);
        when(strategy.getChatClient()).thenReturn(chatClient);
        when(reactiveChatGateway.callBufferedStream(
                same(chatClient), anyString(), anyMap(), eq("那条件呢？")))
                .thenReturn(Mono.error(new IllegalStateException("timeout")));

        StepVerifier.create(processor.process("那条件呢？", "conversation-1", "qwen"))
                .assertNext(result -> {
                    assertEquals(false, result.isChitChat());
                    assertEquals("那条件呢？", result.searchTargetQuery());
                })
                .verifyComplete();
    }

    @Test
    void blankRewriteFallsBackToOriginalQuery() {
        QueryPreProcessor processor = new QueryPreProcessor(chatMemory, strategyFactory, reactiveChatGateway, tracingSupport);
        when(chatMemory.get("conversation-1")).thenReturn(List.of(
                new UserMessage("转专业"),
                new AssistantMessage("请问具体哪一方面？")));
        when(strategyFactory.getStrategy("qwen")).thenReturn(strategy);
        when(strategy.getChatClient()).thenReturn(chatClient);
        when(reactiveChatGateway.callBufferedStream(
                same(chatClient), anyString(), anyMap(), eq("那条件呢？")))
                .thenReturn(Mono.just("  "));

        StepVerifier.create(processor.process("那条件呢？", "conversation-1", "qwen"))
                .assertNext(result -> assertEquals("那条件呢？", result.searchTargetQuery()))
                .verifyComplete();
    }

    private void verifyNoModelInteractions() {
        verify(strategyFactory, never()).getStrategy(anyString());
        verify(reactiveChatGateway, never()).callBufferedStream(any(), anyString(), anyMap(), anyString());
    }
}
