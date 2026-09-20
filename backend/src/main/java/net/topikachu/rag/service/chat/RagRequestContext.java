package net.topikachu.rag.service.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable, low-cardinality request identifiers shared by RAG stage logs.
 */
public record RagRequestContext(
        String traceId,
        String conversationId,
        String msgId,
        String modelId,
        long startNanos) {

    public static RagRequestContext create(String traceId,
                                           String conversationId,
                                           String msgId,
                                           String modelId) {
        return new RagRequestContext(traceId, conversationId, msgId, modelId, System.nanoTime());
    }

    public long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public Map<String, Object> traceTags() {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("rag.trace_id", value(traceId));
        tags.put("rag.conversation_id", value(conversationId));
        tags.put("rag.msg_id", value(msgId));
        tags.put("rag.model_id", value(modelId));
        return tags;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
