package net.topikachu.rag.service.chat;

import java.util.List;

/**
 * The strict, answer-free result of Phase A evidence planning.
 */
public record SourcePlanResult(
        String answerType,
        List<String> usedSources
) {
    public SourcePlanResult {
        usedSources = usedSources == null ? List.of() : List.copyOf(usedSources);
    }
}
