package com.securitymodel.application.resource;

import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.application.task.LddConfigurationPolicy;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.infrastructure.cache.ResourceCatalogCache;
import com.securitymodel.infrastructure.persistence.mapper.AlgorithmMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The single source of truth for an algorithm's default hyperparameters.
 *
 * Task creation merges these defaults with the per-task overrides. The legacy
 * sys_hyper templates remain readable for historical task evidence, but new UI
 * flows no longer require a separate template selection.
 */
@Service
public class AlgorithmConfigCommandService {

    private static final String LDD_IMPLEMENTATION = "vab://pre-defense/ldd";

    private final AlgorithmMapper algorithms;
    private final LddConfigurationPolicy policy;
    private final CurrentUserService currentUser;
    private final OperationAuditService audit;
    private final ResourceCatalogCache cache;

    public AlgorithmConfigCommandService(AlgorithmMapper algorithms, LddConfigurationPolicy policy,
                                         CurrentUserService currentUser, OperationAuditService audit,
                                         ResourceCatalogCache cache) {
        this.algorithms = algorithms;
        this.policy = policy;
        this.currentUser = currentUser;
        this.audit = audit;
        this.cache = cache;
    }

    public record Input(@NotBlank @Size(max = 16000) String defaultConfig) {}

    @Transactional
    public Algorithm update(Long algorithmId, Input input) {
        currentUser.requireAdmin();
        Algorithm algorithm = algorithms.selectById(algorithmId);
        if (algorithm == null) {
            throw new ResourceNotFoundException("算法不存在");
        }
        String normalized = policy.normalizeObject(input.defaultConfig(), "算法默认参数");
        if (LDD_IMPLEMENTATION.equals(algorithm.getImplementationPath())) {
            policy.mergeAndValidate(normalized, null, null);
        }
        algorithm.setDefaultConfig(normalized);
        algorithm.setUpdateBy(currentUser.username());
        algorithm.setUpdateTime(LocalDateTime.now());
        algorithms.updateById(algorithm);
        cache.evict(ResourceCatalogCache.ALGORITHM_LIST,
                "security-model:resource:algorithm:" + algorithmId);
        audit.recordResource("algorithm", algorithmId, "UPDATE_CONFIG", "updated", null);
        return algorithm;
    }
}
