package com.cretas.aims.ai.tool.gateway.descriptor;

/**
 * Human governance state for an inventory descriptor.
 *
 * <p>The inventory is not a runtime authorization policy. In particular, empty permissions and
 * legacy-inferred metadata never imply approval.</p>
 */
public enum ToolGovernanceStatus {
    REVIEW_REQUIRED,
    REVIEW_REQUIRED_P0,
    APPROVED,
    WAIVED
}
