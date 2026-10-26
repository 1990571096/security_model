package com.securitymodel.application.task;

import lombok.Data;

/**
 * Mutable command object kept separate from the persistence model so clients
 * cannot change lifecycle or execution fields directly.
 */
@Data
public class PreDefenseTaskCommand {

    private Long taskId;
    private String taskName;
    private String description;
    private Long datasetId;
    private Long algorithmId;
    private Long hyperId;
    private String customParams;
}
