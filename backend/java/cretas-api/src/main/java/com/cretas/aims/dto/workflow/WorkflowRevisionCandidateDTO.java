package com.cretas.aims.dto.workflow;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class WorkflowRevisionCandidateDTO {
    private Long revisionId;
    private Long workflowId;
    private Integer definitionVersion;
    private Integer revisionNumber;
    private String revisionHash;
    private String status;
    private LocalDateTime savedAt;
    private Integer processCount;
    private boolean enabled;
    private boolean compatible;
    private String incompatibilityReason;
    private boolean recommended;
}
