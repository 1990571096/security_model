package com.securitymodel.application.knowledge;

import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.application.task.TaskCommandException;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.knowledge.KnowledgeDocument;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.AlgorithmMapper;
import com.securitymodel.infrastructure.persistence.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexServiceTest {

    @TempDir
    Path tempDir;

    private final KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeDocumentWriter writer = mock(KnowledgeDocumentWriter.class);
    private final AlgorithmMapper algorithmMapper = mock(AlgorithmMapper.class);
    private final ResourceQueryService resourceQueryService = mock(ResourceQueryService.class);
    private final TaskAccessGuard taskAccessGuard = mock(TaskAccessGuard.class);

    private KnowledgeIndexService service(int maxChunkChars) {
        return new KnowledgeIndexService(documentMapper, writer, algorithmMapper,
                resourceQueryService, taskAccessGuard, tempDir.toString(), maxChunkChars);
    }

    @Test
    void sddImportIndexesOnlyChangedMarkdownAndSkipsSameHash() throws Exception {
        Files.writeString(tempDir.resolve("05-decisions.md"), "# 决策\n\n租约定。\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("readme.txt"), "not markdown", StandardCharsets.UTF_8);
        KnowledgeDocument saved = document(1L);
        KnowledgeDocument active = document(1L);
        active.setContentHash(sha256(KnowledgeSanitizer.sanitize("# 决策\n\n租约定。\n")));
        when(documentMapper.findActiveBySourceKey("sdd:05-decisions.md")).thenReturn(null, active);
        when(writer.saveVersion(any(), anyString(), anyString(), anyString(), any(), anyString(),
                anyList(), any())).thenReturn(saved);

        KnowledgeIndexService.IndexStats first = service(800).reindexSdd();
        assertEquals(1, first.scanned());
        assertEquals(1, first.changed());
        assertEquals(0, first.skipped());

        KnowledgeIndexService.IndexStats second = service(800).reindexSdd();
        assertEquals(1, second.scanned());
        assertEquals(1, second.skipped());
        verify(writer, org.mockito.Mockito.times(1)).saveVersion(any(), anyString(), anyString(),
                anyString(), any(), anyString(), anyList(), any());
    }

    @Test
    void unchangedActiveVersionIsNotRewritten() throws Exception {
        String content = KnowledgeSanitizer.sanitize("# 决策\n\n租约定。\n");
        Files.writeString(tempDir.resolve("05.md"), "# 决策\n\n租约定。\n", StandardCharsets.UTF_8);
        KnowledgeDocument active = new KnowledgeDocument();
        active.setDocumentId(3L);
        active.setVersion(1);
        active.setContentHash(sha256(content));
        when(documentMapper.findActiveBySourceKey("sdd:05.md")).thenReturn(active);

        KnowledgeIndexService.IndexStats stats = service(800).reindexSdd();

        assertEquals(1, stats.skipped());
        assertEquals(0, stats.changed());
        verify(writer, never()).saveVersion(any(), anyString(), anyString(), anyString(), any(),
                anyString(), anyList(), any());
    }

    @Test
    void changedContentCreatesNewVersionThroughWriter() throws Exception {
        KnowledgeDocument active = new KnowledgeDocument();
        active.setDocumentId(4L);
        active.setVersion(1);
        active.setContentHash("stale-hash");
        when(documentMapper.findActiveBySourceKey("sdd:05.md")).thenReturn(active);
        when(writer.saveVersion(any(), anyString(), anyString(), anyString(), any(), anyString(),
                anyList(), any())).thenReturn(document(5L));
        Files.writeString(tempDir.resolve("05.md"), "# 新内容\n\n新内容。\n", StandardCharsets.UTF_8);

        KnowledgeIndexService.IndexStats stats = service(800).reindexSdd();

        assertEquals(1, stats.changed());
        verify(writer).saveVersion(eq("sdd"), eq("sdd:05.md"), anyString(), eq("shared"),
                eq(null), anyString(), anyList(), any());
    }

    @Test
    void algorithmsImportOnlyEnabledRowsAndBuildDeterministicMarkdown() {
        Algorithm enabled = new Algorithm();
        enabled.setAlgorithmId(7L);
        enabled.setAlgorithmName("LDD");
        enabled.setEnabled(true);
        enabled.setAlgorithmType("defense");
        enabled.setDefaultConfig("{\"epochs\":5}");
        Algorithm disabled = new Algorithm();
        disabled.setAlgorithmId(8L);
        disabled.setAlgorithmName("disabled");
        disabled.setEnabled(false);
        when(algorithmMapper.selectList(any())).thenReturn(List.of(enabled, disabled));
        when(writer.saveVersion(any(), anyString(), anyString(), anyString(), any(), anyString(),
                anyList(), any())).thenReturn(document(9L));

        KnowledgeIndexService.IndexStats stats = service(800).reindexAlgorithms();

        assertEquals(1, stats.scanned());
        assertEquals(1, stats.changed());
        verify(writer).saveVersion(eq("algorithm"), eq("algorithm:7"), eq("LDD"), eq("shared"),
                eq(null), anyString(), anyList(), any());
    }

    @Test
    void demoTaskReportIsRejectedBeforeIndexing() {
        PreDefenseTask demo = new PreDefenseTask();
        demo.setTaskId(1L);
        demo.setTaskName("demo");
        demo.setDataOrigin("demo");
        when(taskAccessGuard.requireOwned(1L)).thenReturn(demo);

        assertThrows(TaskCommandException.class, () -> service(800).indexTaskReport(1L));
        verify(writer, never()).saveVersion(any(), anyString(), anyString(), anyString(), any(),
                anyString(), anyList(), any());
    }

    @Test
    void reportOwnerGuardRunsBeforeAnyContentRead() {
        when(taskAccessGuard.requireOwned(2L))
                .thenThrow(new com.securitymodel.application.resource.ResourceNotFoundException("not found"));

        assertThrows(com.securitymodel.application.resource.ResourceNotFoundException.class,
                () -> service(800).indexTaskReport(2L));
    }

    @Test
    void retireDelegatesWithDocumentId() {
        when(writer.retire(eq(10L), any())).thenReturn(true);
        assertTrue(service(800).retire(10L));
        verify(writer).retire(eq(10L), any());
    }

    private KnowledgeDocument document(Long id) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocumentId(id);
        document.setVersion(1);
        document.setCreatedAt(LocalDateTime.now());
        return document;
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte item : digest) {
            builder.append(String.format("%02x", item));
        }
        return builder.toString();
    }
}
