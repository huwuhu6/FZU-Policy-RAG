package net.topikachu.rag.service.chat.strategy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class ChatModelStrategyFactoryTest {

    @Test
    void shouldResolveGeminiStrategyByModelId() {
        ChatModel mockModel = mock(ChatModel.class);

        ChatModelStrategy gemini = new GeminiChatModelStrategy(mockModel);

        ChatModelStrategyFactory factory = new ChatModelStrategyFactory(List.of(gemini));

        ChatModelStrategy resolved = factory.getStrategy("gemini");
        assertNotNull(resolved);
        assertEquals("gemini", resolved.getModelId());
    }

    @Test
    void shouldResolveQwenStrategyByModelId() {
        ChatModel mockModel = mock(ChatModel.class);

        ChatModelStrategy qwen = new QwenChatModelStrategy(mockModel);

        ChatModelStrategyFactory factory = new ChatModelStrategyFactory(List.of(qwen));

        assertEquals("qwen", factory.getStrategy("qwen").getModelId());
    }
}

