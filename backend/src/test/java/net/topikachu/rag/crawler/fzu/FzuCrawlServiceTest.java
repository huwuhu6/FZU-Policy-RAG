package net.topikachu.rag.crawler.fzu;

import net.topikachu.rag.business.document.service.impl.DocumentIngestionService;
import net.topikachu.rag.business.document.vo.DocumentSourceMetadata;
import net.topikachu.rag.business.document.vo.UploadResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FzuCrawlServiceTest {

    @Test
    void countsDiscoveryAndArtifactFailuresTogether() throws Exception {
        FzuJwcCrawler crawler = mock(FzuJwcCrawler.class);
        DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);
        DocumentSourceMetadata metadata = new DocumentSourceMetadata(
                "https://jwch.fzu.edu.cn/detail", null, "TEACHING_FILE", LocalDate.of(2025, 1, 1), null);
        FzuJwcCrawler.CrawlArtifact accepted = new FzuJwcCrawler.CrawlArtifact(
                "accepted.md", "text/markdown", "content", metadata);
        FzuJwcCrawler.CrawlArtifact rejected = new FzuJwcCrawler.CrawlArtifact(
                "rejected.md", "text/markdown", "content", metadata);
        FzuJwcCrawler.CrawlDiscovery discovery = new FzuJwcCrawler.CrawlDiscovery(
                1, 2, 2, 0, List.of(accepted, rejected), List.of("detail-url | parse failed"));
        when(crawler.discover()).thenReturn(discovery);
        when(ingestionService.ingest(any(), anyString(), anyString(), anyBoolean(),
                anyString(), any(), any())).thenReturn(Mono.just(UploadResult.builder().created(true).build()));
        doAnswer(invocation -> {
            FzuJwcCrawler.CrawlArtifact artifact = invocation.getArgument(0);
            if (artifact.fileName().equals("rejected.md")) {
                throw new IllegalStateException("download failed");
            }
            return null;
        }).when(crawler).writeArtifact(any(), any());

        FzuCrawlSummary summary = new FzuCrawlService(crawler, ingestionService).crawl("user").block();

        assertEquals(2, summary.failed());
        assertEquals(1, summary.submitted());
        assertEquals(2, summary.failures().size());
        assertTrue(summary.failures().stream().anyMatch(failure -> failure.contains("parse failed")));
        assertTrue(summary.failures().stream().anyMatch(failure -> failure.contains("download failed")));
    }
}
