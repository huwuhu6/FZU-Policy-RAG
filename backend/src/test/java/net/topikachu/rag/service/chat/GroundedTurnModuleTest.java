package net.topikachu.rag.service.chat;

import net.topikachu.rag.chat.history.ChatHistoryService;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.timeout;

@ExtendWith(MockitoExtension.class)
class GroundedTurnModuleTest {

    @Mock
    private ContextFormatter contextFormatter;

    @Mock
    private ChatModelStrategyFactory strategyFactory;

    @Mock
    private ChatModelStrategy strategy;

    @Mock
    private ReactiveChatGateway reactiveChatGateway;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private ChatHistoryService chatHistoryService;

    @Mock
    private TracingSupport tracingSupport;

    private GroundedTurnModule module;

    @BeforeEach
    void setUp() {
        module = new GroundedTurnModule(
                contextFormatter,
                strategyFactory,
                reactiveChatGateway,
                new UsedSourceValidator(),
                tracingSupport,
                chatMemory,
                chatHistoryService);
        org.mockito.Mockito.lenient().when(tracingSupport.traceMono(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyMap(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    @Test
    void publishesOnlyEvidenceActuallyUsedByTheAnswer() {
        Document first = candidate("ev-1", "doc-1", "first.pdf");
        Document second = candidate("ev-2", "doc-2", "second.pdf");
        GroundedTurnModule.Command command = command(List.of(first, second));

        when(chatMemory.get("conversation-1")).thenReturn(List.of(
                new UserMessage("previous question"),
                new AssistantMessage("previous answer")));
        when(contextFormatter.formatParentContextsWithStats(anyList()))
                .thenReturn(new ContextFormatter.FormattedContext("parent context", false));
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callSourcedAnswer(
                same(reactiveChatGateway),
                eq("parent context"),
                eq("question"),
                eq("conversation-1"),
                argThat(history -> history.size() == 2)))
                .thenReturn(Mono.just(new SourcedAnswerResult("answer", "factual", List.of("ev-2"))));
        when(chatHistoryService.saveTurn(
                "conversation-1", "user-1", "question", "answer", "model-1", "agent", "msg-1"))
                .thenReturn(Mono.empty());
        GroundedTurnModule.Result result = module.execute(command).block();

        assertEquals("answer", result.answer());
        assertEquals(List.of("ev-2"), result.usedSources().stream().map(UsedSource::evidenceId).toList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> memoryCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatMemory).add(eq("conversation-1"), memoryCaptor.capture());
        assertEquals(2, memoryCaptor.getValue().size());
        assertEquals("question", memoryCaptor.getValue().get(0).getText());
        assertEquals("answer", memoryCaptor.getValue().get(1).getText());
    }

    @Test
    void invalidUsedEvidenceDoesNotCommitTheTurn() {
        GroundedTurnModule.Command command = command(List.of(candidate("ev-1", "doc-1", "first.pdf")));

        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatParentContextsWithStats(anyList()))
                .thenReturn(new ContextFormatter.FormattedContext("parent context", false));
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callSourcedAnswer(
                same(reactiveChatGateway),
                eq("parent context"),
                eq("question"),
                eq("conversation-1"),
                anyList()))
                .thenReturn(Mono.just(new SourcedAnswerResult("answer", "factual", List.of("ev-missing"))));

        StepVerifier.create(module.execute(command))
                .expectErrorMatches(error -> error instanceof SourceValidationException validationError
                        && UsedSourceValidator.REASON_EVIDENCE_ID_NOT_IN_CANDIDATES.equals(validationError.getReason()))
                .verify();

        verify(chatMemory, never()).add(eq("conversation-1"), anyList());
        verify(chatHistoryService, never()).saveTurn(
                eq("conversation-1"), eq("user-1"), eq("question"), eq("answer"),
                eq("model-1"), eq("agent"), eq("msg-1"));
    }

    @Test
    void doesNotReturnBeforeEveryCommitCompletes() {
        GroundedTurnModule.Command command = command(List.of(candidate("ev-1", "doc-1", "first.pdf")));
        Sinks.Empty<Void> memoryBarrier = Sinks.empty();
        Sinks.Empty<Void> historyBarrier = Sinks.empty();

        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        doAnswer(invocation -> {
            memoryBarrier.asMono().block();
            return null;
        }).when(chatMemory).add(eq("conversation-1"), anyList());
        when(contextFormatter.formatParentContextsWithStats(anyList()))
                .thenReturn(new ContextFormatter.FormattedContext("parent context", false));
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callSourcedAnswer(
                same(reactiveChatGateway), eq("parent context"), eq("question"), eq("conversation-1"), anyList()))
                .thenReturn(Mono.just(new SourcedAnswerResult("answer", "factual", List.of("ev-1"))));
        when(chatHistoryService.saveTurn(
                "conversation-1", "user-1", "question", "answer", "model-1", "agent", "msg-1"))
                .thenReturn(historyBarrier.asMono());
        StepVerifier.create(module.execute(command))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(100))
                .then(() -> historyBarrier.tryEmitEmpty())
                .expectNoEvent(Duration.ofMillis(100))
                .then(() -> memoryBarrier.tryEmitEmpty())
                .assertNext(result -> assertEquals("answer", result.answer()))
                .verifyComplete();
    }

    @Test
    void mapsStructuredAnswerFailureToSourceValidationFailure() {
        GroundedTurnModule.Command command = command(List.of(candidate("ev-1", "doc-1", "first.pdf")));
        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatParentContextsWithStats(anyList()))
                .thenReturn(new ContextFormatter.FormattedContext("parent context", false));
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.callSourcedAnswer(same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList()))
                .thenReturn(Mono.error(new StructuredAnswerException("invalid JSON")));

        StepVerifier.create(module.execute(command))
                .expectErrorMatches(error -> error instanceof SourceValidationException validationError
                        && "json_parse_failed".equals(validationError.getReason()))
                .verify();
    }

    @Test
    void doesNotMapUnrelatedRuntimeFailureToSourceValidationFailure() {
        GroundedTurnModule.Command command = command(List.of(candidate("ev-1", "doc-1", "first.pdf")));
        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatParentContextsWithStats(anyList()))
                .thenReturn(new ContextFormatter.FormattedContext("parent context", false));
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        RuntimeException failure = new RuntimeException("upstream timeout");
        when(strategy.callSourcedAnswer(same(reactiveChatGateway), eq("parent context"), eq("question"),
                eq("conversation-1"), anyList()))
                .thenReturn(Mono.error(failure));

        StepVerifier.create(module.execute(command))
                .expectErrorSatisfies(error -> assertEquals(failure, error))
                .verify();
    }

    @Test
    void validatesSourcesBeforeExposingAnswerChunksAndPersistsAfterCompletion() {
        Document candidate = candidate("ev-1", "doc-1", "first.pdf");
        GroundedTurnModule.Command command = command(List.of(candidate));
        ParentContextBlock parent = new ParentContextBlock(
                "parent-1", "doc-1", "first.pdf", "parent context",
                1, 1, 1, List.of("ev-1"), 1);
        command = new GroundedTurnModule.Command(
                command.userInput(), command.conversationId(), command.userId(), command.modelId(),
                command.mode(), command.msgId(), command.traceId(), command.candidateEvidence(), List.of(parent));

        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatCandidateEvidence(anyList())).thenReturn("child context");
        when(contextFormatter.formatParentContextsWithStats(anyList()))
                .thenReturn(new ContextFormatter.FormattedContext("selected parent", false));
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.supportsValidatedAnswerStreaming()).thenReturn(true);
        when(strategy.callSourcePlan(same(reactiveChatGateway), eq("child context"), eq("question"),
                eq("conversation-1"), anyList()))
                .thenReturn(Mono.just(new SourcePlanResult("factual", List.of("ev-1"))));
        when(strategy.streamGroundedAnswer(same(reactiveChatGateway), eq("selected parent"), eq("question"),
                eq("conversation-1"), anyList()))
                .thenReturn(Flux.just("第一段", "第二段"));
        when(chatHistoryService.saveTurn(
                "conversation-1", "user-1", "question", "第一段第二段", "model-1", "agent", "msg-1"))
                .thenReturn(Mono.empty());

        GroundedTurnModule.StreamResult stream = module.stream(command).block();
        verify(chatMemory, never()).add(eq("conversation-1"), anyList());
        List<String> chunks = stream.answerFlux().collectList().block();

        assertEquals(List.of("第一段", "第二段"), chunks);
        verify(chatMemory, timeout(1000)).add(eq("conversation-1"), anyList());
        verify(chatHistoryService, timeout(1000)).saveTurn(
                "conversation-1", "user-1", "question", "第一段第二段", "model-1", "agent", "msg-1");
    }

    @Test
    void refusalSourcePlanSkipsAnswerModelAndPersistsFixedRefusal() {
        GroundedTurnModule.Command command = command(List.of(candidate("ev-1", "doc-1", "first.pdf")));
        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatCandidateEvidence(anyList())).thenReturn("child context");
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.supportsValidatedAnswerStreaming()).thenReturn(true);
        when(strategy.callSourcePlan(any(), eq("child context"), eq("question"), eq("conversation-1"), anyList()))
                .thenReturn(Mono.just(new SourcePlanResult("refusal", List.of())));
        when(chatHistoryService.saveTurn(
                eq("conversation-1"), eq("user-1"), eq("question"),
                eq(UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE), eq("model-1"), eq("agent"), eq("msg-1")))
                .thenReturn(Mono.empty());

        GroundedTurnModule.StreamResult stream = module.stream(command).block();
        assertEquals(List.of(UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE),
                stream.answerFlux().collectList().block());
        verify(strategy, never()).streamGroundedAnswer(any(), any(), any(), any(), anyList());
    }

    @Test
    void streamErrorAfterFirstChunkDoesNotPersist() {
        Document candidate = candidate("ev-1", "doc-1", "first.pdf");
        ParentContextBlock parent = new ParentContextBlock(
                "parent-1", "doc-1", "first.pdf", "parent context",
                1, 1, 1, List.of("ev-1"), 1);
        GroundedTurnModule.Command command = new GroundedTurnModule.Command(
                "question", "conversation-1", "user-1", "model-1", "agent", "msg-1", "trace-1",
                List.of(candidate), List.of(parent));
        when(chatMemory.get("conversation-1")).thenReturn(List.of());
        when(contextFormatter.formatCandidateEvidence(anyList())).thenReturn("child context");
        when(contextFormatter.formatParentContextsWithStats(anyList()))
                .thenReturn(new ContextFormatter.FormattedContext("selected parent", false));
        when(strategyFactory.getStrategy("model-1")).thenReturn(strategy);
        when(strategy.supportsValidatedAnswerStreaming()).thenReturn(true);
        when(strategy.callSourcePlan(any(), eq("child context"), eq("question"), eq("conversation-1"), anyList()))
                .thenReturn(Mono.just(new SourcePlanResult("factual", List.of("ev-1"))));
        when(strategy.streamGroundedAnswer(any(), eq("selected parent"), eq("question"), eq("conversation-1"), anyList()))
                .thenReturn(Flux.concat(Flux.just("第一段"), Flux.error(new IllegalStateException("stream failed"))));

        GroundedTurnModule.StreamResult stream = module.stream(command).block();
        StepVerifier.create(stream.answerFlux())
                .expectNext("第一段")
                .expectErrorMessage("stream failed")
                .verify();
        verify(chatMemory, never()).add(eq("conversation-1"), anyList());
        verify(chatHistoryService, never()).saveTurn(any(), any(), any(), any(), any(), any(), any());
    }

    private GroundedTurnModule.Command command(List<Document> candidates) {
        return new GroundedTurnModule.Command(
                "question",
                "conversation-1",
                "user-1",
                "model-1",
                "agent",
                "msg-1",
                "trace-1",
                candidates,
                List.of());
    }

    private Document candidate(String evidenceId, String docUuid, String fileName) {
        return new Document("content", Map.of(
                "evidence_id", evidenceId,
                "doc_uuid", docUuid,
                "file_name", fileName,
                "page_start", 1,
                "page_end", 1));
    }
}
