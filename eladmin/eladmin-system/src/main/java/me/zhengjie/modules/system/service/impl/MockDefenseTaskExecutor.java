package me.zhengjie.modules.system.service.impl;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.modules.system.domain.DuringDefense;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.mapper.DuringDefenseMapper;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
import me.zhengjie.modules.system.service.executor.DefenseTaskExecutor;
import me.zhengjie.modules.system.service.executor.dto.DefenseResourceRef;
import me.zhengjie.modules.system.service.executor.dto.DefenseTaskRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MVP mock executor used before real security algorithms are integrated.
 */
@Slf4j
@Service
@Profile("!python-executor")
@RequiredArgsConstructor
public class MockDefenseTaskExecutor implements DefenseTaskExecutor {

    private final PreDefenseMapper preDefenseMapper;
    private final DuringDefenseMapper duringDefenseMapper;

    @Override
    @Async("taskAsync")
    public void runPreDefense(DefenseTaskRequest request) {
        Long taskId = request.getTaskId();
        String startedAt = LocalDateTime.now().toString();
        try {
            updatePreProgress(taskId, 20);
            sleep();
            updatePreProgress(taskId, 45);
            sleep();
            updatePreProgress(taskId, 75);
            sleep();

            Map<String, Object> result = buildPreDefenseResult(request, startedAt);

            preDefenseMapper.updateResult(taskId, null, JSON.toJSONString(result));
            finishPreTask(taskId, "success", 100);
            log.info("Mock pre-defense task {} completed", taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordPreFailure(taskId, startedAt, "TASK_INTERRUPTED", "任务被中断");
            finishPreTask(taskId, "failed", 0);
            log.warn("Mock pre-defense task {} interrupted", taskId);
        } catch (Exception e) {
            recordPreFailure(taskId, startedAt, "EXECUTION_ERROR", e.getMessage());
            finishPreTask(taskId, "failed", 0);
            log.error("Mock pre-defense task {} failed", taskId, e);
        }
    }

    @Override
    @Async("taskAsync")
    public void runDuringDefense(DefenseTaskRequest request) {
        Long taskId = request.getTaskId();
        String startedAt = LocalDateTime.now().toString();
        try {
            updateDuringProgress(taskId, 15);
            sleep();
            updateDuringProgress(taskId, 35);
            sleep();
            updateDuringProgress(taskId, 65);
            sleep();
            updateDuringProgress(taskId, 90);
            sleep();

            Map<String, Object> trainingLog = buildDuringDefenseResult(request, startedAt);

            duringDefenseMapper.updateTrainingResult(taskId, null, JSON.toJSONString(trainingLog));
            finishDuringTask(taskId, "success", 100);
            log.info("Mock during-defense task {} completed", taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordDuringFailure(taskId, startedAt, "TASK_INTERRUPTED", "任务被中断");
            finishDuringTask(taskId, "failed", 0);
            log.warn("Mock during-defense task {} interrupted", taskId);
        } catch (Exception e) {
            recordDuringFailure(taskId, startedAt, "EXECUTION_ERROR", e.getMessage());
            finishDuringTask(taskId, "failed", 0);
            log.error("Mock during-defense task {} failed", taskId, e);
        }
    }

    private void updatePreProgress(Long taskId, Integer progress) {
        preDefenseMapper.updateStatus(taskId, "running", progress);
    }

    private void updateDuringProgress(Long taskId, Integer progress) {
        duringDefenseMapper.updateStatus(taskId, "running", progress);
    }

    private void finishPreTask(Long taskId, String status, Integer progress) {
        PreDefense task = preDefenseMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(status);
        task.setProgress(progress);
        task.setEndTime(LocalDateTime.now());
        preDefenseMapper.updateById(task);
    }

    private void finishDuringTask(Long taskId, String status, Integer progress) {
        DuringDefense task = duringDefenseMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(status);
        task.setProgress(progress);
        task.setEndTime(LocalDateTime.now());
        duringDefenseMapper.updateById(task);
    }

    private void recordPreFailure(Long taskId, String startedAt, String errorCode, String failureReason) {
        preDefenseMapper.updateResult(taskId, null, JSON.toJSONString(buildFailureResult(
                "mock", "pre_defense", startedAt, errorCode, failureReason, "dataset")));
    }

    private void recordDuringFailure(Long taskId, String startedAt, String errorCode, String failureReason) {
        duringDefenseMapper.updateTrainingResult(taskId, null, JSON.toJSONString(buildFailureResult(
                "mock", "during_defense", startedAt, errorCode, failureReason, "model")));
    }

    private Map<String, Object> buildFailureResult(String executor, String stage, String startedAt,
                                                   String errorCode, String failureReason, String artifactType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executor", executor);
        result.put("stage", stage);
        result.put("status", "failed");
        result.put("message", "task execution failed");
        result.put("error_code", errorCode);
        result.put("failure_reason", failureReason == null ? "未知错误" : failureReason);
        result.put("started_at", startedAt);
        result.put("finished_at", LocalDateTime.now().toString());
        result.put("artifacts", buildArtifacts(artifactType, null, false));
        return result;
    }

    private Map<String, Object> buildArtifacts(String artifactType, String artifactPath, boolean available) {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("type", artifactType);
        artifacts.put("available", available);
        artifacts.put("path", artifactPath);
        artifacts.put("message", available ? "artifact ready" : "MVP mock executor does not generate real artifacts");
        return artifacts;
    }

    private Map<String, Object> buildPreDefenseResult(DefenseTaskRequest request, String startedAt) {
        PreDefenseProfile profile = resolvePreDefenseProfile(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executor", "builtin-mock");
        result.put("stage", "pre_defense");
        result.put("status", "success");
        result.put("algorithm_key", profile.algorithmKey);
        result.put("summary", profile.summary);
        result.put("message", profile.message);
        result.put("started_at", startedAt);
        result.put("finished_at", LocalDateTime.now().toString());
        result.put("poisonedSampleCount", profile.removedCount);
        result.put("cleanSampleCount", profile.cleanedCount);
        result.put("estimatedPoisonRate", round4(profile.removedCount * 1.0 / profile.originalCount));
        result.put("cleanSubsetPurity", profile.cleanSubsetPurity);
        result.put("sample_stats", buildSampleStats(
                profile.originalCount,
                profile.cleanedCount,
                profile.removedCount,
                profile.averageConfidence));
        result.put("metrics", buildMetrics(profile.precision, profile.recall, profile.f1));
        result.put("method_detail", profile.methodDetail);
        result.put("score_distribution", profile.scoreDistribution);
        result.put("artifacts", buildArtifacts("dataset", null, false));
        result.put("task", buildTaskSnapshot(request));
        return result;
    }

    private PreDefenseProfile resolvePreDefenseProfile(DefenseTaskRequest request) {
        String path = getAlgorithmPath(request);
        if (path.contains("low-confidence-filter")) {
            return new PreDefenseProfile(
                    "low_confidence_filter",
                    "低置信样本过滤占位执行完成：按置信度阈值剔除疑似低质量或受污染样本。",
                    "low-confidence pre-defense mock completed",
                    5000, 4760, 240, 0.91, 0.93, 0.91, 0.92, 0.985,
                    mapOf(
                            "method", "confidence_threshold",
                            "confidence_threshold", 0.65,
                            "max_remove_ratio", 0.15,
                            "note", "MVP阶段使用确定性模拟结果，后续替换为真实模型置信度计算。"),
                    mapOf("low", 240, "medium", 1120, "high", 3640));
        }
        if (path.contains("iqr-cleaning")) {
            return new PreDefenseProfile(
                    "iqr_cleaning",
                    "IQR四分位异常清洗占位执行完成：按箱线图规则过滤离群样本。",
                    "iqr pre-defense mock completed",
                    5000, 4820, 180, 0.93, 0.94, 0.92, 0.93, 0.988,
                    mapOf(
                            "method", "iqr",
                            "q1", 0.18,
                            "q3", 0.74,
                            "iqr_multiplier", 1.5,
                            "note", "适合作为表格特征或样本损失分布的经典异常检测基线。"),
                    mapOf("normal", 4820, "mild_outlier", 146, "extreme_outlier", 34));
        }
        if (path.contains("isolation-forest-cleaning")) {
            return new PreDefenseProfile(
                    "isolation_forest_cleaning",
                    "Isolation Forest异常清洗占位执行完成：按孤立树异常分数筛出疑似污染样本。",
                    "isolation-forest pre-defense mock completed",
                    5000, 4685, 315, 0.89, 0.90, 0.88, 0.89, 0.981,
                    mapOf(
                            "method", "isolation_forest",
                            "estimators", 100,
                            "contamination", 0.06,
                            "note", "MVP阶段模拟算法输出，真实实现可由Python执行器接入。"),
                    mapOf("safe", 4294, "review", 391, "removed", 315));
        }
        return new PreDefenseProfile(
                "statistical_cleaning",
                "统计异常清洗占位执行完成：按Z-Score异常分数过滤疑似污染样本。",
                "statistical pre-defense mock completed",
                5000, 4872, 128, 0.94, 0.97, 0.95, 0.96, 0.99,
                mapOf(
                        "method", "zscore",
                        "threshold", 2.5,
                        "max_remove_ratio", 0.1,
                        "note", "适合作为训练前数据清洗的默认轻量基线。"),
                mapOf("normal", 4872, "suspect", 98, "removed", 128));
    }

    private Map<String, Object> buildDuringDefenseResult(DefenseTaskRequest request, String startedAt) {
        DuringDefenseProfile profile = resolveDuringDefenseProfile(request);
        Map<String, Object> trainingLog = new LinkedHashMap<>();
        trainingLog.put("executor", "builtin-mock");
        trainingLog.put("stage", "during_defense");
        trainingLog.put("status", "success");
        trainingLog.put("algorithm_key", profile.algorithmKey);
        trainingLog.put("summary", profile.summary);
        trainingLog.put("message", profile.message);
        trainingLog.put("started_at", startedAt);
        trainingLog.put("finished_at", LocalDateTime.now().toString());
        trainingLog.put("epochs", profile.epochs);
        trainingLog.put("final_accuracy", profile.finalAccuracy);
        trainingLog.put("current_accuracy", profile.finalAccuracy);
        trainingLog.put("bestAccuracy", profile.bestAccuracy);
        trainingLog.put("attackSuccessRate", profile.attackSuccessRate);
        trainingLog.put("lossCurve", profile.lossCurve);
        trainingLog.put("accuracyCurve", profile.accuracyCurve);
        trainingLog.put("defense_metrics", mapOf(
                "clean_accuracy", profile.finalAccuracy,
                "best_accuracy", profile.bestAccuracy,
                "attack_success_rate", profile.attackSuccessRate,
                "robust_accuracy", profile.robustAccuracy));
        trainingLog.put("method_detail", profile.methodDetail);
        trainingLog.put("artifacts", buildArtifacts("model", null, false));
        trainingLog.put("task", buildTaskSnapshot(request));
        return trainingLog;
    }

    private DuringDefenseProfile resolveDuringDefenseProfile(DefenseTaskRequest request) {
        String path = getAlgorithmPath(request);
        if (path.contains("label-smoothing-training")) {
            return new DuringDefenseProfile(
                    "label_smoothing_training",
                    "标签平滑训练占位执行完成：通过软标签降低模型对异常标签的过拟合。",
                    "label-smoothing during-defense mock completed",
                    10, 0.921, 0.925, 0.058, 0.887,
                    new double[]{1.74, 1.18, 0.91, 0.70, 0.58, 0.49, 0.43, 0.39, 0.36, 0.34},
                    new double[]{0.40, 0.55, 0.65, 0.73, 0.79, 0.84, 0.875, 0.898, 0.913, 0.921},
                    mapOf("method", "label_smoothing", "epsilon", 0.1, "note", "适合先作为训练中防御的低成本基线。"));
        }
        if (path.contains("adversarial-training")) {
            return new DuringDefenseProfile(
                    "adversarial_training_placeholder",
                    "对抗训练占位执行完成：模拟在训练过程中混入扰动样本提升鲁棒性。",
                    "adversarial-training during-defense mock completed",
                    10, 0.906, 0.914, 0.033, 0.902,
                    new double[]{1.96, 1.42, 1.07, 0.84, 0.69, 0.58, 0.51, 0.46, 0.42, 0.39},
                    new double[]{0.35, 0.50, 0.61, 0.70, 0.76, 0.81, 0.85, 0.878, 0.895, 0.906},
                    mapOf("method", "adversarial_training", "epsilon", 0.03, "attack_steps", 3, "note", "MVP阶段仅模拟训练过程，真实扰动生成后续接入。"));
        }
        return new DuringDefenseProfile(
                "robust_training",
                "鲁棒训练占位执行完成：通过稳健损失和轻量正则化模拟安全训练流程。",
                "robust-training during-defense mock completed",
                10, 0.928, 0.932, 0.041, 0.895,
                new double[]{1.82, 1.21, 0.88, 0.63, 0.51, 0.43, 0.38, 0.34, 0.31, 0.29},
                new double[]{0.42, 0.57, 0.68, 0.76, 0.82, 0.86, 0.89, 0.91, 0.923, 0.928},
                mapOf("method", "robust_loss", "weight_decay", 0.0005, "defense_strength", 0.3, "note", "默认训练中防御基线。"));
    }

    private Map<String, Object> buildTaskSnapshot(DefenseTaskRequest request) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", request.getTaskId());
        task.put("name", request.getTaskName());
        task.put("stage", request.getStage());
        task.put("created_by", request.getCreatedBy());
        task.put("dataset", buildResourceSnapshot(request.getDataset()));
        task.put("algorithm", buildResourceSnapshot(request.getAlgorithm()));
        task.put("hyper_parameter", buildResourceSnapshot(request.getHyperParameter()));
        task.put("input_model", buildResourceSnapshot(request.getInputModel()));
        return task;
    }

    private Map<String, Object> buildResourceSnapshot(DefenseResourceRef resource) {
        if (resource == null) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", resource.getId());
        data.put("name", resource.getName());
        data.put("type", resource.getType());
        data.put("category", resource.getCategory());
        data.put("path", resource.getPath());
        return data;
    }

    private Map<String, Object> buildMetrics(double precision, double recall, double f1) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("precision", precision);
        metrics.put("recall", recall);
        metrics.put("f1", f1);
        return metrics;
    }

    private Map<String, Object> buildSampleStats(int originalCount, int cleanedCount, int removedCount, double averageConfidence) {
        Map<String, Object> sampleStats = new LinkedHashMap<>();
        sampleStats.put("original_count", originalCount);
        sampleStats.put("cleaned_count", cleanedCount);
        sampleStats.put("removed_count", removedCount);
        sampleStats.put("retention_rate", round4(cleanedCount * 1.0 / originalCount));
        sampleStats.put("average_confidence", averageConfidence);
        return sampleStats;
    }

    private String getAlgorithmPath(DefenseTaskRequest request) {
        DefenseResourceRef algorithm = request.getAlgorithm();
        if (algorithm == null || algorithm.getPath() == null) {
            return "";
        }
        return algorithm.getPath().toLowerCase();
    }

    private Map<String, Object> mapOf(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private void sleep() throws InterruptedException {
        Thread.sleep(800L);
    }

    private static class PreDefenseProfile {
        private final String algorithmKey;
        private final String summary;
        private final String message;
        private final int originalCount;
        private final int cleanedCount;
        private final int removedCount;
        private final double averageConfidence;
        private final double precision;
        private final double recall;
        private final double f1;
        private final double cleanSubsetPurity;
        private final Map<String, Object> methodDetail;
        private final Map<String, Object> scoreDistribution;

        private PreDefenseProfile(String algorithmKey, String summary, String message,
                                  int originalCount, int cleanedCount, int removedCount,
                                  double averageConfidence, double precision, double recall,
                                  double f1, double cleanSubsetPurity,
                                  Map<String, Object> methodDetail,
                                  Map<String, Object> scoreDistribution) {
            this.algorithmKey = algorithmKey;
            this.summary = summary;
            this.message = message;
            this.originalCount = originalCount;
            this.cleanedCount = cleanedCount;
            this.removedCount = removedCount;
            this.averageConfidence = averageConfidence;
            this.precision = precision;
            this.recall = recall;
            this.f1 = f1;
            this.cleanSubsetPurity = cleanSubsetPurity;
            this.methodDetail = methodDetail;
            this.scoreDistribution = scoreDistribution;
        }
    }

    private static class DuringDefenseProfile {
        private final String algorithmKey;
        private final String summary;
        private final String message;
        private final int epochs;
        private final double finalAccuracy;
        private final double bestAccuracy;
        private final double attackSuccessRate;
        private final double robustAccuracy;
        private final double[] lossCurve;
        private final double[] accuracyCurve;
        private final Map<String, Object> methodDetail;

        private DuringDefenseProfile(String algorithmKey, String summary, String message,
                                     int epochs, double finalAccuracy, double bestAccuracy,
                                     double attackSuccessRate, double robustAccuracy,
                                     double[] lossCurve, double[] accuracyCurve,
                                     Map<String, Object> methodDetail) {
            this.algorithmKey = algorithmKey;
            this.summary = summary;
            this.message = message;
            this.epochs = epochs;
            this.finalAccuracy = finalAccuracy;
            this.bestAccuracy = bestAccuracy;
            this.attackSuccessRate = attackSuccessRate;
            this.robustAccuracy = robustAccuracy;
            this.lossCurve = lossCurve;
            this.accuracyCurve = accuracyCurve;
            this.methodDetail = methodDetail;
        }
    }
}
