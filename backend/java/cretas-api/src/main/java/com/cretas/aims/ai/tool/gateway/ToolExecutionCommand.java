package com.cretas.aims.ai.tool.gateway;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Optional;

/**
 * Immutable request accepted by the future tool execution gateway.
 *
 * <p>The principal is a separate mandatory value. No factory or fallback reads tenant, user,
 * role, or permission data from {@link #parameters()}.</p>
 */
public record ToolExecutionCommand(
        String requestId,
        String correlationId,
        String traceId,
        String toolName,
        String expectedDescriptorVersion,
        JsonNode parameters,
        ExecutionPrincipal principal,
        ToolExecutionSource source,
        ToolExecutionMode mode,
        Optional<String> idempotencyKey,
        Optional<ConfirmationProof> confirmationProof,
        Optional<ApprovalProof> approvalProof,
        Instant deadline) {

    public ToolExecutionCommand {
        requestId = ContractValidation.requireNonBlank(requestId, "requestId");
        correlationId = ContractValidation.requireNonBlank(correlationId, "correlationId");
        traceId = ContractValidation.requireNonBlank(traceId, "traceId");
        toolName = ContractValidation.requireNonBlank(toolName, "toolName");
        expectedDescriptorVersion = ContractValidation.requireNonBlank(
                expectedDescriptorVersion, "expectedDescriptorVersion");
        parameters = ContractValidation.requireNonNull(parameters, "parameters");
        if (!parameters.isObject()) {
            throw new IllegalArgumentException("parameters must be a JSON object");
        }
        parameters = parameters.deepCopy();
        principal = ContractValidation.requireNonNull(principal, "principal");
        source = ContractValidation.requireNonNull(source, "source");
        mode = ContractValidation.requireNonNull(mode, "mode");
        idempotencyKey = ContractValidation.optionalNonBlank(idempotencyKey, "idempotencyKey");
        confirmationProof = ContractValidation.immutableOptional(
                confirmationProof, "confirmationProof");
        approvalProof = ContractValidation.immutableOptional(approvalProof, "approvalProof");
        deadline = ContractValidation.requireNonNull(deadline, "deadline");
    }

    @Override
    public JsonNode parameters() {
        return parameters.deepCopy();
    }
}
