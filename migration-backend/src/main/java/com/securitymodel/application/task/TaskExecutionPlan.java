package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The normalized, resource-bound execution contract approved by a user preview.
 * {@code fingerprint} binds the final LDD parameters and the catalog rows that produced
 * them; {@code preview} is the safe assistant view without local paths.
 */
public final class TaskExecutionPlan {

    private final String fingerprint;
    private final ObjectNode finalParameters;
    private final ObjectNode preview;
    private final DefenseResourceRef dataset;
    private final DefenseResourceRef algorithm;
    private final DefenseResourceRef hyper;

    public TaskExecutionPlan(String fingerprint, ObjectNode finalParameters, ObjectNode preview) {
        this(fingerprint, finalParameters, preview, null, null, null);
    }

    public TaskExecutionPlan(String fingerprint, ObjectNode finalParameters, ObjectNode preview,
                             DefenseResourceRef dataset, DefenseResourceRef algorithm,
                             DefenseResourceRef hyper) {
        this.fingerprint = fingerprint;
        this.finalParameters = finalParameters;
        this.preview = preview;
        this.dataset = dataset;
        this.algorithm = algorithm;
        this.hyper = hyper;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public ObjectNode finalParameters() {
        return finalParameters;
    }

    public ObjectNode preview() {
        return preview;
    }

    public DefenseResourceRef dataset() {
        return dataset;
    }

    public DefenseResourceRef algorithm() {
        return algorithm;
    }

    public DefenseResourceRef hyper() {
        return hyper;
    }
}
