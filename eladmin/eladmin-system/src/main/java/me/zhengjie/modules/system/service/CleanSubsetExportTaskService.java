package me.zhengjie.modules.system.service;

import me.zhengjie.modules.system.domain.CleanSubsetExportTask;
import me.zhengjie.modules.system.domain.PreDefense;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 清洗后数据子集异步导出任务服务。
 */
public interface CleanSubsetExportTaskService {

    CleanSubsetExportTask submit(PreDefense task, Integer isolationRatio);

    CleanSubsetExportTask findById(Long exportId);

    List<CleanSubsetExportTask> findByTaskId(Long taskId);

    /**
     * 返回已经完成且经过任务产物目录校验的 ZIP 文件。
     */
    Path resolveDownloadFile(Long exportId) throws IOException;

    String buildDownloadFileName(CleanSubsetExportTask exportTask);
}
