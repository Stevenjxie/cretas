package com.cretas.aims.dto.config;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WorkflowStateDTO {
    private String code;
    private String label;
    private boolean enabled;
    private boolean isInitial;
    private boolean isFinal;
    private String tagType;
}
