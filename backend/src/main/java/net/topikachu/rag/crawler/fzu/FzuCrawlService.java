package net.topikachu.rag.crawler.fzu;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.service.impl.DocumentIngestionService;
import net.topikachu.rag.business.document.vo.UploadResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class FzuCrawlService {

    private final FzuJwcCrawler crawler;
    private final DocumentIngestionService ingestionService;

    public Mono<FzuCrawlSummary> crawl(String userId) {
        return Mono.fromCallable(crawler::discover)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(discovery -> submitArtifacts(discovery, userId))
                .map(result -> result.withUserFailures());
    }

    private Mono<MutableSummary> submitArtifacts(FzuJwcCrawler.CrawlDiscovery discovery, String userId) {
        MutableSummary summary = new MutableSummary(discovery, userId);
        for (String failure : discovery.failures()) {
            summary.failures.add(failure);
        }
        return Flux.fromIterable(discovery.artifacts())
                .concatMap(artifact -> submitArtifact(artifact, summary))
                .then(Mono.fromSupplier(() -> summary));
    }

    private Mono<Void> submitArtifact(FzuJwcCrawler.CrawlArtifact artifact, MutableSummary summary) {
        String suffix = suffix(artifact.fileName());
        return Mono.using(
                () -> Files.createTempFile("fzu-crawl-", suffix),
                path -> Mono.fromCallable(() -> {
                            crawler.writeArtifact(artifact, path);
                            return path;
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(written -> ingestionService.ingest(
                                written,
                                artifact.fileName(),
                                artifact.contentType(),
                                false,
                                summary.userId,
                                List.of("fzu-crawler", artifact.metadata().sourceSection()),
                                artifact.metadata()))
                        .doOnNext(result -> recordResult(artifact, result, summary))
                        .onErrorResume(error -> {
                            summary.failed++;
                            summary.failures.add(artifact.fileName() + " | " + message(error));
                            log.warn("FZU crawler artifact failed: fileName={}, sourceUrl={}",
                                    artifact.fileName(), artifact.metadata().sourceUrl(), error);
                            return Mono.empty();
                        })
                        .then(),
                this::deleteTempFile);
    }

    private void recordResult(FzuJwcCrawler.CrawlArtifact artifact, UploadResult result, MutableSummary summary) {
        if (result.isCreated()) {
            summary.submitted++;
        } else {
            summary.duplicate++;
        }
        switch (suffix(artifact.fileName()).toLowerCase()) {
            case ".md" -> summary.html++;
            case ".pdf" -> summary.pdf++;
            case ".doc" -> summary.doc++;
            case ".docx" -> summary.docx++;
            default -> {
            }
        }
    }

    private void deleteTempFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception error) {
            log.warn("Failed to delete crawler temp file: {}", path, error);
        }
    }

    private String suffix(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index < 0 ? ".tmp" : fileName.substring(index);
    }

    private String message(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static final class MutableSummary {
        private final int pagesScanned;
        private final int discovered;
        private final int relevant;
        private final int skipped;
        private final String userId;
        private int submitted;
        private int duplicate;
        private int failed;
        private int html;
        private int pdf;
        private int doc;
        private int docx;
        private final List<String> failures = new ArrayList<>();

        private MutableSummary(FzuJwcCrawler.CrawlDiscovery discovery, String userId) {
            this.pagesScanned = discovery.pagesScanned();
            this.discovered = discovery.discovered();
            this.relevant = discovery.relevant();
            this.skipped = discovery.skipped();
            this.userId = userId;
            this.failed = discovery.failures().size();
        }

        private FzuCrawlSummary withUserFailures() {
            return new FzuCrawlSummary(pagesScanned, discovered, relevant, submitted, duplicate,
                    skipped, failed, html, pdf, doc, docx, List.copyOf(failures));
        }
    }
}
