package me.zhengjie.modules.system.service.executor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Stable resource snapshot passed to algorithm executors.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefenseResourceRef {

    private Long id;

    private String name;

    private String type;

    private String category;

    private String path;

    private String configJson;

    private String schemaJson;
}
