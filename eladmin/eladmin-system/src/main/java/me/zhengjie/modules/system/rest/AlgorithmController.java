package me.zhengjie.modules.system.rest;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.annotation.Log;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.Algorithm;
import me.zhengjie.modules.system.domain.dto.AlgorithmQueryCriteria;
import me.zhengjie.modules.system.service.AlgorithmService;
import me.zhengjie.utils.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
// JSON处理相关
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

// 工具类
import cn.hutool.core.util.ObjectUtil;

// 集合工具类
import java.util.Arrays;


/**
 * 算法管理控制器
 * @author ZJING
 * @date 2025-07-30
 */
@Slf4j
@Api(tags = "系统：算法管理")
@RestController
@RequestMapping("/api/algorithm")
@RequiredArgsConstructor
public class AlgorithmController {

    private final AlgorithmService algorithmService;
    private static final String ENTITY_NAME = "algorithm";

    @ApiOperation("导出算法数据")
    @GetMapping(value = "/download")
    @PreAuthorize("@el.check('algorithm:list')")
    public void exportAlgorithm(HttpServletResponse response, AlgorithmQueryCriteria criteria) throws IOException {
        algorithmService.download(algorithmService.queryAll(criteria), response);
    }

    @ApiOperation("查询算法")
    @GetMapping
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<PageResult<Algorithm>> queryAlgorithm(AlgorithmQueryCriteria criteria) {
        Page<Object> page = new Page<>(criteria.getPage(), criteria.getSize());
        return new ResponseEntity<>(algorithmService.queryAll(criteria, page), HttpStatus.OK);
    }

    @ApiOperation("根据ID查询算法")
    @GetMapping("/{algorithmId}")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Algorithm> getAlgorithm(@PathVariable Long algorithmId) {
        return new ResponseEntity<>(algorithmService.findById(algorithmId), HttpStatus.OK);
    }

