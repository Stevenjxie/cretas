package com.cretas.aims.ai.tool.gateway;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolExecutionResult(
        String requestId,
        String toolName,
        String descriptorVersion,
        String auditEventId,
        String traceId,
        ToolExecutionStatus status,
        JsonNode payload,
        String message,
        boolean replayed) {

    public ToolExecutionResult {
        requestId = ContractValidation.requireNonBlank(requestId, "requestId");
        toolName = ContractValidation.requireNonBlank(toolName, "toolName");
        descriptorVersion = ContractValidation.requireNonBlank(
                descriptorVersion, "descriptorVersion");
        auditEventId = ContractValidation.requireNonBlank(auditEventId, "auditEventId");
        traceId = ContractValidation.requireNonBlank(traceId, "traceId");
        status = ContractValidation.requireNonNull(status, "status");
        payload = ContractValidation.requireNonNull(payload, "payload").deepCopy();
        message = ContractValidation.requireNonBlank(message, "message");
        if (replayed != (status == ToolExecutionStatus.IDEMPOTENT_REPLAY)) {
            throw new IllegalArgumentException(
                    "replayed must be true exactly when status is IDEMPOTENT_REPLAY");
        }
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
