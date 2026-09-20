package net.topikachu.rag.service.chat;

/**
 * Result of the semantic conversation router. A retrieve route carries the
 * query that is safe to send to FAQ matching and normal retrieval.
 */
public record ConversationRouteResult(
        Route route,
        String directReply,
        String searchTargetQuery) {

    public enum Route {
        SMALLTALK,
        CLARIFY,
        RETRIEVE
    }
}
