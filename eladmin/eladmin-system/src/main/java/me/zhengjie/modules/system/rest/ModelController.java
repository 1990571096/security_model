package me.zhengjie.modules.system.rest;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import me.zhengjie.annotation.Log;
import me.zhengjie.modules.system.domain.Model;
import me.zhengjie.modules.system.domain.dto.ModelQueryCriteria;
import me.zhengjie.modules.system.service.ModelService;
import me.zhengjie.utils.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;

/**
 * 模型管理控制器
 * @author ZJING
 * @date 2025-07-27
 */
@Slf4j
@Api(tags = "系统：模型管理")
@RestController
@RequestMapping("/api/model")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    @ApiOperation("导出模型数据")
    @GetMapping(value = "/download")
    @PreAuthorize("@el.check('model:list')")
    public void exportModel(HttpServletResponse response, ModelQueryCriteria criteria) throws IOException {
        modelService.download(modelService.queryAll(criteria), response);
    }

    @ApiOperation("查询模型")
    @GetMapping
    @PreAuthorize("@el.check('model:list')")
    public ResponseEntity<PageResult<Model>> queryModel(ModelQueryCriteria criteria) {
        Page<Object> page = new Page<>(criteria.getPage(), criteria.getSize());
        return new ResponseEntity<>(modelService.queryAll(criteria, page), HttpStatus.OK);
    }

    @ApiOperation("根据ID查询模型")
    @GetMapping("/{id}")
    @PreAuthorize("@el.check('model:list')")
    public ResponseEntity<Model> getModel(@PathVariable Long id) {
        return new ResponseEntity<>(modelService.findById(id), HttpStatus.OK);
    }

    @Log("新增模型")
    @ApiOperation("新增模型")
    @PostMapping
    @PreAuthorize("@el.check('model:add')")
    public ResponseEntity<Object> createModel(@Validated @RequestBody Model resources) {
        modelService.create(resources);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @Log("修改模型")
    @ApiOperation("修改模型")
    @PutMapping
    @PreAuthorize("@el.check('model:edit')")
    public ResponseEntity<Object> updateModel(@Validated(Model.Update.class) @RequestBody Model resources) {
        modelService.update(resources);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Log("删除模型")
    @ApiOperation("删除模型")
    @DeleteMapping
    @PreAuthorize("@el.check('model:del')")
    public ResponseEntity<Object> deleteModel(@RequestBody Set<Long> ids) {
        // 添加日志记录接收到的ID (排查使用)
        log.info("deleteModel called with IDs: {}", ids);
        modelService.delete(ids);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Log("更新模型解析状态")
    @ApiOperation("更新模型解析状态")
    @PutMapping("/parse-status")
    @PreAuthorize("@el.check('model:edit')")
    public ResponseEntity<Object> updateParseStatus(
            @RequestParam Long modelId,
            @RequestParam String parseStatus,
            @RequestParam(required = false) String parseMessage) {
        modelService.updateParseStatus(modelId, parseStatus, parseMessage);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Log("更新模型状态")
    @ApiOperation("更新模型状态")
    @PutMapping("/status")
    @PreAuthorize("@el.check('model:edit')")
    public ResponseEntity<Object> updateStatus(
            @RequestParam Long modelId,
            @RequestParam String status) {
        modelService.updateStatus(modelId, status);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation("根据模型来源查询")
    @GetMapping("/source/{modelSource}")
    @PreAuthorize("@el.check('model:list')")
    public ResponseEntity<Object> getModelsBySource(@PathVariable String modelSource) {
        return new ResponseEntity<>(modelService.findByModelSource(modelSource), HttpStatus.OK);
    }

    @ApiOperation("根据创建人查询")
    @GetMapping("/creator/{createBy}")
    @PreAuthorize("@el.check('model:list')")
    public ResponseEntity<Object> getModelsByCreator(@PathVariable String createBy) {
        return new ResponseEntity<>(modelService.findByCreateBy(createBy), HttpStatus.OK);
    }
}