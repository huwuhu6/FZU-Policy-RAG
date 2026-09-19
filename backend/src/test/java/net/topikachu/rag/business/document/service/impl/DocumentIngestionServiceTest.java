package net.topikachu.rag.business.document.service.impl;

import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.service.EtlJobService;
import net.topikachu.rag.business.document.vo.DocumentSourceMetadata;
import net.topikachu.rag.business.document.vo.UploadResult;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestionServiceTest {

    @TempDir
    Path tempDir;

    private DocumentMapper documentMapper;
    private EtlJobService etlJobService;
    private ObjectStorageService objectStorageService;
    private DocumentIngestionService service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        TracingSupport tracingSupport = mock(TracingSupport.class);
        etlJobService = mock(EtlJobService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        objectStorageService = mock(ObjectStorageService.class);
        service = new DocumentIngestionService(documentMapper, tracingSupport, etlJobService,
                transactionManager, objectStorageService);
        ReflectionTestUtils.setField(service, "maxSizeBytes", 1024 * 1024L);
        ReflectionTestUtils.setField(service, "allowedExt", "pdf,doc,docx,txt,md");
        doAnswer(invocation -> invocation.getArgument(2))
                .when(tracingSupport).traceMono(anyString(), anyMap(), any());
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        when(objectStorageService.putObject(anyString(), any(Path.class), anyString()))
                .thenReturn(Mono.empty());
    }

    @Test
    void pathEntryUsesSharedIngestionAndPreservesSourceMetadataAndHashDedupe() throws Exception {
        Path source = tempDir.resolve("transfer-policy.md");
        Files.writeString(source, "转专业政策正文");
        DocumentSourceMetadata metadata = new DocumentSourceMetadata(
                "https://jwch.fzu.edu.cn/info/1201/14307.htm",
                "https://jwch.fzu.edu.cn/upload/policy.pdf",
                "STUDENT_HANDBOOK",
                LocalDate.of(2025, 9, 30),
                2025);
        Document existing = new Document();
        existing.setDocUuid("existing-doc");
        existing.setFileName("transfer-policy.md");
        existing.setFileHash("same-hash");

        when(documentMapper.selectOne(any())).thenReturn(null, existing);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        UploadResult created = service.ingest(source, "transfer-policy.md", "text/markdown", false,
                "admin", List.of("fzu-crawler"), metadata).block();
        UploadResult duplicate = service.ingest(source, "transfer-policy.md", "text/markdown", false,
                "admin", List.of("fzu-crawler"), metadata).block();

        assertTrue(created.isCreated());
        assertFalse(duplicate.isCreated());
        verify(documentMapper).insert(any(Document.class));
        verify(etlJobService).queueDocumentIngestionSync(any(Document.class), anyString(), anyString());

        org.mockito.ArgumentCaptor<Document> captor = org.mockito.ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(captor.capture());
        Document saved = captor.getValue();
        assertEquals(metadata.sourceUrl(), saved.getSourceUrl());
        assertEquals(metadata.artifactUrl(), saved.getArtifactUrl());
        assertEquals(metadata.sourceSection(), saved.getSourceSection());
        assertEquals(metadata.publishDate(), saved.getPublishDate());
        assertEquals(metadata.handbookYear(), saved.getHandbookYear());
    }
}
