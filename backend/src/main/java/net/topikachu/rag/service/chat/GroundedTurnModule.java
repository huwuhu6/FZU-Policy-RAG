package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.chat.history.ChatHistoryService;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public final class GroundedTurnModule {

    private final ContextFormatter contextFormatter;
    private final ChatModelStrategyFactory strategyFactory;
    private final ReactiveChatGateway reactiveChatGateway;
    private final UsedSourceValidator usedSourceValidator;
    private final TracingSupport tracingSupport;
    private final ChatMemory chatMemory;
    private final ChatHistoryService chatHistoryService;

    public GroundedTurnModule(ContextFormatter contextFormatter,
                              ChatModelStrategyFactory strategyFactory,
                              ReactiveChatGateway reactiveChatGateway,
                              UsedSourceValidator usedSourceValidator,
                              TracingSupport tracingSupport,
                              ChatMemory chatMemory,
                              ChatHistoryService chatHistoryService) {
        this.contextFormatter = contextFormatter;
        this.strategyFactory = strategyFactory;
        this.reactiveChatGateway = reactiveChatGateway;
        this.usedSourceValidator = usedSourceValidator;
        this.tracingSupport = tracingSupport;
        this.chatMemory = chatMemory;
        this.chatHistoryService = chatHistoryService;
    }

    public Mono<Result> execute(Command command) {
        Objects.requireNonNull(command, "command must not be null");
        return loadHistory(command.conversationId())
                .flatMap(history -> {
                    long contextStart = System.nanoTime();
                    ContextFormatter.FormattedContext formattedContext = contextFormatter
                            .formatParentContextsWithStats(command.parentContexts());
                    String context = formattedContext.text();
                    log.info("[RAG] context traceId={} conversationId={} msgId={} children={} parents={} contextChars={} truncated={} elapsedMs={}",
                            command.traceId(), command.conversationId(), command.msgId(), command.candidateEvidence().size(),
                            command.parentContexts().size(), context.length(), formattedContext.truncated(),
                            elapsedMs(contextStart));
                    ChatModelStrategy strategy = strategyFactory.getStrategy(command.modelId());
                    long generationStart = System.nanoTime();
                    log.info("[RAG] generate traceId={} conversationId={} msgId={} model={} structured=json_schema transportStreaming=true clientStreaming=false historyMessages={} contextChars={}",
                            command.traceId(), command.conversationId(), command.msgId(), command.modelId(), history.size(),
                            context.length());
                    return strategy.callSourcedAnswer(
                            reactiveChatGateway,
                            context,
                            command.userInput(),
                            command.conversationId(),
                            history)
                            .doOnNext(answer -> log.info("[RAG] generate completed traceId={} conversationId={} msgId={} elapsedMs={}",
                                    command.traceId(), command.conversationId(), command.msgId(), elapsedMs(generationStart)))
                            .doOnError(error -> log.warn("[RAG] failed stage=generate traceId={} conversationId={} msgId={} errorType={} totalMs={}",
                                    command.traceId(), command.conversationId(), command.msgId(),
                                    error.getClass().getSimpleName(), elapsedMs(generationStart)));
                })
                .onErrorMap(this::toSourceValidationError)
                .flatMap(answer -> {
                    long validationStart = System.nanoTime();
                    Mono<Result> validation = Mono.fromCallable(() -> {
                        try {
                            List<UsedSource> usedSources = usedSourceValidator.validate(answer, command.candidateEvidence());
                            log.info("[RAG] validate traceId={} conversationId={} msgId={} answerType={} requestedSources={} validatedSources={} elapsedMs={}",
                                    command.traceId(), command.conversationId(), command.msgId(), answer.answerType(),
                                    answer.usedSources() == null ? 0 : answer.usedSources().size(), usedSources.size(),
                                    elapsedMs(validationStart));
                            return new Result(answer.answer(), answer.answerType(), usedSources);
                        } catch (SourceValidationException error) {
                            log.warn("[RAG] validate failed traceId={} conversationId={} msgId={} reason={}",
                                    command.traceId(), command.conversationId(), command.msgId(), error.getReason());
                            throw error;
                        }
                    });
                    return tracingSupport.traceMono("rag.source_validate", command.traceTags(), validation);
                })
                .flatMap(result -> commit(command, result).thenReturn(result));
    }

    /**
     * Two-phase generation for strategies that explicitly support validated
     * answer streaming. Phase A is fully buffered and validated; only then is
     * the Phase B answer Flux assembled. DeepSeek and other strategies retain
     * the original atomic execute() path.
     */
    public Mono<StreamResult> stream(Command command) {
        Objects.requireNonNull(command, "command must not be null");
        ChatModelStrategy strategy = strategyFactory.getStrategy(command.modelId());
        if (!strategy.supportsValidatedAnswerStreaming()) {
            return execute(command)
                    .map(result -> new StreamResult(
                            Flux.just(result.answer()), result.answerType(), result.usedSources()));
        }

        return loadHistory(command.conversationId())
                .flatMap(history -> {
                    long sourcePlanStart = System.nanoTime();
                    String candidateContext = contextFormatter.formatCandidateEvidence(command.candidateEvidence());
                    Mono<SourcePlanResult> sourcePlan = strategy.callSourcePlan(
                                    reactiveChatGateway,
                                    candidateContext,
                                    command.userInput(),
                                    command.conversationId(),
                                    history);
                    return tracingSupport.traceMono("rag.source_plan", command.traceTags(), sourcePlan)
                            .doOnNext(plan -> log.info(
                                    "[RAG] source-plan traceId={} conversationId={} msgId={} candidateEvidence={} answerType={} requestedSources={} elapsedMs={}",
                                    command.traceId(), command.conversationId(), command.msgId(),
                                    command.candidateEvidence().size(), plan.answerType(),
                                    plan.usedSources().size(), elapsedMs(sourcePlanStart)))
                            .doOnError(error -> log.warn(
                                    "[RAG] failed stage=source-plan traceId={} conversationId={} msgId={} errorType={} elapsedMs={}",
                                    command.traceId(), command.conversationId(), command.msgId(),
                                    error.getClass().getSimpleName(), elapsedMs(sourcePlanStart)))
                            .onErrorMap(this::toSourceValidationError)
                            .flatMap(plan -> validateSourcePlan(command, plan))
                            .map(validatedPlan -> new PreparedPlan(history, validatedPlan));
                })
                .flatMap(prepared -> {
                    UsedSourceValidator.ValidatedSourcePlan validatedPlan = prepared.plan();
                    List<Message> history = prepared.history();
                    if ("refusal".equalsIgnoreCase(validatedPlan.answerType())) {
                        String refusal = UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE;
                        Flux<String> refusalFlux = Flux.defer(() ->
                                Flux.just(refusal)
                                        .concatWith(commit(command, new Result(refusal, "refusal", List.of()))
                                                .then(Mono.<String>empty())));
                        return Mono.just(new StreamResult(refusalFlux, "refusal", List.of()));
                    }

                    List<ParentContextBlock> selectedParents = selectValidatedParents(
                            command.parentContexts(), validatedPlan.evidenceIds());
                    if (selectedParents.isEmpty()) {
                        return Mono.error(new SourceValidationException(
                                UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE,
                                UsedSourceValidator.REASON_PARENT_CONTEXT_MISSING));
                    }

                    ContextFormatter.FormattedContext formattedContext = contextFormatter
                            .formatParentContextsWithStats(selectedParents);
                    log.info("[RAG] stream-start traceId={} conversationId={} msgId={} parents={} contextChars={} truncated={} model={}",
                            command.traceId(), command.conversationId(), command.msgId(), selectedParents.size(),
                            formattedContext.text().length(), formattedContext.truncated(), command.modelId());

                    Flux<String> answerFlux = Flux.defer(() -> {
                        long generationStart = System.nanoTime();
                        StringBuilder answerBuffer = new StringBuilder();
                        AtomicBoolean firstChunkSeen = new AtomicBoolean();
                        AtomicInteger chunkCount = new AtomicInteger();
                        Flux<String> modelFlux = strategy.streamGroundedAnswer(
                                reactiveChatGateway,
                                formattedContext.text(),
                                command.userInput(),
                                command.conversationId(),
                                history)
                                .filter(chunk -> chunk != null && !chunk.isBlank())
                                .doOnNext(chunk -> {
                                    answerBuffer.append(chunk);
                                    int currentChunk = chunkCount.incrementAndGet();
                                    if (firstChunkSeen.compareAndSet(false, true)) {
                                        log.info("[RAG] first-chunk traceId={} conversationId={} msgId={} firstChunkMs={} chunk={}",
                                                command.traceId(), command.conversationId(), command.msgId(),
                                                elapsedMs(generationStart), currentChunk);
                                    }
                                });
                        return modelFlux
                                .concatWith(Mono.defer(() -> {
                                    if (answerBuffer.isEmpty()) {
                                        return Mono.error(new StructuredAnswerException(
                                                "Grounded answer stream completed without content."));
                                    }
                                    return commit(command, new Result(
                                                    answerBuffer.toString(),
                                                    "factual",
                                                    validatedPlan.usedSources()))
                                            .then(Mono.<String>empty());
                                }))
                                .doOnComplete(() -> log.info(
                                        "[RAG] stream-complete traceId={} conversationId={} msgId={} chunks={} answerChars={} generationMs={}",
                                        command.traceId(), command.conversationId(), command.msgId(),
                                        chunkCount.get(), answerBuffer.length(), elapsedMs(generationStart)));
                    });
                    return Mono.just(new StreamResult(
                            answerFlux, "factual", validatedPlan.usedSources()));
                });
    }

    private Mono<UsedSourceValidator.ValidatedSourcePlan> validateSourcePlan(
            Command command, SourcePlanResult plan) {
        long validationStart = System.nanoTime();
        Mono<UsedSourceValidator.ValidatedSourcePlan> validation = Mono.fromCallable(() -> {
                    try {
                        UsedSourceValidator.ValidatedSourcePlan validated =
                                usedSourceValidator.validateSourcePlan(plan, command.candidateEvidence());
                        log.info("[RAG] source-validate traceId={} conversationId={} msgId={} answerType={} requestedSources={} validatedSources={} elapsedMs={}",
                                command.traceId(), command.conversationId(), command.msgId(),
                                validated.answerType(), plan.usedSources().size(),
                                validated.evidenceIds().size(), elapsedMs(validationStart));
                        return validated;
                    } catch (SourceValidationException error) {
                        log.warn("[RAG] source-validate failed traceId={} conversationId={} msgId={} reason={}",
                                command.traceId(), command.conversationId(), command.msgId(), error.getReason());
                        throw error;
                    }
                });
        return tracingSupport.traceMono("rag.source_validate", command.traceTags(), validation);
    }

    private List<ParentContextBlock> selectValidatedParents(
            List<ParentContextBlock> parents, List<String> validatedEvidenceIds) {
        Set<String> allowed = new HashSet<>(validatedEvidenceIds == null ? List.of() : validatedEvidenceIds);
        return (parents == null ? List.<ParentContextBlock>of() : parents).stream()
                .map(parent -> {
                    List<String> selectedEvidenceIds = parent.evidenceIds() == null
                            ? List.of()
                            : parent.evidenceIds().stream()
                            .filter(allowed::contains)
                            .distinct()
                            .toList();
                    if (selectedEvidenceIds.isEmpty()) {
                        return null;
                    }
                    return new ParentContextBlock(
                            parent.parentBlockId(),
                            parent.docUuid(),
                            parent.fileName(),
                            parent.content(),
                            parent.parentIndex(),
                            parent.pageStart(),
                            parent.pageEnd(),
                            selectedEvidenceIds,
                            parent.rank());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public Mono<Void> commitDirectReply(Command command, String answer) {
        return commitDirectReply(command, answer, "chitchat");
    }

    public Mono<Void> commitDirectReply(Command command, String answer, String answerType) {
        Objects.requireNonNull(command, "command must not be null");
        return commit(command, new Result(answer, answerType, List.of()));
    }

    private Mono<List<Message>> loadHistory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Mono.just(List.of());
        }
        return Mono.fromCallable(() -> {
                    List<Message> history = chatMemory.get(conversationId);
                    if (history == null || history.isEmpty()) {
                        return List.<Message>of();
                    }
                    return history.stream()
                            .filter(message -> message instanceof UserMessage || message instanceof AssistantMessage)
                            .toList();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> commit(Command command, Result result) {
        Mono<Void> memoryCommit = Mono.fromRunnable(() -> chatMemory.add(command.conversationId(), List.of(
                        new UserMessage(command.userInput()),
                        new AssistantMessage(result.answer()))))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
        Mono<Void> historyCommit = chatHistoryService.saveTurn(
                command.conversationId(),
                command.userId(),
                command.userInput(),
                result.answer(),
                command.modelId(),
                command.mode(),
                command.msgId());
        // ponytail: completion barrier only; add compensation if partial cross-store writes become an observed problem.
        long persistStart = System.nanoTime();
        Mono<Void> persistence = Mono.when(memoryCommit, historyCommit)
                .doOnSuccess(ignored -> log.info("[RAG] persist traceId={} conversationId={} msgId={} memory=true history=true elapsedMs={}",
                        command.traceId(), command.conversationId(), command.msgId(), elapsedMs(persistStart)))
                .doOnError(error -> log.warn("[RAG] failed stage=persist traceId={} conversationId={} msgId={} errorType={} totalMs={}",
                        command.traceId(), command.conversationId(), command.msgId(),
                        error.getClass().getSimpleName(), elapsedMs(persistStart)));
        return tracingSupport.traceMono("rag.persist", command.traceTags(), persistence);
    }

    private Throwable toSourceValidationError(Throwable error) {
        if (error instanceof SourceValidationException) {
            return error;
        }
        if (error instanceof StructuredAnswerException) {
            log.warn("[RAG] structured grounded answer parse failed errorType={} causeType={}",
                    error.getClass().getSimpleName(),
                    error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName());
            return new SourceValidationException(
                    UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE,
                    "json_parse_failed");
        }
        return error;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public record Command(
            String userInput,
            String conversationId,
            String userId,
            String modelId,
            String mode,
            String msgId,
            String traceId,
            List<Document> candidateEvidence,
            List<ParentContextBlock> parentContexts) {

        public Command {
            candidateEvidence = candidateEvidence == null ? List.of() : List.copyOf(candidateEvidence);
            parentContexts = parentContexts == null ? List.of() : List.copyOf(parentContexts);
        }

        public Map<String, Object> traceTags() {
            Map<String, Object> tags = new LinkedHashMap<>();
            tags.put("rag.trace_id", traceId == null ? "" : traceId);
            tags.put("rag.conversation_id", conversationId == null ? "" : conversationId);
            tags.put("rag.msg_id", msgId == null ? "" : msgId);
            tags.put("rag.model_id", modelId == null ? "" : modelId);
            return tags;
        }
    }

    public record Result(String answer, String answerType, List<UsedSource> usedSources) {

        public Result {
            usedSources = usedSources == null ? List.of() : List.copyOf(usedSources);
        }
    }

    public record StreamResult(Flux<String> answerFlux, String answerType, List<UsedSource> usedSources) {
        public StreamResult {
            Objects.requireNonNull(answerFlux, "answerFlux must not be null");
            usedSources = usedSources == null ? List.of() : List.copyOf(usedSources);
        }
    }

    private record PreparedPlan(
            List<Message> history,
            UsedSourceValidator.ValidatedSourcePlan plan) {
    }
}
