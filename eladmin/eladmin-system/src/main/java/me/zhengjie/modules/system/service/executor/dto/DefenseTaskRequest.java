package me.zhengjie.modules.system.service.executor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable-at-start execution context for a defense task.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefenseTaskRequest {

    private Long taskId;

    private String taskName;

    private String stage;

    private String description;

    private String createdBy;

    private DefenseResourceRef dataset;

    private DefenseResourceRef algorithm;

    private DefenseResourceRef hyperParameter;

    private DefenseResourceRef inputModel;
}
