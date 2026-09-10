package me.zhengjie.modules.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.exception.EntityExistException;
import me.zhengjie.modules.system.domain.Hyper;
import me.zhengjie.modules.system.domain.dto.HyperQueryCriteria;
import me.zhengjie.modules.system.mapper.HyperMapper;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
import me.zhengjie.modules.system.service.HyperService;
import me.zhengjie.utils.FileUtil;
import me.zhengjie.utils.PageResult;
import me.zhengjie.utils.PageUtil;
import me.zhengjie.utils.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

/**
 * 超参数模板管理服务实现
 * @author ZJING
 * @date 2025-08-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HyperServiceImpl extends ServiceImpl<HyperMapper, Hyper> implements HyperService {

    private final HyperMapper hyperMapper;
    private final PreDefenseMapper preDefenseMapper;

    @Override
    public Hyper findById(Long templateId) {
        Hyper hyper = hyperMapper.selectById(templateId);
        if (ObjectUtil.isNull(hyper)) {
            throw new BadRequestException("超参数模板不存在");
        }
        return hyper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(Hyper resources) {
        // 检查模板名称是否已存在
        if (isTemplateNameDuplicate(resources.getTemplateName(), null)) {
            throw new EntityExistException(Hyper.class, "templateName", resources.getTemplateName());
        }
        
        // 验证模板参数
        validateTemplate(resources);
        
        // 设置默认值
        if (ObjectUtil.isNull(resources.getEnabled())) {
            resources.setEnabled(true);
        }
        if (ObjectUtil.isNull(resources.getIsDefault())) {
            resources.setIsDefault(false);
        }
        
        // 如果设置为默认模板，需要将该算法的其他模板设为非默认
        if (resources.getIsDefault()) {
            hyperMapper.updateDefaultStatus(resources.getAlgorithmId(), null);
        }
        
        hyperMapper.insert(resources);
        log.info("用户 {} 创建了新的超参数模板: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getTemplateName(),
                resources.getTemplateId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Hyper resources) {
        Hyper hyper = findById(resources.getTemplateId());

        // 检查模板名称是否已被其他模板使用
        if (isTemplateNameDuplicate(resources.getTemplateName(), resources.getTemplateId())) {
            throw new EntityExistException(Hyper.class, "templateName", resources.getTemplateName());
        }

        // 验证模板参数
        validateTemplate(resources);

        // 如果设置为默认模板，需要将该算法的其他模板设为非默认
        if (resources.getIsDefault() && !hyper.getIsDefault()) {
            hyperMapper.updateDefaultStatus(resources.getAlgorithmId(), resources.getTemplateId());
        }

        hyperMapper.updateById(resources);
        log.info("用户 {} 更新了超参数模板: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getTemplateName(),
                resources.getTemplateId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Set<Long> templateIds) {
        String currentUser = SecurityUtils.getCurrentUsername();
        
        for (Long templateId : templateIds) {
            Hyper hyper = findById(templateId);
            
            // 权限检查：只能删除自己创建的模板
            if (!ObjectUtil.equal(hyper.getCreateBy(), currentUser)) {
                throw new BadRequestException("只能删除自己创建的模板【" + hyper.getTemplateName() + "】");
            }
            
            // 业务检查：默认模板删除前需要确认该算法还有其他可用模板
            if (hyper.getIsDefault()) {
                List<Hyper> enabledTemplates = hyperMapper.findEnabledByAlgorithmId(hyper.getAlgorithmId());
                if (enabledTemplates.size() <= 1) {
                    throw new BadRequestException("算法至少需要保留一个可用模板，无法删除默认模板【" + hyper.getTemplateName() + "】");
                }
            }
            
            long taskCount = preDefenseMapper.countByHyperId(templateId);
            if (taskCount > 0) {
                throw new BadRequestException("超参数模板【" + hyper.getTemplateName()
                        + "】已被 " + taskCount + " 个训练前任务引用，无法删除");
            }
        }

        hyperMapper.deleteBatchIds(templateIds);
        log.info("用户 {} 删除了 {} 个超参数模板", currentUser, templateIds.size());
    }

    @Override
    public Hyper findByTemplateName(String templateName) {
        return hyperMapper.findByTemplateName(templateName);
    }

    @Override
    public PageResult<Hyper> queryAll(HyperQueryCriteria criteria, Page<Object> page) {
        criteria.setOffset(page.offset());
        IPage<Hyper> hyperPage = hyperMapper.findAll(criteria, page);
        return PageUtil.toPage(hyperPage);
    }

    @Override
    public List<Hyper> queryAll(HyperQueryCriteria criteria) {
        return hyperMapper.findAll(criteria);
    }

    @Override
    public List<Hyper> findAllAvailable() {
        return hyperMapper.findByEnabled(true);
    }

    @Override
    public void download(List<Hyper> hypers, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Hyper hyper : hypers) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("模板ID", hyper.getTemplateId());
            map.put("模板名称", hyper.getTemplateName());
            map.put("模板描述", hyper.getDescription());
            map.put("关联算法ID", hyper.getAlgorithmId());
            map.put("启用状态", hyper.getEnabled() ? "启用" : "禁用");
            map.put("是否默认", hyper.getIsDefault() ? "是" : "否");
            map.put("适用数据集类型", hyper.getApplicableDatasets());
            map.put("参数配置", hyper.getParameterConfig());
            map.put("创建时间", hyper.getCreateTime());
            list.add(map);
        }
        FileUtil.downloadExcel(list, response);
    }

    @Override
    public List<Hyper> findByAlgorithmId(Long algorithmId) {
        return hyperMapper.findByAlgorithmId(algorithmId);
    }

    @Override
    public List<Hyper> findEnabledByAlgorithmId(Long algorithmId) {
        return hyperMapper.findEnabledByAlgorithmId(algorithmId);
    }

    @Override
    public Hyper findDefaultByAlgorithmId(Long algorithmId) {
        return hyperMapper.findDefaultByAlgorithmId(algorithmId);
    }

    @Override
    public List<Hyper> findByEnabled(Boolean enabled) {
        return hyperMapper.findByEnabled(enabled);
    }

    @Override
    public List<Hyper> findByCreateBy(String createBy) {
        return hyperMapper.findByCreateBy(createBy);
    }

    @Override
    public List<Hyper> findApplicableTemplates(Long algorithmId, String datasetType) {
        if (ObjectUtil.isEmpty(datasetType)) {
            return findEnabledByAlgorithmId(algorithmId);
        }
        return hyperMapper.findApplicableTemplates(algorithmId, datasetType);
    }

    @Override
    public Map<String, Object> validateParameterConfig(Long templateId, String parameterConfig) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            Hyper hyper = findById(templateId);
            String schema = hyper.getParameterSchema();
            
            // 简单的JSON格式验证
            if (ObjectUtil.isNotEmpty(parameterConfig)) {
                com.alibaba.fastjson2.JSON.parseObject(parameterConfig);
                
                // TODO: 根据schema进行详细的参数验证
                // validateAgainstSchema(parameterConfig, schema);
                
                result.put("valid", true);
                result.put("message", "参数配置格式正确");
            } else {
                result.put("valid", false);
                result.put("message", "参数配置不能为空");
            }
            
        } catch (Exception e) {
            result.put("valid", false);
            result.put("message", "参数配置格式错误: " + e.getMessage());
        }
        
        return result;
    }

    @Override
    public String mergeParameterConfig(Long templateId, String customConfig) {
        Hyper hyper = findById(templateId);
        String defaultConfig = hyper.getParameterConfig();
        
        try {
            if (ObjectUtil.isEmpty(customConfig)) {
                return defaultConfig;
            }
            
            // 解析JSON配置
            com.alibaba.fastjson2.JSONObject defaultJson = com.alibaba.fastjson2.JSON.parseObject(defaultConfig);
            com.alibaba.fastjson2.JSONObject customJson = com.alibaba.fastjson2.JSON.parseObject(customConfig);
            
            // 合并配置：自定义配置覆盖默认配置
            for (String key : customJson.keySet()) {
                defaultJson.put(key, customJson.get(key));
            }
            
            return defaultJson.toJSONString();
        } catch (Exception e) {
            log.warn("合并参数配置失败: {}", e.getMessage());
            return defaultConfig;
        }
    }

    @Override
    public Map<String, Object> getTemplateStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        // 获取按算法分组的统计信息
        List<Map<String, Object>> templateStats = hyperMapper.getTemplateStatistics();
        stats.put("algorithmStats", templateStats);
        
        // 总体统计
        HyperQueryCriteria criteria = new HyperQueryCriteria();
        List<Hyper> allTemplates = hyperMapper.findAll(criteria);
        
        stats.put("totalCount", allTemplates.size());
        stats.put("enabledCount", hyperMapper.findByEnabled(true).size());
        stats.put("disabledCount", hyperMapper.findByEnabled(false).size());
        
        // 按数据集类型统计
        Map<String, Integer> datasetTypeStats = new HashMap<>();
        for (Hyper hyper : allTemplates) {
            String applicableDatasets = hyper.getApplicableDatasets();
            if (ObjectUtil.isNotEmpty(applicableDatasets)) {
                try {
                    com.alibaba.fastjson2.JSONArray types = com.alibaba.fastjson2.JSON.parseArray(applicableDatasets);
                    for (Object type : types) {
                        String typeStr = type.toString();
                        datasetTypeStats.put(typeStr, datasetTypeStats.getOrDefault(typeStr, 0) + 1);
                    }
                } catch (Exception e) {
                    log.warn("解析适用数据集类型失败: {}", e.getMessage());
                }
            }
        }
        stats.put("datasetTypeStats", datasetTypeStats);
        
        return stats;
    }

    @Override
    public void validateTemplate(Hyper resources) {
        // 基础参数验证
        if (ObjectUtil.isEmpty(resources.getTemplateName())) {
            throw new BadRequestException("模板名称不能为空");
        }
        if (ObjectUtil.isNull(resources.getAlgorithmId())) {
            throw new BadRequestException("关联算法ID不能为空");
        }
        
        // 参数配置JSON格式验证
        if (ObjectUtil.isNotEmpty(resources.getParameterConfig())) {
            try {
                com.alibaba.fastjson2.JSON.parseObject(resources.getParameterConfig());
            } catch (Exception e) {
                throw new BadRequestException("参数配置格式错误: " + e.getMessage());
            }
        }
        
        // 参数模式JSON格式验证
        if (ObjectUtil.isNotEmpty(resources.getParameterSchema())) {
            try {
                com.alibaba.fastjson2.JSON.parseObject(resources.getParameterSchema());
            } catch (Exception e) {
                throw new BadRequestException("参数模式格式错误: " + e.getMessage());
            }
        }
        
        // 适用数据集类型JSON数组格式验证
        if (ObjectUtil.isNotEmpty(resources.getApplicableDatasets())) {
            try {
                com.alibaba.fastjson2.JSON.parseArray(resources.getApplicableDatasets());
            } catch (Exception e) {
                throw new BadRequestException("适用数据集类型格式错误: " + e.getMessage());
            }
        }
        
        // TODO: 验证算法ID是否存在
        // Algorithm algorithm = algorithmService.findById(resources.getAlgorithmId());
        // if (algorithm == null) {
        //     throw new BadRequestException("关联的算法不存在");
        // }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefaultTemplate(Long templateId) {
        Hyper hyper = findById(templateId);
        
        if (!hyper.getEnabled()) {
            throw new BadRequestException("只能将启用的模板设为默认模板");
        }
        
        // 将该算法的其他模板设为非默认
        hyperMapper.updateDefaultStatus(hyper.getAlgorithmId(), templateId);
        
        // 设置当前模板为默认
        hyper.setIsDefault(true);
        hyperMapper.updateById(hyper);
        
        log.info("用户 {} 将模板 {} 设为默认模板", SecurityUtils.getCurrentUsername(), hyper.getTemplateName());
    }

    @Override
    public boolean isTemplateNameDuplicate(String templateName, Long excludeId) {
        Hyper existingTemplate = hyperMapper.findByTemplateName(templateName);
        if (existingTemplate == null) {
            return false;
        }
        return excludeId == null || !existingTemplate.getTemplateId().equals(excludeId);
    }
}
