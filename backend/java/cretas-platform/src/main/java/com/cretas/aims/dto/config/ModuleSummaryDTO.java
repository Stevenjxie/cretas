package com.cretas.aims.dto.config;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ModuleSummaryDTO {
    private String moduleCode;
    private String moduleName;
    private String moduleCategory;
    private boolean enabled;
    private String renderingMode;
}
