package com.securitymodel.application.resource;

import com.securitymodel.application.audit.AdminRequiredException;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.infrastructure.cache.ResourceCatalogCache;
import com.securitymodel.infrastructure.persistence.mapper.AlgorithmMapper;
import com.securitymodel.infrastructure.persistence.mapper.DatasetMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ResourceCommandServiceTest {
    private final DatasetMapper datasets = mock(DatasetMapper.class);
    private final AlgorithmMapper algorithms = mock(AlgorithmMapper.class);
    private final CurrentUserService user = mock(CurrentUserService.class);
    private final OperationAuditService audit = mock(OperationAuditService.class);
    private final ResourceCatalogCache cache = mock(ResourceCatalogCache.class);
    private final ResourceCommandService service = new ResourceCommandService(datasets, algorithms, user, audit, cache);

    @Test
    void registrationDoesNotPretendAPathWasParsed() {
        when(user.username()).thenReturn("operator");
        Dataset dataset = service.saveDataset(null,
                new ResourceCommandService.DatasetInput("  CIFAR  ", "image", "F:/datasets/missing.zip",
                        50000, 10, "train", true));

        assertEquals("CIFAR", dataset.getDatasetName());
        assertEquals("pending", dataset.getParseStatus());
        assertNull(dataset.getParseTime());
        assertEquals("operator", dataset.getCreateBy());
        verify(datasets).insert(dataset);
        verify(audit).recordResource(eq("dataset"), any(), eq("CREATE"), eq("pending"), any());
    }

    @Test
    void editingAPathInvalidatesPriorVerification() {
        Dataset existing = new Dataset();
        existing.setDatasetId(4L);
        existing.setFilePath("old.zip");
        existing.setParseStatus("success");
        when(datasets.selectById(4L)).thenReturn(existing);

        Dataset updated = service.saveDataset(4L,
                new ResourceCommandService.DatasetInput("CIFAR", "image", "new.zip", 1, 2, null, true));

        assertEquals("pending", updated.getParseStatus());
        verify(datasets).updateById(existing);
        verify(cache).evict(ResourceCatalogCache.DATASET_LIST, "security-model:resource:dataset:4");
    }

    @Test
    void uniqueConstraintIsReportedAsConflict() {
        doThrow(new DuplicateKeyException("uniq_dataset_name")).when(datasets).insert(any(Dataset.class));
        assertThrows(ResourceConflictException.class, () -> service.saveDataset(null,
                new ResourceCommandService.DatasetInput("CIFAR", "image", "test.zip", 1, 2, null, true)));
        verifyNoInteractions(audit);
    }

    @Test
    void disableKeepsReferencedAlgorithmRow() {
        Algorithm existing = new Algorithm();
        existing.setAlgorithmId(9L);
        existing.setEnabled(true);
        when(algorithms.selectById(9L)).thenReturn(existing);

        assertFalse(service.disableAlgorithm(9L).getEnabled());
        verify(algorithms).updateById(existing);
        verify(algorithms, never()).deleteById(9L);
        verify(cache).evict(ResourceCatalogCache.ALGORITHM_LIST, "security-model:resource:algorithm:9");
    }

    @Test
    void nonAdminCannotMaintainSharedResources() {
        doThrow(new AdminRequiredException("仅管理员可维护共享数据集"))
                .when(user).requireAdmin();

        assertThrows(AdminRequiredException.class, () -> service.saveDataset(null,
                new ResourceCommandService.DatasetInput("CIFAR", "image", "test.zip", 1, 2, null, true)));
        verifyNoInteractions(datasets, audit);
    }

    @Test
    void verificationPromotesAReadableDirectory(@TempDir Path directory) throws Exception {
        java.nio.file.Files.createFile(directory.resolve("cifar10.bin"));
        Dataset existing = existingDataset(3L, directory.toString());
        when(datasets.selectById(3L)).thenReturn(existing);
        when(user.username()).thenReturn("admin");

        Dataset verified = service.verifyDataset(3L);

        assertEquals("success", verified.getParseStatus());
        assertNotNull(verified.getParseTime());
        assertTrue(verified.getParseMessage().contains("验证通过"));
        verify(datasets).updateById(existing);
        verify(audit).recordResource(eq("dataset"), eq(3L), eq("VERIFY"), eq("success"), any());
    }

    @Test
    void verificationReportsMissingPathWithoutThrowing() {
        Dataset existing = existingDataset(4L, "F:/security-model/definitely-missing-dataset");
        when(datasets.selectById(4L)).thenReturn(existing);
        when(user.username()).thenReturn("admin");

        Dataset verified = service.verifyDataset(4L);

        assertEquals("failed", verified.getParseStatus());
        assertTrue(verified.getParseMessage().contains("路径不存在"));
        assertNotNull(verified.getParseTime());
        verify(datasets).updateById(existing);
    }

    @Test
    void verificationRejectsAnEmptyDirectory(@TempDir Path directory) {
        Dataset existing = existingDataset(5L, directory.toString());
        when(datasets.selectById(5L)).thenReturn(existing);
        when(user.username()).thenReturn("admin");

        Dataset verified = service.verifyDataset(5L);

        assertEquals("failed", verified.getParseStatus());
        assertTrue(verified.getParseMessage().contains("目录为空"));
    }

    private Dataset existingDataset(Long id, String path) {
        Dataset dataset = new Dataset();
        dataset.setDatasetId(id);
        dataset.setFilePath(path);
        dataset.setParseStatus("pending");
        return dataset;
    }
}
