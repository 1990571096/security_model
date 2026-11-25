package com.securitymodel.domain.note;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_experiment_note")
public class ExperimentNote {

    @TableId(value = "note_id", type = IdType.ASSIGN_ID)
    private Long noteId;
    @TableField("user_id")
    private Long userId;
    private String title;
    @TableField("analysis_time")
    private LocalDateTime analysisTime;
    @TableField("run_ref_json")
    private String runRefJson;
    @TableField("snapshot_json")
    private String snapshotJson;
    @TableField("next_step")
    private String nextStep;
    @TableField("request_key")
    private String requestKey;
    private String status;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;
}
