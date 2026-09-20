package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Performs the cheap conversational pre-processing that must happen before retrieval.
 */
@Component
@Slf4j
public class QueryPreProcessor {

    private static final String DIRECT_REPLY =
            "同学你好！我是福州大学教务问答助手，可以向我咨询选课、转专业、缓考、推免或培养方案等相关事宜。";

    private static final Set<String> CHIT_CHAT_INPUTS = Set.of(
            "你好", "您好", "在吗", "在不在", "谢谢", "感谢", "你是谁", "你能做什么");

    private static final Set<String> CONTEXT_DEPENDENCY_MARKERS = Set.of(
            "那", "这个", "它", "上述", "刚才", "具体呢", "条件呢", "什么时候呢");

    private static final String REWRITE_PROMPT = """
            你是一个检索查询重写助手。请结合上下文，仅使用当前输入和历史中明确出现的信息消解指代、省略和上下文依赖；不得新增任何未经提及的学院、年份、身份、政策名称或条件。若当前提问本身已独立完整，直接原样返回。严禁回答问题，仅输出改写后的 Query。

            历史消息：
            {history}
            """;

    private final ChatMemory chatMemory;
    private final ChatModelStrategyFactory strategyFactory;
    private final ReactiveChatGateway reactiveChatGateway;
    private final TracingSupport tracingSupport;
    private final FaqSemanticMatcher faqSemanticMatcher;

    public QueryPreProcessor(ChatMemory chatMemory,
                             ChatModelStrategyFactory strategyFactory,
                             ReactiveChatGateway reactiveChatGateway,
                             TracingSupport tracingSupport,
                             FaqSemanticMatcher faqSemanticMatcher) {
        this.chatMemory = chatMemory;
        this.strategyFactory = strategyFactory;
        this.reactiveChatGateway = reactiveChatGateway;
        this.tracingSupport = tracingSupport;
        this.faqSemanticMatcher = faqSemanticMatcher;
    }

    public Mono<ProcessResult> process(String userInput, String conversationId, String modelId) {
        return process(userInput, conversationId, modelId,
                RagRequestContext.create(tracingSupport.getCurrentTraceId(), conversationId, "", modelId));
    }

    public Mono<ProcessResult> process(String userInput,
                                       String conversationId,
                                       String modelId,
                                       RagRequestContext context) {
        String normalizedInput = userInput == null ? "" : userInput.trim();
        RagRequestContext requestContext = context == null
                ? RagRequestContext.create(tracingSupport.getCurrentTraceId(), conversationId, "", modelId)
                : context;
        long startNanos = System.nanoTime();

        Mono<ProcessResult> process = normalizedInput.isEmpty() || CHIT_CHAT_INPUTS.contains(normalizedInput)
                ? Mono.just(ProcessResult.chitChat(DIRECT_REPLY, normalizedInput))
                : processFaqOrHistory(normalizedInput, conversationId, modelId, requestContext);

        return tracingSupport.traceMono("rag.preprocess", requestContext.traceTags(), process)
                .doOnNext(result -> logRoute(result, requestContext, startNanos));
    }

    private Mono<ProcessResult> processFaqOrHistory(String normalizedInput,
                                                     String conversationId,
                                                     String modelId,
                                                     RagRequestContext context) {
        if (isContextDependent(normalizedInput)) {
            return rewriteOrOriginal(normalizedInput, conversationId, modelId, context);
        }
        return faqSemanticMatcher.match(normalizedInput, context)
                .flatMap(match -> match.matched()
                        ? Mono.just(ProcessResult.faq(match.faqId(), match.answer(), match.similarity()))
                        : rewriteOrOriginal(normalizedInput, conversationId, modelId, context));
    }

    private Mono<ProcessResult> rewriteOrOriginal(String normalizedInput,
                                                   String conversationId,
                                                   String modelId,
                                                   RagRequestContext context) {
        return loadRecentHistory(conversationId)
                .flatMap(history -> {
                    if (history.isEmpty()) {
                        return Mono.just(ProcessResult.original(normalizedInput));
                    }
                    String renderedHistory = renderHistory(history);
                    Mono<ProcessResult> rewrite = Mono.defer(() -> reactiveChatGateway.callBufferedStream(
                                    strategyFactory.getStrategy(modelId).getChatClient(),
                                    REWRITE_PROMPT,
                                    Map.of("history", renderedHistory),
                                    normalizedInput))
                            .map(rewrittenQuery -> {
                                String normalizedRewrite = normalizeRewrite(rewrittenQuery, normalizedInput);
                                return ProcessResult.rewrite(normalizedRewrite,
                                        normalizedRewrite.equals(normalizedInput), history.size());
                            })
                            .onErrorResume(error -> {
                                log.warn("[RAG] preprocess route=REWRITE fallback=true conversationId={} modelId={} errorType={}",
                                        conversationId,
                                        modelId,
                                        error.getClass().getSimpleName());
                                tracingSupport.tagCurrent(Map.of(
                                        "rag.query_rewrite.fallback", true,
                                        "rag.query_rewrite.error_type", error.getClass().getSimpleName()));
                                return Mono.just(ProcessResult.rewrite(normalizedInput, true, history.size()));
                            });
                    return tracingSupport.traceMono(
                            "rag.query_rewrite",
                            Map.of(
                                    "rag.query_rewrite.history_messages", history.size(),
                                    "rag.query_rewrite.model_id", modelId == null ? "" : modelId),
                            rewrite);
                });
    }

