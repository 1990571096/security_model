package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CleanSubsetExportTaskMapper extends BaseMapper<CleanSubsetExportTask> {

    @Insert("""
            INSERT INTO sys_pre_defense_export
              (task_id, isolation_ratio, status, progress, create_by, update_by, create_time, update_time)
            VALUES
              (#{taskId}, #{isolationRatio}, 'pending', 0, #{operator}, #{operator}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE export_id = LAST_INSERT_ID(export_id)
            """)
    int insertIfAbsent(@Param("taskId") Long taskId,
                       @Param("isolationRatio") Integer isolationRatio,
                       @Param("operator") String operator);

    @Select("""
            SELECT * FROM sys_pre_defense_export
            WHERE task_id = #{taskId} AND isolation_ratio = #{ratio}
            LIMIT 1
            """)
    CleanSubsetExportTask findByTaskAndRatio(@Param("taskId") Long taskId,
                                             @Param("ratio") Integer ratio);

    @Select("""
            SELECT * FROM sys_pre_defense_export
            WHERE task_id = #{taskId}
            ORDER BY export_id DESC
            """)
    List<CleanSubsetExportTask> findByTaskId(@Param("taskId") Long taskId);

    @Update("""
            UPDATE sys_pre_defense_export
            SET status = 'running', progress = 1, start_time = #{startTime},
                end_time = NULL, file_path = NULL, metadata_path = NULL,
                error_code = NULL, failure_reason = NULL, update_time = NOW()
            WHERE export_id = #{exportId} AND status = 'pending'
            """)
    int claimForExecution(@Param("exportId") Long exportId,
                          @Param("startTime") LocalDateTime startTime);

    @Update("""
            UPDATE sys_pre_defense_export
            SET progress = #{progress}, update_time = NOW()
            WHERE export_id = #{exportId} AND status = 'running'
            """)
    int updateProgress(@Param("exportId") Long exportId, @Param("progress") Integer progress);

    @Update("""
            UPDATE sys_pre_defense_export
            SET status = 'success', progress = 100, file_path = #{filePath},
                metadata_path = #{metadataPath}, end_time = #{endTime}, update_time = NOW()
            WHERE export_id = #{exportId} AND status = 'running'
            """)
    int markSuccess(@Param("exportId") Long exportId,
                    @Param("filePath") String filePath,
                    @Param("metadataPath") String metadataPath,
                    @Param("endTime") LocalDateTime endTime);

    @Update("""
            UPDATE sys_pre_defense_export
            SET status = 'failed', progress = 0, error_code = #{errorCode},
                failure_reason = #{reason}, end_time = #{endTime}, update_time = NOW()
            WHERE export_id = #{exportId} AND status = 'running'
            """)
    int markFailed(@Param("exportId") Long exportId,
                   @Param("errorCode") String errorCode,
                   @Param("reason") String reason,
                   @Param("endTime") LocalDateTime endTime);

    @Update("""
            UPDATE sys_pre_defense_export
            SET status = 'pending', progress = 0, start_time = NULL, end_time = NULL,
                file_path = NULL, metadata_path = NULL, error_code = NULL,
                failure_reason = NULL, update_time = NOW()
            WHERE export_id = #{exportId} AND status = 'failed'
            """)
    int resetForRetry(@Param("exportId") Long exportId);

    @Update("""
            UPDATE sys_pre_defense_export
            SET status = 'failed', progress = 0, error_code = #{errorCode},
                failure_reason = #{reason}, end_time = #{endTime}, update_time = NOW()
            WHERE export_id = #{exportId} AND status = 'pending'
            """)
    int failBeforeExecution(@Param("exportId") Long exportId,
                            @Param("errorCode") String errorCode,
                            @Param("reason") String reason,
                            @Param("endTime") LocalDateTime endTime);

    @Update("""
            UPDATE sys_pre_defense_export
            SET status = 'failed', progress = 0, error_code = #{errorCode},
                failure_reason = #{reason}, end_time = #{endTime}, update_time = NOW()
            WHERE export_id = #{exportId} AND status = 'running'
            """)
    int failOrphaned(@Param("exportId") Long exportId,
                     @Param("errorCode") String errorCode,
                     @Param("reason") String reason,
                     @Param("endTime") LocalDateTime endTime);
}
