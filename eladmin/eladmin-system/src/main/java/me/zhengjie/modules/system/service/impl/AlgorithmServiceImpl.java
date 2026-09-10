package me.zhengjie.modules.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.exception.EntityExistException;
import me.zhengjie.modules.system.domain.Algorithm;
import me.zhengjie.modules.system.domain.dto.AlgorithmQueryCriteria;
import me.zhengjie.modules.system.mapper.AlgorithmMapper;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
import me.zhengjie.modules.system.service.AlgorithmService;
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
 * 算法管理服务实现
 * @author ZJING
 * @date 2025-07-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlgorithmServiceImpl extends ServiceImpl<AlgorithmMapper, Algorithm> implements AlgorithmService {

    private final AlgorithmMapper algorithmMapper;
    private final PreDefenseMapper preDefenseMapper;

    @Override
    public Algorithm findById(Long algorithmId) {
        Algorithm algorithm = algorithmMapper.selectById(algorithmId);
        if (ObjectUtil.isNull(algorithm)) {
            throw new BadRequestException("算法不存在");
        }
        return algorithm;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(Algorithm resources) {
        // 检查算法名称是否已存在
        if (algorithmMapper.findByAlgorithmName(resources.getAlgorithmName()) != null) {
            throw new EntityExistException(Algorithm.class, "algorithmName", resources.getAlgorithmName());
        }
        
        // 验证算法参数
        validateAlgorithm(resources);
        
        // 设置默认值
        if (ObjectUtil.isNull(resources.getEnabled())) {
            resources.setEnabled(true);
        }
        
        algorithmMapper.insert(resources);
        log.info("用户 {} 创建了新算法: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getAlgorithmName(),
                resources.getAlgorithmId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Algorithm resources) {
        Algorithm algorithm = findById(resources.getAlgorithmId());

        // 检查算法名称是否已被其他算法使用
        Algorithm algorithmByName = algorithmMapper.findByAlgorithmName(resources.getAlgorithmName());
        if (algorithmByName != null && !algorithmByName.getAlgorithmId().equals(resources.getAlgorithmId())) {
            throw new EntityExistException(Algorithm.class, "algorithmName", resources.getAlgorithmName());
        }

        // 验证算法参数
        validateAlgorithm(resources);

        algorithmMapper.updateById(resources);
        log.info("用户 {} 更新了算法: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getAlgorithmName(),
                resources.getAlgorithmId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Set<Long> algorithmIds) {
        String currentUser = SecurityUtils.getCurrentUsername();
        
        for (Long algorithmId : algorithmIds) {
            Algorithm algorithm = findById(algorithmId);
            
            // 权限检查：只能删除自己创建的算法
            if (!ObjectUtil.equal(algorithm.getCreateBy(), currentUser)) {
                throw new BadRequestException("只能删除自己创建的算法【" + algorithm.getAlgorithmName() + "】");
            }
            
            long taskCount = preDefenseMapper.countByAlgorithmId(algorithmId);
            if (taskCount > 0) {
                throw new BadRequestException("算法【" + algorithm.getAlgorithmName()
                        + "】已被 " + taskCount + " 个训练前任务引用，无法删除");
            }
        }

        algorithmMapper.deleteBatchIds(algorithmIds);
        log.info("用户 {} 删除了 {} 个算法", currentUser, algorithmIds.size());
    }

    @Override
    public Algorithm findByAlgorithmName(String algorithmName) {
        return algorithmMapper.findByAlgorithmName(algorithmName);
    }

    @Override
    public PageResult<Algorithm> queryAll(AlgorithmQueryCriteria criteria, Page<Object> page) {
        criteria.setOffset(page.offset());
        IPage<Algorithm> algorithmPage = algorithmMapper.findAll(criteria, page);
        return PageUtil.toPage(algorithmPage);
    }

    @Override
    public List<Algorithm> queryAll(AlgorithmQueryCriteria criteria) {
        return algorithmMapper.findAll(criteria);
    }

    @Override
    public void download(List<Algorithm> algorithms, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Algorithm algorithm : algorithms) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("算法ID", algorithm.getAlgorithmId());
            map.put("算法名称", algorithm.getAlgorithmName());
            map.put("算法描述", algorithm.getDescription());
            map.put("算法类型", algorithm.getAlgorithmType());
            map.put("防御阶段", algorithm.getDefenseStage());
            map.put("算法分类", algorithm.getAlgorithmCategory());
            map.put("启用状态", algorithm.getEnabled() ? "启用" : "禁用");
            map.put("实现路径", algorithm.getImplementationPath());
            map.put("创建时间", algorithm.getCreateTime());
            list.add(map);
        }
        FileUtil.downloadExcel(list, response);
    }

    @Override
    public List<Algorithm> findByAlgorithmType(String algorithmType) {
        return algorithmMapper.findByAlgorithmType(algorithmType);
    }

    @Override
    public List<Algorithm> findByDefenseStage(String defenseStage) {
        return algorithmMapper.findByDefenseStage(defenseStage);
    }

    @Override
    public List<Algorithm> findByAlgorithmCategory(String algorithmCategory) {
        return algorithmMapper.findByAlgorithmCategory(algorithmCategory);
    }

    @Override
    public List<Algorithm> findByEnabled(Boolean enabled) {
        return algorithmMapper.findByEnabled(enabled);
    }

    @Override
    public List<Algorithm> findAvailableAlgorithms(String defenseStage, String algorithmType) {
        return algorithmMapper.findAvailableAlgorithms(defenseStage, algorithmType);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDefaultConfig(Long algorithmId, String defaultConfig) {
        algorithmMapper.updateDefaultConfig(algorithmId, defaultConfig);
        log.info("更新算法 {} 的默认配置", algorithmId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfigSchema(Long algorithmId, String configSchema) {
        algorithmMapper.updateConfigSchema(algorithmId, configSchema);
        log.info("更新算法 {} 的配置模式", algorithmId);
    }

    @Override
    public Map<String, Object> getAlgorithmStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        // 按防御阶段统计
        List<Map<String, Object>> defenseStageStats = algorithmMapper.getDefenseStageStatistics();
        Map<String, Long> stageMap = new HashMap<>();
        for (Map<String, Object> stat : defenseStageStats) {
            stageMap.put((String) stat.get("stage"), ((Number) stat.get("count")).longValue());
        }
        stats.put("defenseStageStats", stageMap);
        
        // 按算法类型统计
        List<Map<String, Object>> algorithmTypeStats = algorithmMapper.getAlgorithmTypeStatistics();
        Map<String, Long> typeMap = new HashMap<>();
        for (Map<String, Object> stat : algorithmTypeStats) {
            typeMap.put((String) stat.get("type"), ((Number) stat.get("count")).longValue());
        }
        stats.put("algorithmTypeStats", typeMap);
        
        // 总数统计
        AlgorithmQueryCriteria criteria = new AlgorithmQueryCriteria();
        stats.put("totalCount", algorithmMapper.findAll(criteria).size());
        stats.put("enabledCount", algorithmMapper.findByEnabled(true).size());
        stats.put("disabledCount", algorithmMapper.findByEnabled(false).size());
        
        return stats;
    }

    @Override
    public void validateAlgorithm(Algorithm resources) {
        // 基础参数验证
        if (ObjectUtil.isEmpty(resources.getAlgorithmName())) {
            throw new BadRequestException("算法名称不能为空");
        }
        if (ObjectUtil.isEmpty(resources.getAlgorithmType())) {
            throw new BadRequestException("算法类型不能为空");
        }
        if (ObjectUtil.isEmpty(resources.getDefenseStage())) {
            throw new BadRequestException("防御阶段不能为空");
        }
        
        // 防御阶段验证
        List<String> validStages = Arrays.asList("pre", "during", "post");
        if (!validStages.contains(resources.getDefenseStage())) {
            throw new BadRequestException("防御阶段必须是: " + String.join(", ", validStages));
        }
        
        // 算法类型验证
        List<String> validTypes = Arrays.asList("data_cleaning", "backdoor_detection", "model_purification");
        if (!validTypes.contains(resources.getAlgorithmType())) {
            throw new BadRequestException("算法类型必须是: " + String.join(", ", validTypes));
        }
        
        // 算法分类验证（可选）
        if (ObjectUtil.isNotEmpty(resources.getAlgorithmCategory())) {
            List<String> validCategories = Arrays.asList("statistical", "ml_based", "deep_learning");
            if (!validCategories.contains(resources.getAlgorithmCategory())) {
                throw new BadRequestException("算法分类必须是: " + String.join(", ", validCategories));
            }
        }
    }

    @Override
    public List<Algorithm> findByCreateBy(String createBy) {
        return algorithmMapper.findByCreateBy(createBy);
    }
}
