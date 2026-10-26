package com.securitymodel.application.task;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskProgressView {
    private Long taskId;
    private String taskName;
    private String description;
    private String taskType = "pre_defense";
    private String status;
    private Integer progress;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
    private String createBy;
    private Long datasetId;
    private Long outputDatasetId;
    private String cleaningResult;
    private Long algorithmId;
    private Long hyperId;
    private String customParams;
    private String algorithmName;
    private String datasetName;
    private Long runningDuration;
    private String taskTypeDisplay = "训练前防御";
}
