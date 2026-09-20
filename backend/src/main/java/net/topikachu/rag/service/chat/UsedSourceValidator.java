package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class UsedSourceValidator {

    public static final String UNRELIABLE_SOURCE_MESSAGE =
            "当前知识库为福州大学教务规章规程库，暂未收录该问题的相关条款或数据（如涉及行业就业前景、主观评价或具体学院未公开事项，建议咨询学院教学办、辅导员或关注教务处最新动态）。";
    public static final String REASON_ANSWER_MISSING = "answer_missing";
    public static final String REASON_USED_SOURCES_EMPTY = "used_sources_empty";
    public static final String REASON_FACTUAL_WITHOUT_SOURCES = "factual_without_sources";
    public static final String REASON_EVIDENCE_ID_MISSING = "evidence_id_missing";
    public static final String REASON_EVIDENCE_ID_NOT_IN_CANDIDATES = "evidence_id_not_in_candidates";
    public static final String REASON_INVALID_ANSWER_TYPE = "invalid_answer_type";
    public static final String REASON_REFUSAL_SOURCES_NOT_EMPTY = "refusal_sources_not_empty";
    public static final String REASON_PARENT_CONTEXT_MISSING = "validated_sources_not_in_parent_context";

    // 验证 LLM 回答中的引用：确保每个 usedSources 中的 evidence_id 都在候选文档中存在
    // 验证失败 → 抛异常，回答被拒绝，返回"无法可靠生成带溯源的答案"
    public List<UsedSource> validate(SourcedAnswerResult result, List<Document> candidates) {
        // 1. 回答必须有内容
        if (result == null || !StringUtils.hasText(result.answer())) {
            throw validationFailure(REASON_ANSWER_MISSING, null, candidates);
        }

        if ("factual".equalsIgnoreCase(result.answerType())
                && (result.usedSources() == null || result.usedSources().isEmpty())) {
            throw validationFailure(REASON_USED_SOURCES_EMPTY, result, candidates);
        }
        ValidatedSourcePlan validatedPlan = validateSourcePlan(
                new SourcePlanResult(result.answerType(), result.usedSources()), candidates);
        return validatedPlan.usedSources();
    }

    public ValidatedSourcePlan validateSourcePlan(SourcePlanResult plan, List<Document> candidates) {
        if (plan == null) {
            throw validationFailure(REASON_INVALID_ANSWER_TYPE, null, candidates);
        }
        boolean refusal = "refusal".equalsIgnoreCase(plan.answerType());
        boolean factual = "factual".equalsIgnoreCase(plan.answerType());
        if (!refusal && !factual) {
            throw validationFailure(REASON_INVALID_ANSWER_TYPE, plan, candidates);
        }
        List<String> requestedSources = plan.usedSources() == null ? List.of() : plan.usedSources();
        if (refusal) {
            if (!requestedSources.isEmpty()) {
                throw validationFailure(REASON_REFUSAL_SOURCES_NOT_EMPTY, plan, candidates);
            }
            return new ValidatedSourcePlan("refusal", List.of(), List.of());
        }
        if (requestedSources.isEmpty()) {
            log.warn("Used source validation downgraded factual source plan to refusal: reason={}, candidateCount={}",
                    REASON_FACTUAL_WITHOUT_SOURCES, candidates == null ? 0 : candidates.size());
            return new ValidatedSourcePlan("refusal", List.of(), List.of());
        }

        Map<String, Document> candidatesByEvidenceId = candidatesByEvidenceId(candidates);
        List<UsedSource> validated = new ArrayList<>();
        List<String> evidenceIds = new ArrayList<>();
        for (String requestedEvidenceId : requestedSources) {
            if (!StringUtils.hasText(requestedEvidenceId)) {
                throw validationFailure(REASON_EVIDENCE_ID_MISSING, plan, candidates);
            }
            String normalizedEvidenceId = requestedEvidenceId.trim();
            Document candidate = candidatesByEvidenceId.get(normalizedEvidenceId);
            if (candidate == null) {
                throw validationFailure(REASON_EVIDENCE_ID_NOT_IN_CANDIDATES, plan, candidates);
            }
            evidenceIds.add(normalizedEvidenceId);
            validated.add(fromDocument(candidate));
        }
        return new ValidatedSourcePlan(
                "factual",
                List.copyOf(evidenceIds),
                collapseDisplayedSources(validated));
    }

    private Map<String, Document> candidatesByEvidenceId(List<Document> candidates) {
        Map<String, Document> candidatesByEvidenceId = new LinkedHashMap<>();
        for (Document candidate : candidates == null ? List.<Document>of() : candidates) {
            String evidenceId = evidenceId(candidate);
            if (StringUtils.hasText(evidenceId)) {
                candidatesByEvidenceId.put(evidenceId, candidate);
            }
        }
        return candidatesByEvidenceId;
    }

    private UsedSource fromDocument(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        String rawFileName = stringValue(metadata.get("file_name"));
        return new UsedSource(
                evidenceId(document),
                stringValue(metadata.get("doc_uuid")),
                cleanFileName(rawFileName),
                pageNumber(metadata),
                fileType(rawFileName),
                sourceLocation(metadata));
    }

    // 同文档同位置的多个 evidence_id 只保留第一条（去重合并展示，避免溯源列表冗余）
    private List<UsedSource> collapseDisplayedSources(List<UsedSource> sources) {
        Map<String, UsedSource> unique = new LinkedHashMap<>();
        for (UsedSource source : sources) {
            if (source == null || !StringUtils.hasText(source.docUuid())) {
                continue;
            }
            String position = source.pageNumber() != null
                    ? source.pageNumber().toString()
                    : source.location() == null ? "" : source.location();
            String key = source.docUuid() + "|" + position;
            unique.putIfAbsent(key, source);
        }
        return List.copyOf(unique.values());
    }

    private String evidenceId(Document document) {
        if (document == null) {
            return null;
        }
        Object metadataEvidenceId = document.getMetadata().get("evidence_id");
        if (metadataEvidenceId != null && StringUtils.hasText(metadataEvidenceId.toString())) {
            return metadataEvidenceId.toString().trim();
        }
        return document.getId();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    // 页码只接受明确的数字字段，避免把 DOCX/MD 的章节路径误放进 page_number。
    private Object pageNumber(Map<String, Object> metadata) {
        Integer start = numericPage(metadata.get("page_start"));
        Integer end = numericPage(metadata.get("page_end"));
        if (start != null && end != null) {
            return start.equals(end) ? start : start + "-" + end;
        }
        return numericPage(metadata.get("page_number"));
    }

    // 章节路径或片段标签单独作为 location 返回，不再复用 page_number。
    private String sourceLocation(Map<String, Object> metadata) {
        Object sourceLocation = metadata.get("source_location");
        if (sourceLocation != null && StringUtils.hasText(sourceLocation.toString())) {
            return sourceLocation.toString().trim();
        }
        Object parentIndex = metadata.get("parent_index");
        if (parentIndex != null) {
            return "片段" + parentIndex;
        }
        return null;
    }

    private Integer numericPage(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric) && numeric >= 0 && Math.rint(numeric) == numeric) {
                return (int) numeric;
            }
            return null;
        }
        if (value != null && value.toString().trim().matches("\\d+")) {
            try {
                return Integer.valueOf(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String cleanFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return fileName;
        }
        return fileName.replaceFirst("(?i)\\.(md|markdown|pdf|docx|txt)$", "");
    }

    private String fileType(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(idx + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private SourceValidationException validationFailure(String reason, Object result, List<Document> candidates) {
        String answerType = null;
        int requestedSources = 0;
        if (result instanceof SourcedAnswerResult sourcedAnswerResult) {
            answerType = sourcedAnswerResult.answerType();
            requestedSources = sourcedAnswerResult.usedSources() == null ? 0 : sourcedAnswerResult.usedSources().size();
        } else if (result instanceof SourcePlanResult sourcePlanResult) {
            answerType = sourcePlanResult.answerType();
            requestedSources = sourcePlanResult.usedSources() == null ? 0 : sourcePlanResult.usedSources().size();
        }
        log.warn("Used source validation failed: reason={}, answerType={}, requestedSources={}, candidateCount={}",
                reason,
                answerType,
                requestedSources,
                candidates == null ? 0 : candidates.size());
        return new SourceValidationException(UNRELIABLE_SOURCE_MESSAGE, reason);
    }

    public record ValidatedSourcePlan(
            String answerType,
            List<String> evidenceIds,
            List<UsedSource> usedSources) {
        public ValidatedSourcePlan {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            usedSources = usedSources == null ? List.of() : List.copyOf(usedSources);
        }
    }
}
