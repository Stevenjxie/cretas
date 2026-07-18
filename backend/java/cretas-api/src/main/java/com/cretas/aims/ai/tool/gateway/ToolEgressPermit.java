package com.cretas.aims.ai.tool.gateway;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A short-lived, command-bound capability for one Tool execution's approved egress destinations. */
public final class ToolEgressPermit {

    private static final String RESERVED_CONTEXT_KEY =
            "com.cretas.aims.ai.tool.gateway.ToolEgressPermit.v1";

    private final String toolName;
    private final String toolVersion;
    private final String requestId;
    private final Instant deadline;
    private final Set<String> allowedDestinationIds;

    ToolEgressPermit(
            String toolName,
            String toolVersion,
            String requestId,
            Instant deadline,
            Set<String> allowedDestinationIds) {
        this.toolName = ContractValidation.requireNonBlank(toolName, "toolName");
        this.toolVersion = ContractValidation.requireNonBlank(toolVersion, "toolVersion");
        this.requestId = ContractValidation.requireNonBlank(requestId, "requestId");
        this.deadline = ContractValidation.requireNonNull(deadline, "deadline");
        this.allowedDestinationIds = ContractValidation.immutableNonBlankSet(
                allowedDestinationIds, "allowedDestinationIds");
        if (this.allowedDestinationIds.isEmpty()) {
            throw new IllegalArgumentException("allowedDestinationIds must contain at least one value");
        }
    }

    /** Safely extracts the permit injected by the gateway, without exposing its reserved key. */
    public static Optional<ToolEgressPermit> fromContext(Map<String, Object> context) {
        if (context == null) {
            return Optional.empty();
        }
        Object candidate = context.get(RESERVED_CONTEXT_KEY);
        return candidate instanceof ToolEgressPermit permit
                ? Optional.of(permit)
                : Optional.empty();
    }

    /**
     * Atomically requires the exact command binding and approved destination while this capability
     * is live. No trimming, prefix matching, wildcard matching, case normalization, or URL
     * normalization is performed.
     */
    public void requireExact(
            String expectedToolName,
            String expectedToolVersion,
            String expectedRequestId,
            String destinationId) {
        if (expectedToolName == null
                || expectedToolName.isBlank()
                || expectedToolVersion == null
                || expectedToolVersion.isBlank()
                || expectedRequestId == null
                || expectedRequestId.isBlank()
                || destinationId == null
                || destinationId.isBlank()
                || !Instant.now().isBefore(deadline)
                || !toolName.equals(expectedToolName)
                || !toolVersion.equals(expectedToolVersion)
                || !requestId.equals(expectedRequestId)
                || !allowedDestinationIds.contains(destinationId)) {
            throw new SecurityException("Tool egress destination is not permitted");
        }
    }

    public String toolName() {
        return toolName;
    }

    public String toolVersion() {
        return toolVersion;
    }

    public String requestId() {
        return requestId;
    }

    public Instant deadline() {
        return deadline;
    }

    public Set<String> allowedDestinationIds() {
        return allowedDestinationIds;
    }

    /**
     * Copies trusted principal context, discards any prior reserved value, and optionally injects
     * the gateway-created permit. The returned map cannot be mutated by a Tool implementation.
     */
    static Map<String, Object> trustedExecutionContext(
            Map<String, Object> baseContext,
            Optional<ToolEgressPermit> permit) {
        Objects.requireNonNull(baseContext, "baseContext");
        Objects.requireNonNull(permit, "permit");
        Map<String, Object> trusted = new LinkedHashMap<>(baseContext);
        trusted.remove(RESERVED_CONTEXT_KEY);
        permit.ifPresent(value -> trusted.put(RESERVED_CONTEXT_KEY, value));
        return Map.copyOf(trusted);
    }
}
