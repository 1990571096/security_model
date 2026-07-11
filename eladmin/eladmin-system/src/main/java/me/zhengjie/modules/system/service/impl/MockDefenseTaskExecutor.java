package me.zhengjie.modules.system.service.impl;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.modules.system.domain.DuringDefense;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.mapper.DuringDefenseMapper;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
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
@RequiredArgsConstructor
public class MockDefenseTaskExecutor {

    private final PreDefenseMapper preDefenseMapper;
    private final DuringDefenseMapper duringDefenseMapper;

    @Async("taskAsync")
    public void runPreDefense(Long taskId) {
        try {
            updatePreProgress(taskId, 20);
            sleep();
            updatePreProgress(taskId, 45);
            sleep();
            updatePreProgress(taskId, 75);
            sleep();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("executor", "mock");
            result.put("stage", "pre_defense");
            result.put("summary", "训练前数据清洗模拟完成，后续将替换为真实防御算法。");
            result.put("poisonedSampleCount", 128);
            result.put("cleanSampleCount", 4872);
            result.put("estimatedPoisonRate", 0.0256);
            result.put("cleanSubsetPurity", 0.99);
            result.put("metrics", buildMetrics(0.97, 0.95, 0.96));

            preDefenseMapper.updateResult(taskId, null, JSON.toJSONString(result));
            finishPreTask(taskId, "success", 100);
            log.info("Mock pre-defense task {} completed", taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishPreTask(taskId, "failed", 0);
            log.warn("Mock pre-defense task {} interrupted", taskId);
        } catch (Exception e) {
            finishPreTask(taskId, "failed", 0);
            log.error("Mock pre-defense task {} failed", taskId, e);
        }
    }

    @Async("taskAsync")
    public void runDuringDefense(Long taskId) {
        try {
            updateDuringProgress(taskId, 15);
            sleep();
            updateDuringProgress(taskId, 35);
            sleep();
            updateDuringProgress(taskId, 65);
            sleep();
            updateDuringProgress(taskId, 90);
            sleep();

            Map<String, Object> trainingLog = new LinkedHashMap<>();
            trainingLog.put("executor", "mock");
            trainingLog.put("stage", "during_defense");
            trainingLog.put("summary", "安全训练模拟完成，后续将替换为真实训练中防御算法。");
            trainingLog.put("epochs", 10);
            trainingLog.put("bestAccuracy", 0.932);
            trainingLog.put("attackSuccessRate", 0.041);
            trainingLog.put("lossCurve", new double[]{1.82, 1.21, 0.88, 0.63, 0.51, 0.43, 0.38, 0.34, 0.31, 0.29});

            duringDefenseMapper.updateTrainingResult(taskId, null, JSON.toJSONString(trainingLog));
            finishDuringTask(taskId, "success", 100);
            log.info("Mock during-defense task {} completed", taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishDuringTask(taskId, "failed", 0);
            log.warn("Mock during-defense task {} interrupted", taskId);
        } catch (Exception e) {
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

    private Map<String, Object> buildMetrics(double precision, double recall, double f1) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("precision", precision);
        metrics.put("recall", recall);
        metrics.put("f1", f1);
        return metrics;
    }

    private void sleep() throws InterruptedException {
        Thread.sleep(800L);
    }
}
