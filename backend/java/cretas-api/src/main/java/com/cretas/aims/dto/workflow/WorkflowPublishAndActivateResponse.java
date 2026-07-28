package com.cretas.aims.dto.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowPublishAndActivateResponse {
    private ProductProcessWorkflowDTO workflow;
    private ProductProcessWorkflowActivationDTO activation;
    private WorkflowBomSyncPreflightResponse bomSync;
    private String idempotencyKey;
    private boolean replayed;
}
