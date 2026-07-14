package com.cretas.aims.dto.workflow;

public record WorkflowUnitIssueDTO(
        String code,
        String message,
        String nodeId,
        String portId,
        String currentUnit,
        String expectedUnit) {
}
