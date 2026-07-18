package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;

import java.util.Map;

/** Immutable aggregate view exposed by the inventory catalog. */
public record ToolDescriptorStatistics(
        int total,
        int legacy,
        Map<ToolExecutor.ActionType, Long> actionTypes,
        Map<ToolExecutor.RiskLevel, Long> riskLevels,
        long previewSupported,
        long requiresPermission,
        Map<ToolGovernanceStatus, Long> governanceStatuses) {
}
