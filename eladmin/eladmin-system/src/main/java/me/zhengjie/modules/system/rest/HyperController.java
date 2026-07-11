package me.zhengjie.modules.system.rest;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.annotation.Log;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.Hyper;
import me.zhengjie.modules.system.domain.dto.HyperQueryCriteria;
import me.zhengjie.modules.system.service.HyperService;
import me.zhengjie.utils.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;

/**
 * 超参数模板管理控制器
 * @author ZJING
 * @date 2025-08-02
 */
@Slf4j
@Api(tags = "系统：超参数模板管理")
@RestController
@RequestMapping("/api/hyper")
@RequiredArgsConstructor
public class HyperController {

    private final HyperService hyperService;
    private static final String ENTITY_NAME = "hyper";

    @ApiOperation("导出超参数模板数据")
    @GetMapping(value = "/download")
    @PreAuthorize("@el.check('hyper:list')")
    public void exportHyper(HttpServletResponse response, HyperQueryCriteria criteria) throws IOException {
        hyperService.download(hyperService.queryAll(criteria), response);
    }

    @ApiOperation("查询超参数模板")
    @GetMapping
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<PageResult<Hyper>> queryHyper(HyperQueryCriteria criteria) {
        Page<Object> page = new Page<>(criteria.getPage(), criteria.getSize());
        return new ResponseEntity<>(hyperService.queryAll(criteria, page), HttpStatus.OK);
    }

    @ApiOperation("获取所有可用超参数模板")
    @GetMapping("/available")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Object> getAllAvailableTemplates() {
        return new ResponseEntity<>(hyperService.findAllAvailable(), HttpStatus.OK);
    }

    @ApiOperation("根据ID查询超参数模板")
    @GetMapping("/{templateId}")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Hyper> getHyper(@PathVariable Long templateId) {
        return new ResponseEntity<>(hyperService.findById(templateId), HttpStatus.OK);
    }

