package me.zhengjie.modules.system.rest;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.annotation.Log;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.Dataset;
import me.zhengjie.modules.system.domain.dto.DatasetQueryCriteria;
import me.zhengjie.modules.system.service.DatasetService;
import me.zhengjie.utils.PageResult;
import me.zhengjie.utils.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import cn.hutool.core.util.ObjectUtil;

/**
 * 数据集管理控制器
 * @author ZJING
 * @date 2025-07-30
 */
@Slf4j
@Api(tags = "系统：数据集管理")
@RestController
@RequestMapping("/api/dataset")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService datasetService;
    private static final String ENTITY_NAME = "dataset";

    @ApiOperation("导出数据集数据")
    @GetMapping(value = "/download")
    @PreAuthorize("@el.check('dataset:list')")
    public void exportDataset(HttpServletResponse response, DatasetQueryCriteria criteria) throws IOException {
        datasetService.download(datasetService.queryAll(criteria), response);
    }

    @ApiOperation("查询数据集")
    @GetMapping
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<PageResult<Dataset>> queryDataset(DatasetQueryCriteria criteria) {
        Page<Object> page = new Page<>(criteria.getPage(), criteria.getSize());
        return new ResponseEntity<>(datasetService.queryAll(criteria, page), HttpStatus.OK);
    }

    @ApiOperation("根据ID查询数据集")
    @GetMapping("/{datasetId}")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Dataset> getDataset(@PathVariable Long datasetId) {
        return new ResponseEntity<>(datasetService.findById(datasetId), HttpStatus.OK);
    }

    @Log("新增数据集")
    @ApiOperation("新增数据集")
    @PostMapping
    @PreAuthorize("@el.check('dataset:add')")
    public ResponseEntity<Object> createDataset(@Validated @RequestBody Dataset resources) {
        if (resources.getDatasetId() != null) {
            throw new BadRequestException("A new " + ENTITY_NAME + " cannot already have an ID");
        }
        datasetService.create(resources);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @Log("修改数据集")
    @ApiOperation("修改数据集")
    @PutMapping
    @PreAuthorize("@el.check('dataset:edit')")
    public ResponseEntity<Object> updateDataset(@Validated(Dataset.Update.class) @RequestBody Dataset resources) {
        datasetService.update(resources);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Log("删除数据集")
    @ApiOperation("删除数据集")
    @DeleteMapping
    @PreAuthorize("@el.check('dataset:del')")
    public ResponseEntity<Object> deleteDataset(@RequestBody Set<Long> datasetIds) {
        log.info("deleteDataset called with dataset IDs: {}", datasetIds);
        datasetService.delete(datasetIds);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation("根据数据集名称查询")
    @GetMapping("/name/{datasetName}")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Dataset> getDatasetByName(@PathVariable String datasetName) {
        Dataset dataset = datasetService.findByDatasetName(datasetName);
        if (dataset == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(dataset, HttpStatus.OK);
    }

    @ApiOperation("根据数据集类型查询")
    @GetMapping("/type/{datasetType}")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Object> getDatasetsByType(@PathVariable String datasetType) {
        return new ResponseEntity<>(datasetService.findByDatasetType(datasetType), HttpStatus.OK);
    }

    @ApiOperation("根据启用状态查询")
    @GetMapping("/enabled/{enabled}")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Object> getDatasetsByEnabled(@PathVariable Boolean enabled) {
        return new ResponseEntity<>(datasetService.findByEnabled(enabled), HttpStatus.OK);
    }

    @ApiOperation("根据解析状态查询")
    @GetMapping("/parse-status/{parseStatus}")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Object> getDatasetsByParseStatus(@PathVariable String parseStatus) {
        return new ResponseEntity<>(datasetService.findByParseStatus(parseStatus), HttpStatus.OK);
    }

    @ApiOperation("获取可用数据集")
    @GetMapping("/available")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Object> getAvailableDatasets(@RequestParam(required = false) String datasetType) {
        return new ResponseEntity<>(datasetService.findAvailableDatasets(datasetType), HttpStatus.OK);
    }

    @Log("启动数据集解析")
    @ApiOperation("启动数据集解析")
    @PostMapping("/parse/start/{datasetId}")
    @PreAuthorize("@el.check('dataset:edit')")
    public ResponseEntity<Object> startDatasetParsing(@PathVariable Long datasetId) {
        datasetService.startParsing(datasetId);
        return new ResponseEntity<>("数据集解析启动成功", HttpStatus.OK);
    }

    @Log("更新解析状态")
    @ApiOperation("更新解析状态")
    @PutMapping("/parse/status")
    @PreAuthorize("@el.check('dataset:edit')")
    public ResponseEntity<Object> updateParseStatus(
            @RequestParam Long datasetId,
            @RequestParam String parseStatus,
            @RequestParam(required = false) String parseMessage) {
        datasetService.updateParseStatus(datasetId, parseStatus, parseMessage);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Log("更新解析结果")
    @ApiOperation("更新解析结果")
    @PutMapping("/parse/result")
    @PreAuthorize("@el.check('dataset:edit')")
    public ResponseEntity<Object> updateParseResult(
            @RequestParam Long datasetId,
            @RequestParam(required = false) Integer sampleCount,
            @RequestParam(required = false) Integer featureCount,
            @RequestParam(required = false) Integer classCount) {
        datasetService.updateParseResult(datasetId, sampleCount, featureCount, classCount);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation("验证数据集可用性")
    @GetMapping("/validate/{datasetId}")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Object> validateDataset(@PathVariable Long datasetId) {
        boolean available = datasetService.isDatasetAvailable(datasetId);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("datasetId", datasetId);
        result.put("available", available);
        result.put("message", available ? "数据集可用" : "数据集不可用或未解析成功");
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @ApiOperation("根据文件大小范围查询")
    @GetMapping("/file-size")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Object> getDatasetsByFileSize(
            @RequestParam(required = false) Long minSize,
            @RequestParam(required = false) Long maxSize) {
        return new ResponseEntity<>(datasetService.findByFileSizeRange(minSize, maxSize), HttpStatus.OK);
    }

    @ApiOperation("根据样本数量范围查询")
    @GetMapping("/sample-count")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Object> getDatasetsBySampleCount(
            @RequestParam(required = false) Integer minCount,
            @RequestParam(required = false) Integer maxCount) {
        return new ResponseEntity<>(datasetService.findBySampleCountRange(minCount, maxCount), HttpStatus.OK);
    }

    @ApiOperation("获取数据集统计信息")
    @GetMapping("/statistics")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Object> getDatasetStatistics() {
        return new ResponseEntity<>(datasetService.getDatasetStatistics(), HttpStatus.OK);
    }

    @ApiOperation("批量验证数据集可用性")
    @PostMapping("/validate/batch")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Object> validateBatchDatasets(@RequestBody Set<Long> datasetIds) {
        if (datasetIds == null || datasetIds.isEmpty()) {
            throw new BadRequestException("数据集ID列表不能为空");
        }
        
        java.util.Map<Long, Boolean> validationResults = new java.util.HashMap<>();
        for (Long datasetId : datasetIds) {
            try {
                boolean available = datasetService.isDatasetAvailable(datasetId);
                validationResults.put(datasetId, available);
            } catch (Exception e) {
                log.warn("验证数据集{}可用性失败: {}", datasetId, e.getMessage());
                validationResults.put(datasetId, false);
            }
        }
        
        return new ResponseEntity<>(validationResults, HttpStatus.OK);
    }

    @Log("批量启动解析")
    @ApiOperation("批量启动解析")
    @PostMapping("/parse/start/batch")
    @PreAuthorize("@el.check('dataset:edit')")
    public ResponseEntity<Object> startBatchParsing(@RequestBody Set<Long> datasetIds) {
        if (datasetIds == null || datasetIds.isEmpty()) {
            throw new BadRequestException("数据集ID列表不能为空");
        }
        
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        java.util.List<Long> successIds = new java.util.ArrayList<>();
        java.util.List<String> failureReasons = new java.util.ArrayList<>();
        
        for (Long datasetId : datasetIds) {
            try {
                datasetService.startParsing(datasetId);
                successIds.add(datasetId);
            } catch (Exception e) {
                log.warn("启动数据集{}解析失败: {}", datasetId, e.getMessage());
                failureReasons.add("数据集" + datasetId + ": " + e.getMessage());
            }
        }
        
        result.put("successCount", successIds.size());
        result.put("successIds", successIds);
        result.put("failureCount", failureReasons.size());
        result.put("failureReasons", failureReasons);
        
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @ApiOperation("根据创建人查询")
    @GetMapping("/creator/{createBy}")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Object> getDatasetsByCreator(@PathVariable String createBy) {
        return new ResponseEntity<>(datasetService.findByCreateBy(createBy), HttpStatus.OK);
    }

    @ApiOperation("获取数据集类型列表")
    @GetMapping("/types")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Object> getDatasetTypes() {
        java.util.List<String> types = java.util.Arrays.asList("image", "text", "tabular");
        return new ResponseEntity<>(types, HttpStatus.OK);
    }

    @ApiOperation("获取解析状态列表")
    @GetMapping("/parse-status-list")
    @PreAuthorize("@el.check('dataset:list')")
    public ResponseEntity<Object> getParseStatusList() {
        java.util.List<String> statusList = java.util.Arrays.asList("pending", "parsing", "success", "failed");
        return new ResponseEntity<>(statusList, HttpStatus.OK);
    }

    @Log("上传数据集")
    @ApiOperation("上传数据集文件")
    @PostMapping("/upload")
    @PreAuthorize("@el.check('dataset:add')")
    public ResponseEntity<Object> uploadDataset(
            @RequestParam("file") MultipartFile file,
            @RequestParam("datasetName") String datasetName,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("datasetType") String datasetType) throws IOException {

        if (file.isEmpty()) {
            throw new BadRequestException("上传文件不能为空");
        }

        // 验证文件类型
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || (!originalFilename.endsWith(".zip") &&
                !originalFilename.endsWith(".tar.gz") && !originalFilename.endsWith(".rar"))) {
            throw new BadRequestException("只支持上传 .zip、.tar.gz、.rar 格式的文件");
        }

        // 验证文件大小 (500MB)
        if (file.getSize() > 500 * 1024 * 1024) {
            throw new BadRequestException("文件大小不能超过 500MB");
        }

        try {
            // 创建数据集记录
            Dataset dataset = new Dataset();
            dataset.setDatasetName(datasetName);
            dataset.setDescription(description);
            dataset.setDatasetType(datasetType);
            dataset.setFileName(originalFilename);
            dataset.setFileSize(file.getSize());
            dataset.setEnabled(true);
            dataset.setParseStatus("pending");

            // 保存文件
            String filePath = saveUploadedFile(file, datasetName);
            dataset.setFilePath(filePath);

            String currentUser = SecurityUtils.getCurrentUsername();
            // 保存到数据库
            datasetService.create(dataset);

            log.info("用户 {} 上传数据集: {} (文件: {})", currentUser, datasetName, originalFilename);

            return new ResponseEntity<>("数据集上传成功", HttpStatus.CREATED);

        } catch (Exception e) {
            log.error("数据集上传失败: {}", e.getMessage());
            throw new BadRequestException("数据集上传失败: " + e.getMessage());
        }
    }

//    @ApiOperation("获取所有可用数据集")
//    @GetMapping("/available")
//    @PreAuthorize("@el.check('dataset:list')")
//    public ResponseEntity<Object> getAllAvailableDatasets(
//            @RequestParam(required = false) String datasetType) {
//        return new ResponseEntity<>(datasetService.findAllAvailable(datasetType), HttpStatus.OK);
//    }

    @ApiOperation("下载数据集文件")
    @GetMapping("/download/{datasetId}")
    @PreAuthorize("@el.check('dataset:list')")
    public void downloadDataset(@PathVariable Long datasetId, HttpServletResponse response) throws IOException {
        Dataset dataset = datasetService.findById(datasetId);

        if (dataset == null) {
            throw new BadRequestException("数据集不存在");
        }

        String filePath = dataset.getFilePath();
        if (ObjectUtil.isEmpty(filePath)) {
            throw new BadRequestException("数据集文件不存在");
        }

        File file = new File(filePath);
        if (!file.exists()) {
            throw new BadRequestException("数据集文件已丢失");
        }

        // 设置响应头
        response.setContentType("application/octet-stream");
        response.setCharacterEncoding("UTF-8");
        String fileName = dataset.getFileName();
        if (ObjectUtil.isEmpty(fileName)) {
            fileName = "dataset_" + datasetId + ".zip";
        }

        // 处理文件名编码
        fileName = new String(fileName.getBytes("UTF-8"), "ISO-8859-1");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        response.setHeader("Content-Length", String.valueOf(file.length()));

        // 输出文件
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis);
             OutputStream os = response.getOutputStream()) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();

            log.info("用户 {} 下载数据集: {}", SecurityUtils.getCurrentUsername(), dataset.getDatasetName());

        } catch (IOException e) {
            log.error("数据集下载失败: {}", e.getMessage());
            throw new BadRequestException("数据集下载失败");
        }
    }

    /**
     * 保存上传的文件
     */
    private String saveUploadedFile(MultipartFile file, String datasetName) throws IOException {
        // 创建上传目录
        String uploadDir = System.getProperty("user.dir") + File.separator + "uploads" + File.separator + "datasets";
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 生成唯一文件名
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        String uniqueFileName = datasetName + "_" + System.currentTimeMillis() + extension;

        // 保存文件
        String filePath = uploadDir + File.separator + uniqueFileName;
        File destFile = new File(filePath);
        file.transferTo(destFile);

        return filePath;
    }
}