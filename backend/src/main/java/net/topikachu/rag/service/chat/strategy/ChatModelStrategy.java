package net.topikachu.rag.service.chat.strategy;

import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.SourcedAnswerPrompts;
import net.topikachu.rag.service.chat.SourcedAnswerResult;
import net.topikachu.rag.service.chat.SourcePlanResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public interface ChatModelStrategy {
    /**
     * 获取模型的唯一标识符，例如 "ollama" 或 "deepseek"
     */
    String getModelId();

    /**
     * 获取基于当前模型构建的 ChatClient 实例
     */
    ChatClient getChatClient();

    default boolean supportsValidatedAnswerStreaming() {
        return false;
    }

    default Mono<SourcePlanResult> callSourcePlan(ReactiveChatGateway reactiveChatGateway,
                                                   String context,
                                                   String userInput,
                                                   String conversationId,
                                                   List<Message> historyMessages) {
        return callSourcePlan(
                reactiveChatGateway,
                context,
                userInput,
                conversationId,
                historyMessages,
                userInput);
    }

    default Mono<SourcePlanResult> callSourcePlan(ReactiveChatGateway reactiveChatGateway,
                                                   String context,
                                                   String userInput,
                                                   String conversationId,
                                                   List<Message> historyMessages,
                                                   String searchTargetQuery) {
        return reactiveChatGateway.callBufferedSourcePlan(
                getChatClient(),
                net.topikachu.rag.service.chat.SourcedAnswerPrompts.sourcePlanPrompt(),
                Map.of("context", context,
                        "question", userInput,
                        "searchTargetQuery", searchTargetQuery),
                historyMessages,
                userInput,
                conversationId);
    }

    default Flux<String> streamGroundedAnswer(ReactiveChatGateway reactiveChatGateway,
                                               String context,
                                               String userInput,
                                               String conversationId,
                                               List<Message> historyMessages) {
        return streamGroundedAnswer(
                reactiveChatGateway,
                context,
                userInput,
                conversationId,
                historyMessages,
                userInput);
    }

    default Flux<String> streamGroundedAnswer(ReactiveChatGateway reactiveChatGateway,
                                               String context,
                                               String userInput,
                                               String conversationId,
                                               List<Message> historyMessages,
                                               String searchTargetQuery) {
        return reactiveChatGateway.stream(
                getChatClient(),
                net.topikachu.rag.service.chat.SourcedAnswerPrompts.answerPrompt(),
                Map.of("context", context,
                        "question", userInput,
                        "searchTargetQuery", searchTargetQuery),
                historyMessages,
                userInput,
                conversationId);
    }

    default Mono<SourcedAnswerResult> callSourcedAnswer(ReactiveChatGateway reactiveChatGateway,
                                                        String context,
                                                        String userInput,
                                                        String conversationId,
                                                        List<Message> historyMessages) {
        return reactiveChatGateway.callBufferedSourcedAnswer(
                getChatClient(),
                SourcedAnswerPrompts.jsonPrompt(),
                Map.of("context", context),
                historyMessages,
                userInput,
                conversationId);
    }
}
