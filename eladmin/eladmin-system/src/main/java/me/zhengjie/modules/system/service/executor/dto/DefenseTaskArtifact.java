package me.zhengjie.modules.system.service.executor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Output artifact metadata written into task result JSON.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefenseTaskArtifact {

    private String type;

    private Boolean available;

    private String path;

    private String message;
}
