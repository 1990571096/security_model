package com.securitymodel.application.task;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DefenseTaskRequest {
    private Long taskId;
    private Integer attempt;
    private String runId;
    private String taskName;
    private String stage;
    private String description;
    private String createdBy;
    private String customParams;
    private DefenseResourceRef dataset;
    private DefenseResourceRef algorithm;
    private DefenseResourceRef hyperParameter;
}
