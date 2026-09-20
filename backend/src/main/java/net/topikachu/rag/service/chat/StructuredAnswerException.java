package net.topikachu.rag.service.chat;

/**
 * Indicates that the strict grounded-answer JSON returned by the model is
 * structurally invalid.
 */
public class StructuredAnswerException extends RuntimeException {

    public StructuredAnswerException(String message) {
        super(message);
    }

    public StructuredAnswerException(String message, Throwable cause) {
        super(message, cause);
    }
}
