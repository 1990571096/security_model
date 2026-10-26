package com.securitymodel.application.task;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DefenseResourceRef {
    private Long id;
    private String name;
    private String type;
    private String category;
    private String path;
    private String configJson;
    private String schemaJson;
}
