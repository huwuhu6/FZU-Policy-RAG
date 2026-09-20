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
 * Routes conversational input before FAQ matching and normal retrieval.
 */
@Component
@Slf4j
public class QueryPreProcessor {

    private static final String EXACT_REPLY =
            "同学你好！我是福州大学教务问答助手，可以向我咨询选课、转专业、缓考、推免或培养方案等相关事宜。";

    private static final Set<String> EXACT_CHIT_CHAT_INPUTS = Set.of(
            "你好", "您好", "在吗", "在不在", "谢谢", "感谢",
            "你是", "你是谁", "你是谁啊", "你是谁呀", "你是什么", "你是什么啊",
            "你能做什么", "你能做什么啊", "你能干什么", "你能干什么啊");

    private final ChatMemory chatMemory;
    private final ConversationIntentRouter conversationIntentRouter;
    private final TracingSupport tracingSupport;
    private final FaqSemanticMatcher faqSemanticMatcher;
    private final ChatModelStrategyFactory strategyFactory;
    private final ReactiveChatGateway reactiveChatGateway;

    private static final String REWRITE_PROMPT = """
            你是一个检索查询重写助手。请结合上下文，仅使用当前输入和历史中明确出现的信息消解指代、省略和上下文依赖；不得新增任何未经提及的学院、年份、身份、政策名称或条件。若当前提问本身已独立完整，直接原样返回。严禁回答问题，仅输出改写后的 Query。

            历史对话：
            {history}
            """;

    public QueryPreProcessor(ChatMemory chatMemory,
                             ConversationIntentRouter conversationIntentRouter,
                             TracingSupport tracingSupport,
                             FaqSemanticMatcher faqSemanticMatcher,
                             ChatModelStrategyFactory strategyFactory,
                             ReactiveChatGateway reactiveChatGateway) {
        this.chatMemory = chatMemory;
        this.conversationIntentRouter = conversationIntentRouter;
        this.tracingSupport = tracingSupport;
        this.faqSemanticMatcher = faqSemanticMatcher;
        this.strategyFactory = strategyFactory;
        this.reactiveChatGateway = reactiveChatGateway;
    }

    public Mono<ProcessResult> process(String userInput, String conversationId, String modelId) {
        return process(userInput, conversationId, modelId,
                RagRequestContext.create(tracingSupport.getCurrentTraceId(), conversationId, "", modelId));
    }

    public Mono<ProcessResult> process(String userInput,
                                       String conversationId,
                                       String modelId,
                                       RagRequestContext context) {
        String normalizedInput = normalizeInput(userInput);
        RagRequestContext requestContext = context == null
                ? RagRequestContext.create(tracingSupport.getCurrentTraceId(), conversationId, "", modelId)
                : context;
        long startNanos = System.nanoTime();

        Mono<ProcessResult> process = normalizedInput.isEmpty()
                || EXACT_CHIT_CHAT_INPUTS.contains(normalizedInput)
                ? Mono.just(ProcessResult.exact(EXACT_REPLY, normalizedInput))
                : loadRecentHistory(conversationId)
                .flatMap(history -> routeAndMatch(
                        normalizedInput, conversationId, modelId, requestContext, history));

        return tracingSupport.traceMono("rag.preprocess", requestContext.traceTags(), process)
                .doOnNext(result -> logRoute(result, requestContext, startNanos, normalizedInput));
    }

    /**
     * Deterministically removes only trailing equivalent tone punctuation.
     * It intentionally does not lowercase, collapse substrings, or remove
     * punctuation in the middle of a question.
     */
    public static String normalizeInput(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().replaceAll("[？?！!。\\.～~]+$", "").trim();
    }

    private Mono<ProcessResult> routeAndMatch(String normalizedInput,
                                                String conversationId,
                                                String modelId,
                                                RagRequestContext context,
                                                List<Message> history) {
        return conversationIntentRouter.route(normalizedInput, history, modelId, context)
                .flatMap(route -> switch (route.route()) {
                    case SMALLTALK -> Mono.just(ProcessResult.smalltalk(
                            route.directReply(), normalizedInput, history.size()));
                    case CLARIFY -> Mono.just(ProcessResult.clarify(
                            route.directReply(), normalizedInput, history.size()));
                    case RETRIEVE -> matchFaqOrRetrieve(
                            route.searchTargetQuery(), normalizedInput, history.size(), context);
                })
                .onErrorResume(error -> {
                    log.warn("[RAG] semantic-route fallback=true conversationId={} modelId={} errorType={}",
                            conversationId, modelId, error.getClass().getSimpleName());
                    tracingSupport.tagCurrent(Map.of(
                            "rag.semantic_route.fallback", true,
                            "rag.semantic_route.error_type", error.getClass().getSimpleName()));
                    return legacyFaqOrRewrite(normalizedInput, modelId, context, history);
                });
    }

    private Mono<ProcessResult> matchFaqOrRetrieve(String searchTargetQuery,
                                                     String originalQuery,
                                                     int historyMessages,
                                                     RagRequestContext context) {
        String query = searchTargetQuery == null || searchTargetQuery.isBlank()
                ? originalQuery
                : normalizeInput(searchTargetQuery);
        return faqSemanticMatcher.match(query, context)
                .flatMap(match -> match.matched()
                        ? Mono.just(ProcessResult.faq(match.faqId(), match.answer(), match.similarity()))
                        : Mono.just(ProcessResult.retrieve(
                                query,
                                !query.equals(originalQuery),
                                false,
                                historyMessages)));
    }

