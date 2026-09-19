package net.topikachu.rag.service.chat;

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
public class QueryPreProcessor {

    private static final String DIRECT_REPLY =
            "同学你好！我是福州大学教务问答助手，可以向我咨询选课、转专业、缓考、推免或培养方案等相关事宜。";

    private static final Set<String> CHIT_CHAT_INPUTS = Set.of(
            "你好", "您好", "在吗", "在不在", "谢谢", "感谢", "你是谁", "你能做什么");

    private static final String REWRITE_PROMPT = """
            你是一个检索查询重写助手。请结合上下文，仅使用当前输入和历史中明确出现的信息消解指代、省略和上下文依赖；不得新增任何未经提及的学院、年份、身份、政策名称或条件。若当前提问本身已独立完整，直接原样返回。严禁回答问题，仅输出改写后的 Query。

            历史消息：
            {history}
            """;

    private final ChatMemory chatMemory;
    private final ChatModelStrategyFactory strategyFactory;
    private final ReactiveChatGateway reactiveChatGateway;

    public QueryPreProcessor(ChatMemory chatMemory,
                             ChatModelStrategyFactory strategyFactory,
                             ReactiveChatGateway reactiveChatGateway) {
        this.chatMemory = chatMemory;
        this.strategyFactory = strategyFactory;
        this.reactiveChatGateway = reactiveChatGateway;
    }

    public Mono<ProcessResult> process(String userInput, String conversationId, String modelId) {
        String normalizedInput = userInput == null ? "" : userInput.trim();
        if (normalizedInput.isEmpty() || CHIT_CHAT_INPUTS.contains(normalizedInput)) {
            return Mono.just(new ProcessResult(true, DIRECT_REPLY, normalizedInput));
        }

        return loadRecentHistory(conversationId)
                .flatMap(history -> {
                    if (history.isEmpty()) {
                        return Mono.just(new ProcessResult(false, null, normalizedInput));
                    }
                    String renderedHistory = renderHistory(history);
                    return reactiveChatGateway.call(
                                    strategyFactory.getStrategy(modelId).getChatClient(),
                                    REWRITE_PROMPT,
                                    Map.of("history", renderedHistory),
                                    normalizedInput)
                            .map(rewrittenQuery -> new ProcessResult(
                                    false,
                                    null,
                                    normalizeRewrite(rewrittenQuery, normalizedInput)));
                });
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

    public record ProcessResult(boolean isChitChat, String directReply, String searchTargetQuery) {
    }
}
