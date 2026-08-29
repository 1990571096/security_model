package me.zhengjie.modules.system.service;

import me.zhengjie.modules.system.domain.dto.TaskProgressDto;

import java.util.List;
import java.util.Map;

/**
 * 任务进度查询服务接口
 * @author ZJING
 * @date 2025-08-09
 */
public interface TaskProgressService {

    /**
     * 获取所有运行中的任务
     * @return 运行中任务列表
     */
    List<TaskProgressDto> getRunningTasks();

    /**
     * 获取所有任务（支持筛选）
     * @param status 任务状态（可选）
     * @param taskType 任务类型（可选）
     * @return 任务列表
     */
    List<TaskProgressDto> getAllTasks(String status, String taskType, String blurry);

    /**
     * 根据任务类型和ID获取任务详情
     * @param taskType 任务类型
     * @param taskId 任务ID
     * @return 任务详情
     */
    TaskProgressDto getTaskDetail(String taskType, Long taskId);

    /**
     * 获取任务统计信息
     * @return 统计信息
     */
    Map<String, Object> getTaskStatistics();

    /**
     * 批量获取任务进度
     * @param taskInfos 任务信息列表
     * @return 进度信息
     */
    Map<String, Object> getBatchTaskProgress(List<Map<String, Object>> taskInfos);

    /**
     * 刷新任务状态（用于实时更新）
     */
    void refreshTaskStatus();

    /**
     * 将PreDefense转换为TaskProgressDto
     * @param preDefense 训练前防御任务
     * @return TaskProgressDto
     */
    TaskProgressDto convertPreDefenseToDto(me.zhengjie.modules.system.domain.PreDefense preDefense);

    /**
     * 将DuringDefense转换为TaskProgressDto
     * @param duringDefense 训练中防御任务
     * @return TaskProgressDto
     */
    TaskProgressDto convertDuringDefenseToDto(me.zhengjie.modules.system.domain.DuringDefense duringDefense);

    /**
     * 将PostDefense转换为TaskProgressDto
     * @param postDefense 训练后防御任务
     * @return TaskProgressDto
     */
    TaskProgressDto convertPostDefenseToDto(me.zhengjie.modules.system.domain.PostDefense postDefense);
}