    private void logRoute(ProcessResult result, RagRequestContext context, long startNanos) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        switch (result.route()) {
            case CHITCHAT -> log.info("[RAG] preprocess route=CHITCHAT traceId={} conversationId={} msgId={} elapsedMs={}",
                    context.traceId(), context.conversationId(), context.msgId(), elapsedMs);
            case FAQ -> log.info("[RAG] preprocess route=FAQ traceId={} conversationId={} msgId={} faqId={} similarity={} elapsedMs={}",
                    context.traceId(), context.conversationId(), context.msgId(), result.faqId(), result.faqSimilarity(), elapsedMs);
            case ORIGINAL -> log.info("[RAG] preprocess route=ORIGINAL traceId={} conversationId={} msgId={} historyMessages={} elapsedMs={}",
                    context.traceId(), context.conversationId(), context.msgId(), result.historyMessages(), elapsedMs);
            case REWRITE -> log.info("[RAG] preprocess route=REWRITE traceId={} conversationId={} msgId={} historyMessages={} queryChanged={} fallback={} elapsedMs={}",
                    context.traceId(), context.conversationId(), context.msgId(),
                    result.historyMessages(), !result.fallback(), result.fallback(), elapsedMs);
        }
    }

    private boolean isContextDependent(String input) {
        String normalized = input.replaceAll("[\\s，。！？；：、,.!?;:]+$", "");
        return CONTEXT_DEPENDENCY_MARKERS.stream().anyMatch(marker ->
                normalized.equals(marker) || normalized.startsWith(marker) || normalized.endsWith(marker));
    }

    private Mono<List<Message>> loadRecentHistory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Mono.just(List.of());
        }
        return Mono.fromCallable(() -> {
                    List<Message> messages = chatMemory.get(conversationId);
                    if (messages == null || messages.isEmpty()) {
                        return List.<Message>of();
                    }
                    List<Message> conversationalMessages = messages.stream()
                            .filter(message -> message instanceof UserMessage || message instanceof AssistantMessage)
                            .toList();
                    int fromIndex = Math.max(0, conversationalMessages.size() - 4);
                    return List.copyOf(conversationalMessages.subList(fromIndex, conversationalMessages.size()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String renderHistory(List<Message> history) {
        return history.stream()
                .map(message -> (message instanceof UserMessage ? "用户" : "助手") + "：" + message.getText())
                .collect(Collectors.joining("\n"));
    }

    private String normalizeRewrite(String rewrittenQuery, String fallback) {
        if (rewrittenQuery == null || rewrittenQuery.isBlank()) {
            return fallback;
        }
        return rewrittenQuery.trim();
    }

    public enum PreprocessRoute {
        CHITCHAT,
        FAQ,
        ORIGINAL,
        REWRITE
    }

    public record ProcessResult(
            PreprocessRoute route,
            String directReply,
            String searchTargetQuery,
            String faqId,
            Double faqSimilarity,
            boolean fallback,
            int historyMessages) {

        public ProcessResult(boolean isChitChat, String directReply, String searchTargetQuery) {
            this(isChitChat ? PreprocessRoute.CHITCHAT : PreprocessRoute.ORIGINAL,
                    directReply, searchTargetQuery, null, null, false, 0);
        }

        public static ProcessResult chitChat(String answer, String query) {
            return new ProcessResult(PreprocessRoute.CHITCHAT, answer, query, null, null, false, 0);
        }

        public static ProcessResult faq(String faqId, String answer, Double similarity) {
            return new ProcessResult(PreprocessRoute.FAQ, answer, null, faqId, similarity, false, 0);
        }

        public static ProcessResult original(String query) {
            return new ProcessResult(PreprocessRoute.ORIGINAL, null, query, null, null, false, 0);
        }

        public static ProcessResult rewrite(String query, boolean fallback, int historyMessages) {
            return new ProcessResult(PreprocessRoute.REWRITE, null, query, null, null, fallback, historyMessages);
        }

        public boolean isChitChat() {
            return route == PreprocessRoute.CHITCHAT;
        }

        public boolean isFaq() {
            return route == PreprocessRoute.FAQ;
        }
    }
}
