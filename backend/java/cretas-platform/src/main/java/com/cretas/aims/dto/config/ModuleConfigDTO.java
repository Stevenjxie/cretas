package com.cretas.aims.dto.config;

import lombok.*;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ModuleConfigDTO {
    private Boolean enabled;
    private Map<String, Object> fieldConfig;
    private Map<String, Object> workflowConfig;
    private Map<String, Object> validationConfig;
    private Map<String, Object> permissionConfig;
    private Map<String, Object> layoutConfig;
    private Map<String, Object> customLabels;
    private String renderingMode;
}
