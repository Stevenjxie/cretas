package com.cretas.aims.dto.unit;

/** Read-only unit-governance finding with enough location data for remediation. */
public record UnitGovernanceConflictDTO(
        String factoryId,
        String productTypeId,
        Integer workflowVersion,
        String nodeId,
        String portId,
        String current,
        String expected,
        String errorCode) {
}
