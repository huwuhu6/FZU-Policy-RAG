package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
@Slf4j
public class ContextFormatter {

    // 40000 字符约等于 10000 token（中英文混合），预留足够空间给 system prompt 和对话历史
    @Value("${rag.retrieval.max-context-chars:40000}")
    private int maxContextChars;

    public String format(List<Document> docs) {
        return format(docs, Document::getText, Document::getMetadata);
    }

    // 将父块上下文列表格式化为 LLM Prompt 中的结构化证据块
    // 每个父块包含：来源文件+位置、parent_block_id、可引用的 evidence_id 列表、完整段落内容
    public String formatParentContexts(List<ParentContextBlock> parentContexts) {
        return formatParentContextsWithStats(parentContexts).text();
    }

    public FormattedContext formatParentContextsWithStats(List<ParentContextBlock> parentContexts) {
        StringBuilder contextBuilder = new StringBuilder();
        boolean truncated = false;
        for (int i = 0; i < parentContexts.size(); i++) {
            ParentContextBlock block = parentContexts.get(i);
            String structuredEntry = String.format(
                    """
                                    【上下文块 %d】
                                    来源: %s
                                    parent_block_id: %s
                                    可引用 evidence_id:
                                    %s
                                    内容: %s
                                    ------------------------
                                    """,
                    i + 1,
                    sourceLabel(block),               // 如 "员工手册.pdf · 第3-4页" 或 "保密协议.docx · 违约责任 > 赔偿标准"
                    block.parentBlockId(),            // 父块唯一标识，LLM 不需要用，调试/追踪用
                    formatEvidenceIds(block.evidenceIds()),  // 该父块下被命中的子块 evidence_id 清单，LLM 引用时用
                    block.content());                 // 父块 1200 字完整段落，LLM 推理的核心依据

            // 超长保护：上下文总长度超过 maxContextChars(40000) 时截断，避免撑爆 token 窗口
            if (contextBuilder.length() + structuredEntry.length() > maxContextChars) {
                log.warn("Parent context limit reached, dropping remaining parent blocks from rank {}", i);
                truncated = true;
                break;
            }
            contextBuilder.append(structuredEntry);
        }
        return new FormattedContext(contextBuilder.toString(), truncated);
    }

    /**
     * Formats the final child candidates for source planning. This is
     * intentionally separate from parent-context formatting so Phase A never
     * receives the full expanded parent blocks.
     */
    public String formatCandidateEvidence(List<Document> candidates) {
        StringBuilder contextBuilder = new StringBuilder();
        List<Document> safeCandidates = candidates == null ? List.of() : candidates;
        for (int i = 0; i < safeCandidates.size(); i++) {
            Document candidate = safeCandidates.get(i);
            Map<String, Object> metadata = candidate == null ? Map.of() : candidate.getMetadata();
            String evidenceId = evidenceId(candidate);
            String structuredEntry = String.format(
                    """
                            【候选证据 %d】
                            来源: %s
                            evidence_id: %s
                            内容: %s
                            ------------------------
                            """,
                    i + 1,
                    documentSourceLabel(metadata, i),
                    evidenceId == null ? "" : evidenceId,
                    candidate == null || candidate.getText() == null ? "" : candidate.getText());
            if (contextBuilder.length() + structuredEntry.length() > maxContextChars) {
                log.warn("Candidate evidence limit reached, dropping remaining candidates from rank {}", i);
                break;
            }
            contextBuilder.append(structuredEntry);
        }
        return contextBuilder.toString();
    }

    public record FormattedContext(String text, boolean truncated) {
    }

    public <T> String format(List<T> docs,
                             Function<T, String> textExtractor,
                             Function<T, Map<String, Object>> metadataExtractor) {
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            T doc = docs.get(i);
            Map<String, Object> metadata = metadataExtractor.apply(doc);
            String filename = String.valueOf(metadata.getOrDefault("file_name", "Unknown Source"));
            String docUuid = String.valueOf(metadata.getOrDefault("doc_uuid", ""));
            String evidenceId = String.valueOf(metadata.getOrDefault("evidence_id", ""));
            Object page = metadata.getOrDefault("page_number", metadata.get("page"));
            String pageLabel = (page == null) ? ("片段" + (i + 1)) : ("第" + formatPageValue(page) + "页");

            String structuredEntry = String.format(
                    """
                                    【%s】(来源: %s, doc_uuid: %s, evidence_id: %s)
                                    内容: %s
                                    ------------------------
                                    """,
                    pageLabel, filename, docUuid, evidenceId, textExtractor.apply(doc));

            if (contextBuilder.length() + structuredEntry.length() > maxContextChars) {
                log.warn("Context limit reached, dropping remaining documents from rank {}", i);
                break;
            }
            contextBuilder.append(structuredEntry);
        }
        return contextBuilder.toString();
    }

    private String formatPageValue(Object page) {
        if (page instanceof Number number) {
            double value = number.doubleValue();
            if (Math.rint(value) == value) {
                return Long.toString((long) value);
            }
            return Double.toString(value);
        }
        return page.toString();
    }

    // 拼接来源标签：PDF 显示"文件名 · 第N页"，非 PDF 显示"文件名 · 片段N"
    private String sourceLabel(ParentContextBlock block) {
        String filename = block.fileName() == null ? "Unknown Source" : block.fileName();
        if (block.pageStart() != null && block.pageEnd() != null) {
            if (block.pageStart().equals(block.pageEnd())) {
                return filename + " · 第" + block.pageStart() + "页";
            }
            return filename + " · 第" + block.pageStart() + "-" + block.pageEnd() + "页";
        }
        return filename + " · 片段" + block.parentIndex();
    }

    private String documentSourceLabel(Map<String, Object> metadata, int index) {
        String filename = String.valueOf(metadata.getOrDefault("file_name", "Unknown Source"));
        Object pageStart = metadata.get("page_start");
        Object pageEnd = metadata.get("page_end");
        if (pageStart != null && pageEnd != null) {
            if (pageStart.toString().equals(pageEnd.toString())) {
                return filename + " · 第" + pageStart + "页";
            }
            return filename + " · 第" + pageStart + "-" + pageEnd + "页";
        }
        Object page = metadata.get("page_number");
        if (page != null) {
            return filename + " · 第" + page + "页";
        }
        Object parentIndex = metadata.get("parent_index");
        return filename + " · 片段" + (parentIndex == null ? index + 1 : parentIndex);
    }

    private String evidenceId(Document document) {
        if (document == null) {
            return null;
        }
        Object value = document.getMetadata().get("evidence_id");
        return value == null ? document.getId() : value.toString();
    }

    // 将 evidence_id 列表格式化为 LLM 可读的清单，每行一条 "- doc_uuid:child:N:hash"
    private String formatEvidenceIds(List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return "- 无";
        }
        return evidenceIds.stream()
                .map(id -> "- " + id)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }
}
