package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.AclRefreshStatus;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.service.EtlJobService;
import net.topikachu.rag.business.document.vo.DocumentSourceMetadata;
import net.topikachu.rag.business.document.vo.UploadResult;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Path-based document ingestion shared by HTTP upload and FZU crawler.
 * The existing hash, MinIO, MySQL and ETL job semantics intentionally stay here.
 */
@Component
@Slf4j
public class DocumentIngestionService {

    private final DocumentMapper documentMapper;
    private final TracingSupport tracingSupport;
    private final EtlJobService etlJobService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectStorageService objectStorageService;

    @Value("${rag.upload.max-size-bytes:52428800}")
    private long maxSizeBytes;

    @Value("${input.directory:${java.io.tmpdir}/fzu-policy-rag-input}")
    private String inputDirectory;

    @Value("${rag.upload.allowed-ext:pdf,doc,docx,txt,md}")
    private String allowedExt;

    public DocumentIngestionService(DocumentMapper documentMapper,
                                    TracingSupport tracingSupport,
                                    EtlJobService etlJobService,
                                    PlatformTransactionManager transactionManager,
                                    ObjectStorageService objectStorageService) {
        this.documentMapper = documentMapper;
        this.tracingSupport = tracingSupport;
        this.etlJobService = etlJobService;
        this.transactionManager = transactionManager;
        this.objectStorageService = objectStorageService;
    }

    public Mono<UploadResult> ingest(Path path,
                                     String fileName,
                                     String contentType,
                                     boolean overwrite,
                                     String userId,
                                     List<String> tags,
                                     DocumentSourceMetadata sourceMetadata) {
        String requestedFileName = StringUtils.hasText(fileName)
                ? fileName
                : path == null || path.getFileName() == null ? null : path.getFileName().toString();
        DocumentSourceMetadata metadata = sourceMetadata == null
                ? DocumentSourceMetadata.empty()
                : sourceMetadata;
        return tracingSupport.traceMono("etl.upload_accept",
                uploadTraceTags(requestedFileName, null, userId, tags),
                validateAndSanitize(path, requestedFileName)
                        .flatMap(finalFileName -> computeHash(path)
                                .flatMap(hash -> persistOrDedupe(path, finalFileName, hash, overwrite,
                                        userId, tags, contentType, metadata))));
    }

    private Mono<UploadResult> persistOrDedupe(Path path,
                                               String finalFileName,
                                               String hash,
                                               boolean overwrite,
                                               String userId,
                                               List<String> tags,
                                               String contentType,
                                               DocumentSourceMetadata sourceMetadata) {
        return lookupExistingDocument(hash)
                .flatMap(existing -> handleExistingDuplicate(existing, overwrite))
                .switchIfEmpty(Mono.defer(() -> createNewDocument(path, finalFileName, hash,
                        userId, tags, contentType, sourceMetadata)));
    }

