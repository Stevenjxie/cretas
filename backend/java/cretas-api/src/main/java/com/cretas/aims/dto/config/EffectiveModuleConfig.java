package com.cretas.aims.dto.config;

import lombok.*;
import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class EffectiveModuleConfig {
    private String moduleCode;
    private String moduleName;
    private boolean enabled;
    private List<EffectiveField> fields;
    private List<FieldGroup> groups;
    private List<WorkflowStateDTO> workflowStates;
    private List<WorkflowTransitionDTO> workflowTransitions;
    private Map<String, Object> workflowOptions;
    private Map<String, String> customLabels;
    private String renderingMode;
}
