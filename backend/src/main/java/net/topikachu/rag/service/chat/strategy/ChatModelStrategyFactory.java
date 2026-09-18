package net.topikachu.rag.service.chat.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ChatModelStrategyFactory {

    private final Map<String, ChatModelStrategy> strategyMap;

    @Autowired
    public ChatModelStrategyFactory(List<ChatModelStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(
                        // modelId 统一转小写：客户端传入的大小写不统一，不敏感匹配避免 404
                        strategy -> strategy.getModelId()
                                .toLowerCase(),
                        strategy -> strategy,
                        (oldVal, newVal)
                                -> newVal));
    }

    public ChatModelStrategy getStrategy(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            log.warn("modelId is null or blank, using default 'qwen' " +
                    "strategy");
            return requireStrategy("qwen");
        }

        ChatModelStrategy strategy = strategyMap.get(modelId.toLowerCase());
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown chat modelId: " + modelId);
        }

        return strategy;
    }

    private ChatModelStrategy requireStrategy(String modelId) {
        ChatModelStrategy strategy = strategyMap.get(modelId);
        if (strategy == null) {
            throw new IllegalStateException("Chat strategy is not configured: " + modelId);
        }
        return strategy;
    }
}