    private Mono<String> validateAndSanitize(Path path, String fileName) {
        return Mono.fromCallable(() -> {
                    validateFile(path, fileName);
                    return sanitizeFileName(fileName);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> computeHash(Path path) {
        return Mono.fromCallable(() -> sha256(path))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Document> lookupExistingDocument(String hash) {
        return Mono.fromCallable(() -> findByHash(hash))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<UploadResult> handleExistingDuplicate(Document existing, boolean overwrite) {
        if (overwrite) {
            return Mono.error(new IllegalArgumentException("The file already exists : " + existing.getFileName()));
        }
        return Mono.just(toResult(existing, false));
    }

    private Mono<UploadResult> createNewDocument(Path path,
                                                  String finalFileName,
                                                  String hash,
                                                  String userId,
                                                  List<String> tags,
                                                  String contentType,
                                                  DocumentSourceMetadata sourceMetadata) {
        String docUuid = UUID.randomUUID().toString().replace("-", "");
        String objectKey = "documents/" + docUuid + "/" + finalFileName;
        Document doc = buildNewDocument(docUuid, finalFileName, hash, tags, objectKey, sourceMetadata);
        return uploadToStorageAndPersist(path, objectKey, contentType, doc, userId)
                .thenReturn(toResult(doc, true));
    }

    private Document buildNewDocument(String docUuid,
                                      String fileName,
                                      String hash,
                                      List<String> tags,
                                      String objectKey,
                                      DocumentSourceMetadata sourceMetadata) {
        Document doc = new Document();
        doc.setDocUuid(docUuid);
        doc.setFileName(fileName);
        doc.setStatus(DocumentStatus.UPLOADED.name());
        doc.setFileHash(hash);
        doc.setTags(tags);
        doc.setSpaceCode("public");
        doc.setIsPublic(Boolean.TRUE);
        doc.setAclVersion(1);
        doc.setAclRefreshStatus(AclRefreshStatus.PENDING.name());
        doc.setCreateDate(LocalDateTime.now());
        doc.setUpdateDate(LocalDateTime.now());
        doc.setObjectKey(objectKey);
        doc.setSourceUrl(sourceMetadata.sourceUrl());
        doc.setArtifactUrl(sourceMetadata.artifactUrl());
        doc.setSourceSection(sourceMetadata.sourceSection());
        doc.setPublishDate(sourceMetadata.publishDate());
        doc.setHandbookYear(sourceMetadata.handbookYear());
        return doc;
    }

    private Mono<Void> uploadToStorageAndPersist(Path path,
                                                 String objectKey,
                                                 String contentType,
                                                 Document doc,
                                                 String userId) {
        AtomicBoolean objectUploaded = new AtomicBoolean(false);
        return objectStorageService.putObject(objectKey, path, contentType)
                .doOnSuccess(v -> objectUploaded.set(true))
                .then(Mono.defer(() -> persistDocumentAndQueueEtl(doc, objectKey, userId)))
                .doOnSuccess(v -> log.info("Upload created: docUuid={}, fileName={}, status={}, fileHash={}, path={}",
                        doc.getDocUuid(), doc.getFileName(), doc.getStatus(), doc.getFileHash(), objectKey))
                .onErrorResume(e -> {
                    Mono<Void> cleanup = objectUploaded.get()
                            ? objectStorageService.deleteObject(objectKey)
                            : Mono.empty();
                    return cleanup.then(Mono.error(e));
                });
    }

    private Mono<Void> persistDocumentAndQueueEtl(Document doc, String objectKey, String userId) {
        return Mono.fromCallable(() -> {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.executeWithoutResult(status -> {
                int inserted = documentMapper.insert(doc);
                if (inserted != 1) {
                    throw new IllegalStateException("Insert document failed");
                }
                etlJobService.queueDocumentIngestionSync(doc, objectKey, userId);
            });
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Map<String, Object> uploadTraceTags(String fileName, String docUuid, String userId, List<String> tags) {
        Map<String, Object> traceTags = new LinkedHashMap<>();
        traceTags.put("document.file_name", fileName);
        traceTags.put("document.doc_uuid", docUuid);
        traceTags.put("document.user_id", userId);
        traceTags.put("document.tags", tags == null ? "" : String.join(",", tags));
        return traceTags;
    }

    private UploadResult toResult(Document doc, boolean created) {
        return UploadResult.builder()
                .created(created)
                .docUuid(doc.getDocUuid())
                .fileName(doc.getFileName())
                .status(doc.getStatus())
                .fileHash(doc.getFileHash())
                .build();
    }

    private Document findByHash(String hash) {
        if (!StringUtils.hasText(hash)) {
            return null;
        }
        return documentMapper.selectOne(Wrappers.<Document>lambdaQuery()
                .eq(Document::getFileHash, hash)
                .last("LIMIT 1"));
    }

    private void validateFile(Path path, String fileName) throws IOException {
        if (path == null || !Files.exists(path)) {
            throw new IllegalArgumentException("File is empty.");
        }
        if (Files.size(path) > maxSizeBytes) {
            throw new IllegalArgumentException("File too large. max=" + maxSizeBytes + " bytes");
        }
        String name = StringUtils.hasText(fileName) ? fileName.trim() : "";
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("File name is blank.");
        }
        String ext = getExtension(name);
        Set<String> allow = Arrays.stream(allowedExt.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        if (ext.isEmpty() || !allow.contains(ext)) {
            throw new IllegalArgumentException("File extension not allowed: " + ext + ", allowed=" + allow);
        }
    }

    private String sanitizeFileName(String name) {
        String normalized = name.trim();
        normalized = Paths.get(normalized).getFileName().toString();
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("Invalid file name after sanitize.");
        }
        return normalized;
    }

    private String getExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) {
            return "";
        }
        return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path);
                 DigestInputStream dis = new DigestInputStream(in, digest)) {
                dis.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate hash for " + path, e);
        }
    }
}
