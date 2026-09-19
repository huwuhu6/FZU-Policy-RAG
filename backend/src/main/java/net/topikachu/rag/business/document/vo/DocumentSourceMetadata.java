package net.topikachu.rag.business.document.vo;

import java.time.LocalDate;

/**
 * 爬虫或其他外部来源写入文档时携带的确定性来源元数据。
 * 不承载 topic、版本或有效性判断。
 */
public record DocumentSourceMetadata(
        String sourceUrl,
        String artifactUrl,
        String sourceSection,
        LocalDate publishDate,
        Integer handbookYear) {

    public static DocumentSourceMetadata empty() {
        return new DocumentSourceMetadata(null, null, null, null, null);
    }
}
