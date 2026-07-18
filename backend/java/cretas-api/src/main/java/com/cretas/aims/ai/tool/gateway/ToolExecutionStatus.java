package com.cretas.aims.ai.tool.gateway;

public enum ToolExecutionStatus {
    SUCCEEDED,
    FAILED,
    DENIED,
    CONFIRMATION_REQUIRED,
    APPROVAL_REQUIRED,
    PREVIEW_UNSUPPORTED,
    TIMEOUT,
    CANCELLED,
    IDEMPOTENT_REPLAY
}
