package com.cretas.aims.ai.tool.gateway.descriptor;

/** Source-level declaration flags captured without instantiating Spring tool beans. */
public record ToolDescriptorOverrideFlags(
        boolean actionType,
        boolean riskLevel,
        boolean supportsPreview,
        boolean requiresPermission,
        boolean hasPermission,
        boolean requiredPermissions,
        boolean version,
        boolean domainTags) {
}
