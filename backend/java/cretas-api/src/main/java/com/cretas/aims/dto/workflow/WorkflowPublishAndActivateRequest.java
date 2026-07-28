package com.cretas.aims.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class WorkflowPublishAndActivateRequest {

    @NotNull
    private Long lockVersion;

    @NotBlank
    private String idempotencyKey;

    @NotNull
    private Long revisionId;

    @NotBlank
    private String revisionHash;

    @NotNull
    private Integer definitionVersion;
}