    private Mono<ProcessResult> legacyFaqOrRewrite(String normalizedInput,
                                                     String modelId,
                                                     RagRequestContext context,
                                                     List<Message> history) {
        if (isContextDependent(normalizedInput)) {
            return rewriteOrOriginal(normalizedInput, modelId, history);
        }
        return faqSemanticMatcher.match(normalizedInput, context)
                .flatMap(match -> match.matched()
                        ? Mono.just(ProcessResult.faq(match.faqId(), match.answer(), match.similarity()))
                        : rewriteOrOriginal(normalizedInput, modelId, history));
    }

    private Mono<ProcessResult> rewriteOrOriginal(String normalizedInput,
                                                   String modelId,
                                                   List<Message> history) {
        if (history.isEmpty()) {
            return Mono.just(ProcessResult.retrieve(
                    normalizedInput, false, true, history.size()));
        }
        return Mono.defer(() -> reactiveChatGateway.callBufferedStream(
                        strategyFactory.getStrategy(modelId).getChatClient(),
                        REWRITE_PROMPT,
                        Map.of("history", renderHistory(history)),
                        normalizedInput))
                .map(rewritten -> normalizeInput(rewritten).isBlank()
                        ? normalizedInput
                        : normalizeInput(rewritten))
                .map(rewritten -> ProcessResult.retrieve(
                        rewritten, !rewritten.equals(normalizedInput), true, history.size()))
                .onErrorResume(error -> {
                    log.warn("[RAG] query-rewrite fallback=true errorType={}",
                            error.getClass().getSimpleName());
                    return Mono.just(ProcessResult.retrieve(
                            normalizedInput, false, true, history.size()));
                });
    }

    private String renderHistory(List<Message> history) {
        return history.stream()
                .map(message -> {
                    if (message instanceof UserMessage) {
                        return "用户：" + message.getText();
                    }
                    if (message instanceof AssistantMessage) {
                        return "助手：" + message.getText();
                    }
                    return message.getText();
                })
                .collect(Collectors.joining("\n"));
    }

    private void logRoute(ProcessResult result,
                          RagRequestContext context,
                          long startNanos,
                          String originalQuery) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        switch (result.route()) {
            case EXACT -> log.info("[RAG] semantic-route route=EXACT traceId={} conversationId={} msgId={} input={} elapsedMs={}",
                    context.traceId(), context.conversationId(), context.msgId(),
                    RagRequestContext.logText(originalQuery), elapsedMs);
            case SMALLTALK, CLARIFY -> log.info(
                    "[RAG] semantic-route route={} traceId={} conversationId={} msgId={} input={} historyMessages={} elapsedMs={}",
                    result.route(), context.traceId(), context.conversationId(), context.msgId(),
                    RagRequestContext.logText(originalQuery), result.historyMessages(), elapsedMs);
            case FAQ -> log.info("[RAG] faq-match hit=true traceId={} conversationId={} msgId={} input={} faqId={} top1Score={} elapsedMs={}",
                    context.traceId(), context.conversationId(), context.msgId(),
                    RagRequestContext.logText(originalQuery), result.faqId(), result.faqSimilarity(), elapsedMs);
            case RETRIEVE -> log.info(
                    "[RAG] semantic-route route=RAG traceId={} conversationId={} msgId={} input={} searchQuery={} historyMessages={} queryChanged={} fallback={} elapsedMs={}",
                    context.traceId(), context.conversationId(), context.msgId(),
                    RagRequestContext.logText(originalQuery), RagRequestContext.logText(result.searchTargetQuery()),
                    result.historyMessages(), result.queryChanged(), result.fallback(), elapsedMs);
        }
    }

    private boolean isContextDependent(String input) {
        String normalized = normalizeInput(input);
        Set<String> markers = Set.of("那", "这个", "它", "上述", "刚才", "具体呢", "条件呢", "什么时候呢");
        return markers.stream().anyMatch(marker ->
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

    public enum PreprocessRoute {
        EXACT,
        SMALLTALK,
        CLARIFY,
        FAQ,
        RETRIEVE
    }

    public record ProcessResult(
            PreprocessRoute route,
            String directReply,
            String searchTargetQuery,
            String faqId,
            Double faqSimilarity,
            boolean fallback,
            int historyMessages,
            boolean queryChanged) {

        public ProcessResult(boolean isChitChat, String directReply, String searchTargetQuery) {
            this(isChitChat ? PreprocessRoute.EXACT : PreprocessRoute.RETRIEVE,
                    directReply, searchTargetQuery, null, null, false, 0, false);
        }

        public static ProcessResult exact(String answer, String query) {
            return new ProcessResult(PreprocessRoute.EXACT, answer, query, null, null, false, 0, false);
        }

        public static ProcessResult smalltalk(String answer, String query, int historyMessages) {
            return new ProcessResult(PreprocessRoute.SMALLTALK, answer, query, null, null,
                    false, historyMessages, false);
        }

        public static ProcessResult clarify(String answer, String query, int historyMessages) {
            return new ProcessResult(PreprocessRoute.CLARIFY, answer, query, null, null,
                    false, historyMessages, false);
        }

        public static ProcessResult faq(String faqId, String answer, Double similarity) {
            return new ProcessResult(PreprocessRoute.FAQ, answer, null, faqId, similarity,
                    false, 0, false);
        }

        public static ProcessResult retrieve(String query,
                                              boolean queryChanged,
                                              boolean fallback,
                                              int historyMessages) {
            return new ProcessResult(PreprocessRoute.RETRIEVE, null, query, null, null,
                    fallback, historyMessages, queryChanged);
        }

        public boolean isChitChat() {
            return route == PreprocessRoute.EXACT || route == PreprocessRoute.SMALLTALK;
        }

        public boolean isFaq() {
            return route == PreprocessRoute.FAQ;
        }
    }
}
