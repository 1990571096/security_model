package me.zhengjie.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import me.zhengjie.modules.system.domain.CleanSubsetExportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 清洗子集导出任务 Mapper。
 */
@Mapper
public interface CleanSubsetExportTaskMapper extends BaseMapper<CleanSubsetExportTask> {

    /**
     * Create an export task only when the task/ratio pair does not exist.
     *
     * <p>The unique key is enforced by MySQL rather than by a read-then-insert
     * race in the service layer.</p>
     *
     * @param task export task to create
     * @return 1 when inserted, 0 when the unique pair already exists
     */
    int insertIfAbsent(@Param("task") CleanSubsetExportTask task);

    CleanSubsetExportTask findByTaskAndRatio(@Param("taskId") Long taskId,
                                             @Param("isolationRatio") Integer isolationRatio);

    List<CleanSubsetExportTask> findByTaskId(@Param("taskId") Long taskId);

    List<CleanSubsetExportTask> findByStatus(@Param("status") String status);

    int claimForExecution(@Param("exportId") Long exportId,
                          @Param("startTime") LocalDateTime startTime);

    int updateProgress(@Param("exportId") Long exportId,
                       @Param("progress") Integer progress);

    int markSuccess(@Param("exportId") Long exportId,
                    @Param("filePath") String filePath,
                    @Param("metadataPath") String metadataPath,
                    @Param("endTime") LocalDateTime endTime);

    int markFailed(@Param("exportId") Long exportId,
                   @Param("errorCode") String errorCode,
                   @Param("failureReason") String failureReason,
                   @Param("endTime") LocalDateTime endTime);

    int failBeforeExecution(@Param("exportId") Long exportId,
                            @Param("errorCode") String errorCode,
                            @Param("failureReason") String failureReason,
                            @Param("endTime") LocalDateTime endTime);

    int resetForRetry(@Param("exportId") Long exportId);

    int failOrphaned(@Param("exportId") Long exportId,
                     @Param("errorCode") String errorCode,
                     @Param("failureReason") String failureReason,
                     @Param("endTime") LocalDateTime endTime);
}
