package com.cretas.aims.dto.config;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FieldConfigDTO {
    private Boolean visible;
    private Boolean required;
    private Object defaultValue;
    private Object options;
    private String label;
}
