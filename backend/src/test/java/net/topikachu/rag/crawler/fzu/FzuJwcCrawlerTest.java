package net.topikachu.rag.crawler.fzu;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FzuJwcCrawlerTest {

    private final FzuJwcCrawler crawler = new FzuJwcCrawler(
            "https://jwch.fzu.edu.cn", 20, 8, 3, 5000, "test-agent");

    @Test
    void parsesListItemsAndUsesAbsoluteDetailUrl() {
        Document page = Jsoup.parse("""
                <ul class="list-gl">
                  <li>2025-03-12 <a href="/info/1201/14307.htm">福州大学本科生转专业管理实施办法</a></li>
                </ul>
                <a href="/jxwj/2.htm">下页</a>
                """, "https://jwch.fzu.edu.cn/jxwj.htm");

        List<FzuJwcCrawler.ListItem> items = crawler.parseListPage(
                page, "https://jwch.fzu.edu.cn/jxwj.htm", FzuJwcCrawler.SourceSection.TEACHING_FILE, null);

        assertEquals(1, items.size());
        assertEquals("福州大学本科生转专业管理实施办法", items.get(0).title());
        assertEquals("https://jwch.fzu.edu.cn/info/1201/14307.htm", items.get(0).url());
        assertEquals(LocalDate.of(2025, 3, 12), items.get(0).listDate());
        assertEquals("https://jwch.fzu.edu.cn/jxwj/2.htm",
                crawler.findNextPageUrl(page, "https://jwch.fzu.edu.cn/jxwj.htm"));
    }

    @Test
    void parsesDetailBodyDateAndOnlySupportedNonTransactionalAttachments() {
        Document page = Jsoup.parse("""
                <div class="articelMain">
                  <h1>福州大学本科生课程替代与学分认定管理实施办法</h1>
                  <p>发布时间：2025-01-10</p>
                  <p>第一章 总则</p>
                  <a href="/upload/policy.pdf">政策正文.pdf</a>
                  <a href="/upload/申请表.docx">课程申请表.docx</a>
                  <a href="/upload/list.xlsx">汇总表.xlsx</a>
                </div>
                """, "https://jwch.fzu.edu.cn/info/1091/14148.htm");
        FzuJwcCrawler.ListItem item = new FzuJwcCrawler.ListItem(
                "福州大学本科生课程替代与学分认定管理实施办法",
                "https://jwch.fzu.edu.cn/info/1091/14148.htm",
                null, FzuJwcCrawler.SourceSection.TEACHING_FILE, null);

        FzuJwcCrawler.DetailPage detail = crawler.parseDetail(page, item);

        assertEquals(LocalDate.of(2025, 1, 10), detail.metadata().publishDate());
        assertTrue(detail.body().contains("第一章 总则"));
        assertEquals(1, detail.attachments().size());
        assertEquals("政策正文.pdf", detail.attachments().get(0).fileName());
        assertEquals(2, detail.skippedAttachments());
        assertNotNull(detail.metadata().sourceUrl());
    }

    @Test
    void relevanceAndAttachmentFiltersStaySimpleAndDeterministic() {
        assertTrue(FzuJwcCrawler.isRelevantTitle("关于本科生重修选课的通知"));
        assertFalse(FzuJwcCrawler.isRelevantTitle("本科生奖学金评定管理办法"));
        assertFalse(FzuJwcCrawler.isRelevantTitle("关于公布修读辅修专业学生名单的公示"));
        assertFalse(FzuJwcCrawler.isRelevantTitle("关于召开工作会议的通知"));
        assertTrue(FzuJwcCrawler.isSupportedAttachment("policy.DOCX"));
        assertFalse(FzuJwcCrawler.isSupportedAttachment("名单.xlsx"));
        assertTrue(FzuJwcCrawler.isBlacklistedAttachment("课程申请表.docx"));
        assertFalse(FzuJwcCrawler.isBlacklistedAttachment("政策正文.pdf"));
    }
}
