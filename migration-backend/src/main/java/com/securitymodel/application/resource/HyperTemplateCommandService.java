package com.securitymodel.application.resource;

import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.application.task.LddConfigurationPolicy;
import com.securitymodel.domain.hyper.Hyper;
import com.securitymodel.infrastructure.cache.ResourceCatalogCache;
import com.securitymodel.infrastructure.persistence.mapper.AlgorithmMapper;
import com.securitymodel.infrastructure.persistence.mapper.HyperMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class HyperTemplateCommandService {
    private final HyperMapper templates;
    private final AlgorithmMapper algorithms;
    private final LddConfigurationPolicy policy;
    private final CurrentUserService currentUser;
    private final OperationAuditService audit;
    private final ResourceCatalogCache cache;

    public HyperTemplateCommandService(HyperMapper templates, AlgorithmMapper algorithms,
                                       LddConfigurationPolicy policy, CurrentUserService currentUser,
                                       OperationAuditService audit, ResourceCatalogCache cache) {
        this.templates = templates;
        this.algorithms = algorithms;
        this.policy = policy;
        this.currentUser = currentUser;
        this.audit = audit;
        this.cache = cache;
    }

    public record Input(@NotBlank @Size(max = 100) String templateName,
                        @Size(max = 5000) String description,
                        @NotBlank @Size(max = 16000) String parameterConfig) {}

    @Transactional
    public Hyper update(Long id, Input input) {
        currentUser.requireAdmin();
        Hyper template = templates.selectById(id);
        if (template == null) {
            throw new ResourceNotFoundException("超参数模板不存在");
        }
        String config = policy.normalizeObject(input.parameterConfig(), "超参数模板配置");
        var algorithm = algorithms.selectById(template.getAlgorithmId());
        if (algorithm != null && "vab://pre-defense/ldd".equals(algorithm.getImplementationPath())) {
            policy.mergeAndValidate(algorithm.getDefaultConfig(), config, null);
        }
        template.setTemplateName(input.templateName().trim());
        template.setDescription(input.description() == null ? "" : input.description().trim());
        template.setParameterConfig(config);
        template.setUpdateBy(currentUser.username());
        template.setUpdateTime(LocalDateTime.now());
        try {
            templates.updateById(template);
        } catch (DuplicateKeyException exception) {
            throw new ResourceConflictException("超参数模板名称已存在", exception);
        }
        cache.evict(ResourceCatalogCache.HYPER_LIST);
        audit.recordResource("hyper_template", id, "UPDATE", "updated", null);
        return template;
    }
}
