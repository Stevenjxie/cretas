package com.cretas.aims.dto.bom;

import lombok.Data;

@Data
public class BomWorkflowRevisionPinRequest {
    private Long revisionId;
    private Long workflowId;
    private String revisionHash;
}
