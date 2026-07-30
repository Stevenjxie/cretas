package com.cretas.aims.dto.config;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FieldGroup {
    private String code;
    private String label;
    private int order;
    private boolean visible;
}
