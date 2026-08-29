package me.zhengjie.modules.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.service.CleanSubsetExportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Delegates the dataset reconstruction to the Python runtime, keeping CIFAR decoding aligned with VaB.
 */
@Slf4j
@Service
public class CleanSubsetExportServiceImpl implements CleanSubsetExportService {

    private static final Pattern SELECTION_RATIO = Pattern.compile("selected_indices_(\\d+)\\.npy$");

    @Value("${security-model.executor.python.executable:python}")
    private String pythonExecutable;

    @Value("${security-model.executor.python.script:}")
    private String pythonScript;

    @Value("${security-model.executor.python.timeout-seconds:3600}")
    private long timeoutSeconds;

    @Override
    public Path export(PreDefense task, Integer requestedRatio) throws IOException {
        ExportContext context = resolveContext(task, requestedRatio);
        Path exportDir = context.artifactRoot.resolve("clean-subset-exports");
        Files.createDirectories(exportDir);
        Path zipPath = exportDir.resolve("clean-subset-ratio-" + context.ratio + ".zip");
        Path metadataPath = exportDir.resolve("clean-subset-ratio-" + context.ratio + ".metadata.json");
        if (Files.isRegularFile(zipPath) && Files.size(zipPath) > 0) {
            return zipPath;
        }
        if (!StringUtils.hasText(pythonScript)) {
            throw new BadRequestException("Python 算法执行器未配置，无法导出清洗数据集");
        }
        List<String> command = Arrays.asList(
                pythonExecutable, pythonScript,
                "--export-clean-subset",
                "--selection-file", context.selectionFile.toString(),
                "--dataset-root", context.datasetRoot.toString(),
                "--dataset", "cifar10",
                "--output", zipPath.toString(),
                "--metadata", metadataPath.toString()
        );
        log.info("导出任务 {} 的 LDD 干净数据子集，隔离比例 {}%", task.getTaskId(), context.ratio);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File(context.artifactRoot.toString()));
        processBuilder.redirectErrorStream(true);
        Path logFile = exportDir.resolve("clean-subset-ratio-" + context.ratio + ".export.log");
        processBuilder.redirectOutput(logFile.toFile());
        try {
            Process process = processBuilder.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BadRequestException("导出清洗数据集超时");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(zipPath)) {
                throw new BadRequestException("导出清洗数据集失败，请查看任务产物日志");
            }
            return zipPath;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("导出清洗数据集被中断");
        }
    }

    @Override
    public Map<String, Object> describe(PreDefense task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("selectionKind", null);
        result.put("isolationRatios", new ArrayList<Integer>());
        if (!"success".equals(task.getStatus()) || ObjectUtil.isEmpty(task.getCleaningResult())) {
            return result;
        }
        try {
            JSONObject cleaningResult = JSON.parseObject(task.getCleaningResult());
            JSONObject artifacts = cleaningResult.getJSONObject("artifacts");
            if (artifacts == null || !"retained_clean_subset".equals(artifacts.getString("selection_kind"))) {
                return result;
            }
            List<Integer> ratios = collectSelectionRatios(artifacts.getJSONArray("selection_files"));
            result.put("available", !ratios.isEmpty());
            result.put("selectionKind", artifacts.getString("selection_kind"));
            result.put("isolationRatios", ratios);
            result.put("defaultIsolationRatio", ratios.isEmpty() ? null : ratios.get(0));
            result.put("description", "依据 LDD 保留样本索引，从 CIFAR-10 原始训练集重建干净子集 ZIP。");
            return result;
        } catch (Exception ignored) {
            return result;
        }
    }

    @Override
    public String buildDownloadFileName(PreDefense task, Integer isolationRatio) {
        int ratio = isolationRatio == null ? 0 : isolationRatio;
        return "task-" + task.getTaskId() + "-ldd-clean-subset-" + ratio + "percent.zip";
    }

    private ExportContext resolveContext(PreDefense task, Integer requestedRatio) {
        if (!"success".equals(task.getStatus()) || ObjectUtil.isEmpty(task.getCleaningResult())) {
            throw new BadRequestException("仅成功的真实 LDD 任务可以导出清洗数据集");
        }
        JSONObject result;
        try {
            result = JSON.parseObject(task.getCleaningResult());
        } catch (Exception e) {
            throw new BadRequestException("任务结果格式错误，无法导出清洗数据集");
        }
        if (!"vab://pre-defense/ldd".equals(result.getString("algorithm"))) {
            throw new BadRequestException("当前仅支持导出 LDD 的清洗数据集");
        }
        JSONObject artifacts = result.getJSONObject("artifacts");
        JSONObject config = result.getJSONObject("effective_config");
        if (artifacts == null || config == null || !"retained_clean_subset".equals(artifacts.getString("selection_kind"))) {
            throw new BadRequestException("该任务不包含可导出的干净子集索引");
        }
        if (!"cifar10".equalsIgnoreCase(config.getString("dataset"))) {
            throw new BadRequestException("当前仅支持导出 CIFAR-10 清洗子集");
        }
        Path artifactRoot = safeDirectory(artifacts.getString("path"), "任务产物目录不可用");
        Path datasetRoot = safeDirectory(config.getString("data_path"), "CIFAR-10 数据集目录不可用");
        List<Path> selectionFiles = selectionFiles(artifacts.getJSONArray("selection_files"), artifactRoot);
        Path selectionFile = selectSelectionFile(selectionFiles, requestedRatio);
        return new ExportContext(artifactRoot, datasetRoot, selectionFile, ratioFromPath(selectionFile));
    }

    private Path safeDirectory(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BadRequestException(message);
        }
        Path path = Paths.get(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new BadRequestException(message);
        }
        return path;
    }

    private List<Path> selectionFiles(JSONArray rawFiles, Path artifactRoot) {
        List<Path> files = new ArrayList<>();
        if (rawFiles == null) {
            return files;
        }
        for (Object raw : rawFiles) {
            Path path = Paths.get(String.valueOf(raw)).toAbsolutePath().normalize();
            if (path.startsWith(artifactRoot) && Files.isRegularFile(path) && SELECTION_RATIO.matcher(path.getFileName().toString()).matches()) {
                files.add(path);
            }
        }
        files.sort(Comparator.comparingInt(this::ratioFromPath));
        return files;
    }

    private Path selectSelectionFile(List<Path> files, Integer requestedRatio) {
        if (files.isEmpty()) {
            throw new BadRequestException("任务未生成可导出的 LDD 样本索引");
        }
        if (requestedRatio == null) {
            return files.get(0);
        }
        for (Path file : files) {
            if (ratioFromPath(file) == requestedRatio) {
                return file;
            }
        }
        throw new BadRequestException("指定隔离比例没有对应的 LDD 样本索引");
    }

    private List<Integer> collectSelectionRatios(JSONArray rawFiles) {
        List<Integer> ratios = new ArrayList<>();
        if (rawFiles != null) {
            for (Object raw : rawFiles) {
                String name = Paths.get(String.valueOf(raw)).getFileName().toString();
                Matcher matcher = SELECTION_RATIO.matcher(name);
                if (matcher.matches()) {
                    ratios.add(Integer.parseInt(matcher.group(1)));
                }
            }
        }
        ratios.sort(Integer::compareTo);
        return ratios;
    }

    private int ratioFromPath(Path path) {
        Matcher matcher = SELECTION_RATIO.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return -1;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static final class ExportContext {
        private final Path artifactRoot;
        private final Path datasetRoot;
        private final Path selectionFile;
        private final int ratio;

        private ExportContext(Path artifactRoot, Path datasetRoot, Path selectionFile, int ratio) {
            this.artifactRoot = artifactRoot;
            this.datasetRoot = datasetRoot;
            this.selectionFile = selectionFile;
            this.ratio = ratio;
        }
    }
}
