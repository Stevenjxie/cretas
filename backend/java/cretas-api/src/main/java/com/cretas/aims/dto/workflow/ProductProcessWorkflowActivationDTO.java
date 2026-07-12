package com.cretas.aims.dto.workflow;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProductProcessWorkflowActivationDTO {

    private Long id;
    private String factoryId;
    private String productTypeId;
    private Long activeWorkflowId;
    private Integer activeDefinitionVersion;
    private Boolean enabled;
    private Long activatedBy;
    private LocalDateTime activatedAt;
    private Long lockVersion;
}
