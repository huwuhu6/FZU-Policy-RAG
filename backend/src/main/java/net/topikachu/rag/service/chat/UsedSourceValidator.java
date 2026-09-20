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
            "当前知识库暂未找到可直接引用的可靠答案。建议前往福州大学教务处官网查询最新政策，或咨询辅导员、教务老师获取准确信息。";
    public static final String REASON_ANSWER_MISSING = "answer_missing";
    public static final String REASON_USED_SOURCES_EMPTY = "used_sources_empty";
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
            throw validationFailure(REASON_USED_SOURCES_EMPTY, plan, candidates);
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
        return new UsedSource(
                evidenceId(document),
                stringValue(metadata.get("doc_uuid")),
                stringValue(metadata.get("file_name")),
                sourceLocation(metadata),
                fileType(stringValue(metadata.get("file_name"))));
    }

    // 同文档同位置的多个 evidence_id 只保留第一条（去重合并展示，避免溯源列表冗余）
    private List<UsedSource> collapseDisplayedSources(List<UsedSource> sources) {
        Map<String, UsedSource> unique = new LinkedHashMap<>();
        for (UsedSource source : sources) {
            if (source == null || !StringUtils.hasText(source.docUuid())) {
                continue;
            }
            String key = source.docUuid() + "|" + (source.pageNumber() == null ? "" : source.pageNumber());
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

    // 解析溯源展示位置：source_location > page_start/page_end > parent_index
    // ① 显式 source_location（DOCX/MD 面包屑，如"学生纪律 > 开除程序"）
    // ② page_start/page_end（PDF 页码范围，如"3-4"或"5"）
    // ③ parent_index → "片段N"（无标题结构的非 PDF 文档）
    private Object sourceLocation(Map<String, Object> metadata) {
        // ① 优先：语义化溯源路径（DOCX/MD 策略写入的面包屑）
        Object sourceLocation = metadata.get("source_location");
        if (sourceLocation != null && StringUtils.hasText(sourceLocation.toString())) {
            return sourceLocation.toString().trim();
        }
        // ② 次选：PDF 页码范围
        Object pageStart = metadata.get("page_start");
        Object pageEnd = metadata.get("page_end");
        if (pageStart != null && pageEnd != null) {
            String start = pageStart.toString();
            String end = pageEnd.toString();
            // 单页 → 直接返回页码，跨页 → 返回"起始-结束"范围
            return start.equals(end) ? pageStart : start + "-" + end;
        }
        Object pageNumber = metadata.get("page_number");
        if (pageNumber != null) {
            return pageNumber;
        }
        // ③ 再次：通用 parent_index → "片段N"
        Object parentIndex = metadata.get("parent_index");
        if (parentIndex != null) {
            return "片段" + parentIndex;
        }
        return null;
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
