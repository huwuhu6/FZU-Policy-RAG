package net.topikachu.rag.service.etl;

import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.KnowledgeParentBlock;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.etl.fileParseStrategy.FileParseStrategyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EtlPipelineMetadataTest {

    @Test
    void enrichMetadataUsesStoredBusinessFileNameForParentAndChild() {
        Document storedDocument = storedDocument("政策文件.pdf");
        ChunkUtils.ParentChildDocuments documents = documents();
        EtlPipeline pipeline = pipeline();

        when(pipelineMetadataBuilder(pipeline).build(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> Map.of("file_name", invocation.getArgument(2)));

        ReflectionTestUtils.invokeMethod(
                pipeline,
                "enrichMetadata",
                EtlPipeline.EtlContext.of(Path.of("etl_123456_政策文件.pdf"), "doc-1", "user-1", List.of("tag")),
                documents,
                storedDocument);

        assertEquals("政策文件.pdf", documents.parentBlocks().get(0).getFileName());
        assertEquals("政策文件.pdf", documents.childDocuments().get(0).getMetadata().get("file_name"));
    }

    @Test
    void enrichMetadataFallsBackToTemporaryPathNameWhenStoredNameIsBlank() {
        Document storedDocument = storedDocument(" ");
        ChunkUtils.ParentChildDocuments documents = documents();
        EtlPipeline pipeline = pipeline();

        when(pipelineMetadataBuilder(pipeline).build(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> Map.of("file_name", invocation.getArgument(2)));

        ReflectionTestUtils.invokeMethod(
                pipeline,
                "enrichMetadata",
                EtlPipeline.EtlContext.of(Path.of("etl_123456_政策文件.pdf"), "doc-1", "user-1", List.of()),
                documents,
                storedDocument);

        assertEquals("etl_123456_政策文件.pdf", documents.parentBlocks().get(0).getFileName());
        assertEquals("etl_123456_政策文件.pdf", documents.childDocuments().get(0).getMetadata().get("file_name"));
    }

    private EtlPipeline pipeline() {
        return new EtlPipeline(
                mock(HybridVectorWriter.class),
                mock(org.springframework.ai.transformer.splitter.TextSplitter.class),
                mock(DocReader.class),
                mock(DocumentMapper.class),
                mock(DocumentChunkMetadataBuilder.class),
                mock(TracingSupport.class),
                mock(FileParseStrategyFactory.class),
                mock(EtlStatusManager.class),
                mock(KnowledgeParentBlockService.class));
    }

    private DocumentChunkMetadataBuilder pipelineMetadataBuilder(EtlPipeline pipeline) {
        return (DocumentChunkMetadataBuilder) ReflectionTestUtils.getField(pipeline, "metadataBuilder");
    }

    private ChunkUtils.ParentChildDocuments documents() {
        KnowledgeParentBlock parent = new KnowledgeParentBlock();
        org.springframework.ai.document.Document child =
                new org.springframework.ai.document.Document("child", new HashMap<>());
        return new ChunkUtils.ParentChildDocuments(List.of(parent), List.of(child));
    }

    private Document storedDocument(String fileName) {
        Document document = new Document();
        document.setDocUuid("doc-1");
        document.setFileName(fileName);
        document.setTags(List.of("tag"));
        document.setAclVersion(1);
        document.setSpaceCode("public");
        return document;
    }
}