    @Log("新增超参数模板")
    @ApiOperation("新增超参数模板")
    @PostMapping
    @PreAuthorize("@el.check('hyper:add')")
    public ResponseEntity<Object> createHyper(@Validated @RequestBody Hyper resources) {
        if (resources.getTemplateId() != null) {
            throw new BadRequestException("A new " + ENTITY_NAME + " cannot already have an ID");
        }
        hyperService.create(resources);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @Log("修改超参数模板")
    @ApiOperation("修改超参数模板")
    @PutMapping
    @PreAuthorize("@el.check('hyper:edit')")
    public ResponseEntity<Object> updateHyper(@Validated(Hyper.Update.class) @RequestBody Hyper resources) {
        hyperService.update(resources);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Log("删除超参数模板")
    @ApiOperation("删除超参数模板")
    @DeleteMapping
    @PreAuthorize("@el.check('hyper:del')")
    public ResponseEntity<Object> deleteHyper(@RequestBody Set<Long> templateIds) {
        log.info("deleteHyper called with template IDs: {}", templateIds);
        hyperService.delete(templateIds);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation("根据模板名称查询")
    @GetMapping("/name/{templateName}")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Hyper> getHyperByName(@PathVariable String templateName) {
        Hyper hyper = hyperService.findByTemplateName(templateName);
        if (hyper == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(hyper, HttpStatus.OK);
    }

    @ApiOperation("根据算法ID查询模板")
    @GetMapping("/algorithm/{algorithmId}")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Object> getTemplatesByAlgorithm(@PathVariable Long algorithmId) {
        return new ResponseEntity<>(hyperService.findByAlgorithmId(algorithmId), HttpStatus.OK);
    }

    @ApiOperation("根据算法ID查询启用的模板")
    @GetMapping("/algorithm/{algorithmId}/enabled")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Object> getEnabledTemplatesByAlgorithm(@PathVariable Long algorithmId) {
        return new ResponseEntity<>(hyperService.findEnabledByAlgorithmId(algorithmId), HttpStatus.OK);
    }

    @ApiOperation("根据算法ID获取默认模板")
    @GetMapping("/algorithm/{algorithmId}/default")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Hyper> getDefaultTemplateByAlgorithm(@PathVariable Long algorithmId) {
        Hyper hyper = hyperService.findDefaultByAlgorithmId(algorithmId);
        if (hyper == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(hyper, HttpStatus.OK);
    }

    @ApiOperation("根据启用状态查询")
    @GetMapping("/enabled/{enabled}")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Object> getTemplatesByEnabled(@PathVariable Boolean enabled) {
        return new ResponseEntity<>(hyperService.findByEnabled(enabled), HttpStatus.OK);
    }

    @ApiOperation("获取适用的模板")
    @GetMapping("/applicable")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Object> getApplicableTemplates(
            @RequestParam Long algorithmId,
            @RequestParam(required = false) String datasetType) {
        return new ResponseEntity<>(hyperService.findApplicableTemplates(algorithmId, datasetType), HttpStatus.OK);
    }

    @Log("验证参数配置")
    @ApiOperation("验证参数配置")
    @PostMapping("/validate")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Object> validateParameterConfig(
            @RequestParam Long templateId,
            @RequestBody String parameterConfig) {
        
        java.util.Map<String, Object> result = hyperService.validateParameterConfig(templateId, parameterConfig);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @ApiOperation("获取参数配置模式")
    @GetMapping("/{templateId}/schema")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Object> getParameterSchema(@PathVariable Long templateId) {
        Hyper hyper = hyperService.findById(templateId);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("templateId", templateId);
        result.put("templateName", hyper.getTemplateName());
        result.put("parameterSchema", hyper.getParameterSchema());
        result.put("parameterConfig", hyper.getParameterConfig());
        
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @ApiOperation("获取模板统计信息")
    @GetMapping("/statistics")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Object> getTemplateStatistics() {
        return new ResponseEntity<>(hyperService.getTemplateStatistics(), HttpStatus.OK);
    }

    @ApiOperation("根据创建人查询")
    @GetMapping("/creator/{createBy}")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Object> getTemplatesByCreator(@PathVariable String createBy) {
        return new ResponseEntity<>(hyperService.findByCreateBy(createBy), HttpStatus.OK);
    }

    @Log("合并参数配置")
    @ApiOperation("合并参数配置")
    @PostMapping("/merge")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Object> mergeParameterConfig(
            @RequestParam Long templateId,
            @RequestBody String customConfig) {
        
        String mergedConfig = hyperService.mergeParameterConfig(templateId, customConfig);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("mergedConfig", mergedConfig);
        
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @ApiOperation("批量获取模板配置")
    @PostMapping("/config/batch")
    @PreAuthorize("@el.check('hyper:list')")
    public ResponseEntity<Object> getBatchTemplateConfig(@RequestBody Set<Long> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) {
            throw new BadRequestException("模板ID列表不能为空");
        }
        
        java.util.Map<Long, java.util.Map<String, Object>> configMap = new java.util.HashMap<>();
        for (Long templateId : templateIds) {
            try {
                Hyper hyper = hyperService.findById(templateId);
                java.util.Map<String, Object> config = new java.util.HashMap<>();
                config.put("templateName", hyper.getTemplateName());
                config.put("parameterConfig", hyper.getParameterConfig());
                config.put("parameterSchema", hyper.getParameterSchema());
                config.put("algorithmId", hyper.getAlgorithmId());
                configMap.put(templateId, config);
            } catch (Exception e) {
                log.warn("获取模板{}配置失败: {}", templateId, e.getMessage());
                java.util.Map<String, Object> errorConfig = new java.util.HashMap<>();
                errorConfig.put("error", e.getMessage());
                configMap.put(templateId, errorConfig);
            }
        }
        
        return new ResponseEntity<>(configMap, HttpStatus.OK);
    }
}