package com.cretas.aims.ai.tool.gateway.mcp;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Local governance truth for one external MCP tool. Remote metadata never populates this record. */
public record MCPExternalToolPolicy(
        MCPExternalEndpoint endpoint,
        String remoteToolName,
        String schemaDigest,
        String localToolName,
        String description,
        ToolExecutor.ActionType actionType,
        ToolExecutor.RiskLevel riskLevel,
        Set<String> requiredPermissions,
        Set<String> domainTags,
        String version,
        Set<ToolExecutionSource> allowedSources,
        Set<String> egressContextFields) {

    public static final String EXECUTION_SOURCE_CONTEXT_KEY = "toolExecutionSource";

    private static final Pattern REMOTE_TOOL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern LOCAL_TOOL_NAME = Pattern.compile("mcp_[A-Za-z0-9][A-Za-z0-9_-]{0,123}");

    public MCPExternalToolPolicy {
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        remoteToolName = nonBlank(remoteToolName, "remoteToolName");
        if (!REMOTE_TOOL_NAME.matcher(remoteToolName).matches()) {
            throw new IllegalArgumentException("remoteToolName contains unsupported characters");
        }
        schemaDigest = nonBlank(schemaDigest, "schemaDigest").toLowerCase(Locale.ROOT);
        if (!schemaDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("schemaDigest must be 64 lowercase SHA-256 hex characters");
        }
        localToolName = nonBlank(localToolName, "localToolName");
        if (!LOCAL_TOOL_NAME.matcher(localToolName).matches()) {
            throw new IllegalArgumentException("localToolName must be an MCP-safe name starting with mcp_");
        }
        description = nonBlank(description, "description");
        if (actionType == null || riskLevel == null) {
            throw new IllegalArgumentException("actionType and riskLevel must be explicit");
        }
        requiredPermissions = nonEmptySet(requiredPermissions, "requiredPermissions");
        domainTags = nonEmptySet(domainTags, "domainTags");
        version = nonBlank(version, "version");
        allowedSources = nonEmptyEnumSet(allowedSources, "allowedSources");
        egressContextFields = immutableSet(egressContextFields, "egressContextFields");
        if (egressContextFields.contains(EXECUTION_SOURCE_CONTEXT_KEY)) {
            throw new IllegalArgumentException("execution source is policy input and cannot be an egress field");
        }
    }

    public String identity() {
        return endpoint.serverId() + ":" + remoteToolName;
    }

    private static String nonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Set<String> nonEmptySet(Set<String> values, String field) {
        Set<String> result = immutableSet(values, field);
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return result;
    }

    private static Set<String> immutableSet(Set<String> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            copy.add(nonBlank(value, field));
        }
        return Set.copyOf(copy);
    }

    private static Set<ToolExecutionSource> nonEmptyEnumSet(
            Set<ToolExecutionSource> values, String field) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(field + " must contain explicit values");
        }
        for (ToolExecutionSource value : values) {
            if (value == null) {
                throw new IllegalArgumentException(field + " must contain explicit values");
            }
        }
        return Set.copyOf(values);
    }
}
