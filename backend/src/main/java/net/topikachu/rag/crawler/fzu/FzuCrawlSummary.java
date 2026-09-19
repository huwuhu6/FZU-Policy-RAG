package net.topikachu.rag.crawler.fzu;

import java.util.List;

public record FzuCrawlSummary(
        int pagesScanned,
        int discovered,
        int relevant,
        int submitted,
        int duplicate,
        int skipped,
        int failed,
        int html,
        int pdf,
        int doc,
        int docx,
        List<String> failures) {
}
