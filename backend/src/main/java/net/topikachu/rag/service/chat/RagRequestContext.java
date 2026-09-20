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

    private static final int MAX_LOG_TEXT_CHARS = 1000;

    public static RagRequestContext create(String traceId,
                                           String conversationId,
                                           String msgId,
                                           String modelId) {
        return new RagRequestContext(traceId, conversationId, msgId, modelId, System.nanoTime());
    }

    public long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Keeps request text readable in one log line without emitting common
     * credential-shaped values or unbounded user input.
     */
    public static String logText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        String redacted = flattened.replaceAll(
                "(?i)(api[_-]?key|token|password|secret|authorization)\\s*[:=]\\s*[^,;\\s]+",
                "$1=<redacted>");
        if (redacted.length() <= MAX_LOG_TEXT_CHARS) {
            return redacted;
        }
        return redacted.substring(0, MAX_LOG_TEXT_CHARS) + "…";
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