    @Log("新增算法")
    @ApiOperation("新增算法")
    @PostMapping
    @PreAuthorize("@el.check('algorithm:add')")
    public ResponseEntity<Object> createAlgorithm(@Validated @RequestBody Algorithm resources) {
        if (resources.getAlgorithmId() != null) {
            throw new BadRequestException("A new " + ENTITY_NAME + " cannot already have an ID");
        }
        algorithmService.create(resources);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @Log("修改算法")
    @ApiOperation("修改算法")
    @PutMapping
    @PreAuthorize("@el.check('algorithm:edit')")
    public ResponseEntity<Object> updateAlgorithm(@Validated(Algorithm.Update.class) @RequestBody Algorithm resources) {
        algorithmService.update(resources);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Log("删除算法")
    @ApiOperation("删除算法")
    @DeleteMapping
    @PreAuthorize("@el.check('algorithm:del')")
    public ResponseEntity<Object> deleteAlgorithm(@RequestBody Set<Long> algorithmIds) {
        log.info("deleteAlgorithm called with algorithm IDs: {}", algorithmIds);
        algorithmService.delete(algorithmIds);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation("根据算法名称查询")
    @GetMapping("/name/{algorithmName}")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Algorithm> getAlgorithmByName(@PathVariable String algorithmName) {
        Algorithm algorithm = algorithmService.findByAlgorithmName(algorithmName);
        if (algorithm == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(algorithm, HttpStatus.OK);
    }

    @ApiOperation("根据算法类型查询")
    @GetMapping("/type/{algorithmType}")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getAlgorithmsByType(@PathVariable String algorithmType) {
        return new ResponseEntity<>(algorithmService.findByAlgorithmType(algorithmType), HttpStatus.OK);
    }

    @ApiOperation("根据防御阶段查询")
    @GetMapping("/stage/{defenseStage}")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getAlgorithmsByStage(@PathVariable String defenseStage) {
        return new ResponseEntity<>(algorithmService.findByDefenseStage(defenseStage), HttpStatus.OK);
    }

    @ApiOperation("根据算法分类查询")
    @GetMapping("/category/{algorithmCategory}")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getAlgorithmsByCategory(@PathVariable String algorithmCategory) {
        return new ResponseEntity<>(algorithmService.findByAlgorithmCategory(algorithmCategory), HttpStatus.OK);
    }

    @ApiOperation("根据启用状态查询")
    @GetMapping("/enabled/{enabled}")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getAlgorithmsByEnabled(@PathVariable Boolean enabled) {
        return new ResponseEntity<>(algorithmService.findByEnabled(enabled), HttpStatus.OK);
    }

    @ApiOperation("获取可用算法")
    @GetMapping("/available")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getAvailableAlgorithms(
            @RequestParam(required = false) String defenseStage,
            @RequestParam(required = false) String algorithmType) {
        return new ResponseEntity<>(algorithmService.findAvailableAlgorithms(defenseStage, algorithmType), HttpStatus.OK);
    }

    @Log("更新默认配置")
    @ApiOperation("更新算法默认配置")
    @PutMapping("/config/default")
    @PreAuthorize("@el.check('algorithm:edit')")
    public ResponseEntity<Object> updateDefaultConfig(
            @RequestParam Long algorithmId,
            @RequestBody String defaultConfig) {
        algorithmService.updateDefaultConfig(algorithmId, defaultConfig);
        return new ResponseEntity<>(HttpStatus.OK);
    }

//    @Log("更新配置模式")
//    @ApiOperation("更新算法配置模式")
//    @PutMapping("/config/schema")
//    @PreAuthorize("@el.check('algorithm:edit')")
//    public ResponseEntity<Object> updateConfigSchema(
//            @RequestParam Long algorithmId,
//            @RequestBody String configSchema) {
//        algorithmService.updateConfigSchema(algorithmId, configSchema);
//        return new ResponseEntity<>(HttpStatus.OK);
//    }

    @Log("更新配置模式")
    @ApiOperation("更新算法配置模式")
    @PutMapping("/config/schema")
    @PreAuthorize("@el.check('algorithm:edit')")
    public ResponseEntity<Object> updateConfigSchema(
            @RequestParam Long algorithmId,
            @RequestBody String configSchema) {

        // 1. 验证算法是否存在
        Algorithm algorithm = algorithmService.findById(algorithmId);

        // 2. 验证configSchema JSON格式
        try {
            if (ObjectUtil.isNotEmpty(configSchema)) {
                JSONObject jsonObject = JSON.parseObject(configSchema);
                // 验证JSON结构的合理性
                validateConfigSchemaStructure(jsonObject);
            }
        } catch (Exception e) {
            throw new BadRequestException("配置模式JSON格式错误: " + e.getMessage());
        }

        // 3. 更新配置模式
        algorithmService.updateConfigSchema(algorithmId, configSchema);

        // 4. 返回更新结果
        Map<String, Object> result = new HashMap<>();
        result.put("algorithmId", algorithmId);
        result.put("algorithmName", algorithm.getAlgorithmName());
        result.put("message", "配置模式更新成功");

        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    /**
     * 验证配置模式JSON结构
     */
    private void validateConfigSchemaStructure(JSONObject configSchema) {
        for (String key : configSchema.keySet()) {
            JSONObject param = configSchema.getJSONObject(key);
            if (param == null) {
                throw new BadRequestException("参数 " + key + " 配置格式错误");
            }

            // 验证必需字段
            if (!param.containsKey("type")) {
                throw new BadRequestException("参数 " + key + " 缺少type字段");
            }

            String type = param.getString("type");
            if (!Arrays.asList("float", "int", "select", "boolean").contains(type)) {
                throw new BadRequestException("参数 " + key + " 的type值无效: " + type);
            }

            // 验证select类型必须有options
            if ("select".equals(type) && !param.containsKey("options")) {
                throw new BadRequestException("select类型参数 " + key + " 缺少options字段");
            }

            // 验证数值类型的范围
            if (("float".equals(type) || "int".equals(type)) && param.containsKey("min") && param.containsKey("max")) {
                Number min = param.getBigDecimal("min");
                Number max = param.getBigDecimal("max");
                if (min.doubleValue() >= max.doubleValue()) {
                    throw new BadRequestException("参数 " + key + " 的min值不能大于等于max值");
                }
            }
        }
    }

    @ApiOperation("获取算法配置模式")
    @GetMapping("/{algorithmId}/config-schema")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getAlgorithmConfigSchema(@PathVariable Long algorithmId) {
        Algorithm algorithm = algorithmService.findById(algorithmId);

        Map<String, Object> result = new HashMap<>();
        result.put("algorithmId", algorithm.getAlgorithmId());
        result.put("algorithmName", algorithm.getAlgorithmName());
        result.put("algorithmType", algorithm.getAlgorithmType());
        result.put("configSchema", algorithm.getConfigSchema());
        result.put("defaultConfig", algorithm.getDefaultConfig());

        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @ApiOperation("获取算法统计信息")
    @GetMapping("/statistics")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getAlgorithmStatistics() {
        return new ResponseEntity<>(algorithmService.getAlgorithmStatistics(), HttpStatus.OK);
    }

    @ApiOperation("根据创建人查询")
    @GetMapping("/creator/{createBy}")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getAlgorithmsByCreator(@PathVariable String createBy) {
        return new ResponseEntity<>(algorithmService.findByCreateBy(createBy), HttpStatus.OK);
    }

    @ApiOperation("获取防御阶段列表")
    @GetMapping("/defense-stages")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getDefenseStages() {
        java.util.List<String> stages = java.util.Arrays.asList("pre", "during", "post");
        return new ResponseEntity<>(stages, HttpStatus.OK);
    }

    @ApiOperation("获取算法类型列表")
    @GetMapping("/algorithm-types")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getAlgorithmTypes() {
        java.util.List<String> types = java.util.Arrays.asList(
            "data_cleaning", "backdoor_detection", "model_purification"
        );
        return new ResponseEntity<>(types, HttpStatus.OK);
    }

    @ApiOperation("获取算法分类列表")
    @GetMapping("/categories")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getAlgorithmCategories() {
        java.util.List<String> categories = java.util.Arrays.asList(
            "statistical", "ml_based", "deep_learning"
        );
        return new ResponseEntity<>(categories, HttpStatus.OK);
    }

    @ApiOperation("批量获取算法配置")
    @PostMapping("/config/batch")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getBatchAlgorithmConfig(@RequestBody Set<Long> algorithmIds) {
        if (algorithmIds == null || algorithmIds.isEmpty()) {
            throw new BadRequestException("算法ID列表不能为空");
        }
        
        java.util.Map<Long, java.util.Map<String, Object>> configMap = new java.util.HashMap<>();
        for (Long algorithmId : algorithmIds) {
            try {
                Algorithm algorithm = algorithmService.findById(algorithmId);
                java.util.Map<String, Object> config = new java.util.HashMap<>();
                config.put("algorithmName", algorithm.getAlgorithmName());
                config.put("defaultConfig", algorithm.getDefaultConfig());
                config.put("configSchema", algorithm.getConfigSchema());
                configMap.put(algorithmId, config);
            } catch (Exception e) {
                log.warn("获取算法{}配置失败: {}", algorithmId, e.getMessage());
                java.util.Map<String, Object> errorConfig = new java.util.HashMap<>();
                errorConfig.put("error", e.getMessage());
                configMap.put(algorithmId, errorConfig);
            }
        }
        
        return new ResponseEntity<>(configMap, HttpStatus.OK);
    }

    @ApiOperation("验证算法配置JSON")
    @PostMapping("/config/validate")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> validateAlgorithmConfig(
            @RequestParam Long algorithmId,
            @RequestBody String customConfig) {
        
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        
        try {
            Algorithm algorithm = algorithmService.findById(algorithmId);
            
            // 简单的JSON格式验证
            if (customConfig != null && !customConfig.trim().isEmpty()) {
                com.alibaba.fastjson2.JSON.parseObject(customConfig);
                result.put("valid", true);
                result.put("message", "配置格式正确");
            } else {
                result.put("valid", false);
                result.put("message", "配置不能为空");
            }
            
        } catch (Exception e) {
            result.put("valid", false);
            result.put("message", "配置格式错误: " + e.getMessage());
        }
        
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @ApiOperation("获取算法详细配置信息")
    @GetMapping("/{algorithmId}/config")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getAlgorithmConfig(@PathVariable Long algorithmId) {
        Algorithm algorithm = algorithmService.findById(algorithmId);
        
        java.util.Map<String, Object> config = new java.util.HashMap<>();
        config.put("algorithmId", algorithm.getAlgorithmId());
        config.put("algorithmName", algorithm.getAlgorithmName());
        config.put("algorithmType", algorithm.getAlgorithmType());
        config.put("defenseStage", algorithm.getDefenseStage());
        config.put("defaultConfig", algorithm.getDefaultConfig());
        config.put("configSchema", algorithm.getConfigSchema());
        config.put("enabled", algorithm.getEnabled());
        
        return new ResponseEntity<>(config, HttpStatus.OK);
    }

    @ApiOperation("按防御阶段分组获取算法")
    @GetMapping("/grouped-by-stage")
    @PreAuthorize("@el.check('algorithm:list')")
    public ResponseEntity<Object> getAlgorithmsGroupedByStage() {
        java.util.Map<String, java.util.List<Algorithm>> groupedAlgorithms = new java.util.HashMap<>();
        
        // 按防御阶段分组
        java.util.List<String> stages = java.util.Arrays.asList("pre", "during", "post");
        for (String stage : stages) {
            java.util.List<Algorithm> algorithms = algorithmService.findByDefenseStage(stage);
            groupedAlgorithms.put(stage, algorithms);
        }
        
        return new ResponseEntity<>(groupedAlgorithms, HttpStatus.OK);
    }
}