package com.cretas.aims.dto.bom;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Request for the idempotent BOM draft lifecycle entry point. */
@Data
public class EnsureBomDraftRequest {

    @NotBlank(message = "productTypeId 不能为空")
    @Size(max = 100, message = "productTypeId 长度不能超过 100")
    private String productTypeId;

    /**
     * Exact saved Workflow revision supplied by the Workflow canvas.
     *
     * <p>When present this is the authority for the BOM workspace. The backend must never
     * silently fall back to an older ACTIVE BOM lineage or another Workflow containing the
     * same terminal SKU.
     */
    private Long workflowRevisionId;
}
