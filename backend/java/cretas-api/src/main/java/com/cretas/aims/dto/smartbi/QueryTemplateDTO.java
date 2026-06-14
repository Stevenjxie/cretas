package com.cretas.aims.dto.smartbi;

import lombok.*;

/**
 * Shape consumed by QueryTemplateManager.vue: { id, name, category, description,
 * queryTemplate, parameters } — parameters is a JSON string (TemplateParam[]).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryTemplateDTO {
    private Long id;
    private String name;
    private String category;
    private String description;
    private String queryTemplate;
    private String parameters;
}
