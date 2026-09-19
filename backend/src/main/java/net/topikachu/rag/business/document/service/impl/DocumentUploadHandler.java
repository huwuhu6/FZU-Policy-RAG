package net.topikachu.rag.business.document.service.impl;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.vo.BatchUploadResponse;
import net.topikachu.rag.business.document.vo.DocumentSourceMetadata;
import net.topikachu.rag.business.document.vo.UploadItemResult;
import net.topikachu.rag.business.document.vo.UploadResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * HTTP multipart adapter. The actual Path-based ingestion is shared with crawler
 * through {@link DocumentIngestionService}.
 */
@Component
@Slf4j
public class DocumentUploadHandler {

    private final DocumentIngestionService ingestionService;

    @Value("${rag.upload.max-size-bytes:52428800}")
    private long maxSizeBytes;

    @Value("${input.directory:${java.io.tmpdir}/fzu-policy-rag-input}")
    private String inputDirectory;

    @Value("${rag.upload.allowed-ext:pdf,doc,docx,txt,md}")
    private String allowedExt;

    @Autowired
    public DocumentUploadHandler(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /** Kept for existing unit tests and source compatibility with the old handler. */
    public DocumentUploadHandler(net.topikachu.rag.business.document.mapper.DocumentMapper documentMapper,
                                 net.topikachu.rag.observability.TracingSupport tracingSupport,
                                 net.topikachu.rag.business.document.service.EtlJobService etlJobService,
                                 org.springframework.transaction.PlatformTransactionManager transactionManager,
                                 net.topikachu.rag.service.storage.ObjectStorageService objectStorageService) {
        this(new DocumentIngestionService(documentMapper, tracingSupport, etlJobService,
                transactionManager, objectStorageService));
    }

    public Mono<UploadResult> upload(FilePart filePart,
                                     String fileName,
                                     boolean overwrite,
                                     String userId,
                                     List<String> tags) {
        String requestedFileName = StringUtils.hasText(fileName) ? fileName : filePart.filename();
        String contentType = filePart.headers().getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : filePart.headers().getContentType().toString();
        return createTempFile()
                .flatMap(tempFile -> filePart.transferTo(tempFile)
                        .then(ingest(tempFile, requestedFileName, contentType, overwrite, userId, tags,
                                DocumentSourceMetadata.empty()))
                        .doFinally(signal -> safeDelete(tempFile)));
    }

    public Mono<BatchUploadResponse> uploadBatch(Flux<FilePart> files,
                                                 boolean overwrite,
                                                 String userId,
                                                 List<String> tags) {
        return files.flatMapSequential(file -> upload(file, null, overwrite, userId, tags)
                        .map(result -> UploadItemResult.builder()
                                .success(true)
                                .created(result.isCreated())
                                .docUuid(result.getDocUuid())
                                .fileName(result.getFileName())
                                .status(result.getStatus())
                                .fileHash(result.getFileHash())
                                .build())
                        .onErrorResume(error -> {
                            log.error("Batch upload failed: fileName={}, err={}", file.filename(), error.toString(), error);
                            return Mono.just(UploadItemResult.builder()
                                    .success(false)
                                    .created(false)
                                    .fileName(file.filename())
                                    .error(error.getMessage())
                                    .build());
                        }), 3)
                .collectList()
                .map(results -> {
                    int success = 0;
                    int created = 0;
                    int existed = 0;
                    int failed = 0;
                    for (UploadItemResult result : results) {
                        if (result.isSuccess()) {
                            success++;
                            if (result.isCreated()) {
                                created++;
                            } else {
                                existed++;
                            }
                        } else {
                            failed++;
                        }
                    }
                    return BatchUploadResponse.builder()
                            .total(results.size())
                            .successCount(success)
                            .createdCount(created)
                            .existedCount(existed)
                            .failedCount(failed)
                            .results(results)
                            .build();
                });
    }

    private Mono<UploadResult> ingest(Path path,
                                      String fileName,
                                      String contentType,
                                      boolean overwrite,
                                      String userId,
                                      List<String> tags,
                                      DocumentSourceMetadata metadata) {
        ingestionService.configureUploadProperties(inputDirectory, maxSizeBytes, allowedExt);
        return ingestionService.ingest(path, fileName, contentType, overwrite, userId, tags, metadata);
    }

    private Mono<Path> createTempFile() {
        return Mono.fromCallable(() -> {
                    Path baseDir = Paths.get(inputDirectory).toAbsolutePath().normalize();
                    Files.createDirectories(baseDir);
                    return Files.createTempFile(baseDir, "upload_", ".tmp");
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void safeDelete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }
}
