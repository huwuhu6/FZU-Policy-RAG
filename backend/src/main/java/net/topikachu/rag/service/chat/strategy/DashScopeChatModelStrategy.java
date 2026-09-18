package net.topikachu.rag.service.chat.strategy;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "dashscope")
public class DashScopeChatModelStrategy implements ChatModelStrategy {

    private final ChatClient chatClient;

    public DashScopeChatModelStrategy(@Qualifier("dashScopeChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public String getModelId() {
        return "dashscope";
    }

    @Override
    public ChatClient getChatClient() {
        return this.chatClient;
    }
}
