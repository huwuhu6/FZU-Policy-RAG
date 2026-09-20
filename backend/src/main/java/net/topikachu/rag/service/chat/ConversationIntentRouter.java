package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Classifies non-exact inputs before FAQ matching and retrieval. It only
 * produces a route and, for retrieval, a grounded search query; it never
 * produces a policy answer.
 */
@Component
@Slf4j
public class ConversationIntentRouter {

    static final String SMALLTALK_FALLBACK_REPLY =
            "同学你好！我是福州大学教务问答助手，可以向我咨询选课、转专业、缓考、推免或培养方案等相关事宜。";
    static final String CLARIFY_REPLY =
            "你想问的是哪一部分？可以把具体政策、课程或问题再描述一下。";
    static final String TERMINATION_REPLY =
            "好的，如果后续有其他教务、转专业或选课问题，随时可以再问我。";

    private static final List<String> TERMINATION_MARKERS = List.of(
            "我不想问这个了", "我不想问了", "不想问了", "先不问了",
            "不问了", "算了", "不用了", "先这样", "再见", "拜拜");

    private final ChatModelStrategyFactory strategyFactory;
    private final ReactiveChatGateway reactiveChatGateway;
    private final TracingSupport tracingSupport;

    public ConversationIntentRouter(ChatModelStrategyFactory strategyFactory,
                                    ReactiveChatGateway reactiveChatGateway,
                                    TracingSupport tracingSupport) {
        this.strategyFactory = strategyFactory;
        this.reactiveChatGateway = reactiveChatGateway;
        this.tracingSupport = tracingSupport;
    }

    public Mono<ConversationRouteResult> route(String normalizedInput,
                                               List<Message> historyMessages,
                                               String modelId,
                                               RagRequestContext context) {
        List<Message> history = historyMessages == null ? List.of() : List.copyOf(historyMessages);
        Map<String, Object> params = Map.of(
                "question", normalizedInput,
                "history", renderHistory(history));
        Mono<ConversationRouteResult> routed;
        if (isTerminationIntent(normalizedInput)) {
            routed = Mono.just(new ConversationRouteResult(
                    ConversationRouteResult.Route.SMALLTALK,
                    TERMINATION_REPLY,
                    ""));
        } else if (isPlaceholderInput(normalizedInput)) {
            routed = Mono.just(new ConversationRouteResult(
                    ConversationRouteResult.Route.CLARIFY,
                    placeholderReply(normalizedInput),
                    ""));
        } else {
            routed = Mono.defer(() ->
                    reactiveChatGateway.callBufferedConversationRoute(
                            strategyFactory.getStrategy(modelId).getChatClient(),
                            SourcedAnswerPrompts.conversationRoutePrompt(),
                            params,
                            history,
                            normalizedInput,
                            context == null ? null : context.conversationId()));
        }

        return tracingSupport.traceMono("rag.semantic_route",
                        context == null ? Map.of() : context.traceTags(), routed)
                .map(result -> sanitize(result, normalizedInput));
    }

    private ConversationRouteResult sanitize(ConversationRouteResult result, String fallbackQuery) {
        if (result == null || result.route() == null) {
            throw new StructuredAnswerException("Conversation route is missing a route.");
        }
        String directReply = result.directReply() == null ? "" : result.directReply().trim();
        String searchTargetQuery = result.searchTargetQuery() == null
                ? ""
                : result.searchTargetQuery().trim();
        if (result.route() == ConversationRouteResult.Route.SMALLTALK) {
            if (isTerminationIntent(fallbackQuery)) {
                directReply = TERMINATION_REPLY;
            } else if (directReply.isBlank() || containsInternalRole(directReply)) {
                directReply = SMALLTALK_FALLBACK_REPLY;
            }
        }
        if (result.route() == ConversationRouteResult.Route.CLARIFY && directReply.isBlank()) {
            directReply = CLARIFY_REPLY;
        }
        if (result.route() == ConversationRouteResult.Route.RETRIEVE && searchTargetQuery.isBlank()) {
            searchTargetQuery = fallbackQuery;
        }
        return new ConversationRouteResult(result.route(), directReply, searchTargetQuery);
    }

    private boolean containsInternalRole(String directReply) {
        String normalized = directReply.toLowerCase(java.util.Locale.ROOT);
        return directReply.contains("路由器")
                || directReply.contains("路由组件")
                || directReply.contains("分类器")
                || normalized.contains("router")
                || normalized.contains("prompt");
    }

    private boolean isTerminationIntent(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim();
        return TERMINATION_MARKERS.stream().anyMatch(normalized::contains);
    }

    private boolean isPlaceholderInput(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim();
        boolean hasSpecificQuestion = normalized.matches(".*(什么|怎么|如何|能否|能不能|是否|条件|政策|规定|申请|要求|什么时候|去哪|哪里|多少|为什么).* ".trim());
        if (hasSpecificQuestion) {
            return false;
        }
        return normalized.contains("问你个事")
                || normalized.contains("问个事")
                || normalized.contains("问你一件事")
                || normalized.contains("帮我同学问一个东西")
                || normalized.equals("先等等")
                || normalized.equals("等一下")
                || normalized.equals("稍等一下");
    }

    private String placeholderReply(String question) {
        return question.contains("同学")
                ? "好的，请问你同学具体想咨询什么？"
                : "好的，请问你具体想咨询什么？";
    }

    private String renderHistory(List<Message> history) {
        return history.stream()
                .map(message -> switch (message.getMessageType()) {
                    case USER -> "用户：" + message.getText();
                    case ASSISTANT -> "助手：" + message.getText();
                    default -> message.getText();
                })
                .reduce((left, right) -> left + "\n" + right)
                .orElse("（无历史消息）");
    }
}
