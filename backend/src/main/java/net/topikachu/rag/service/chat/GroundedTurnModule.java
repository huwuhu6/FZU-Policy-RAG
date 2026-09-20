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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
}
