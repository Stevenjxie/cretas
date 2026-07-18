package com.cretas.aims.mcp;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalEndpoint;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalToolPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fail-closed local configuration for outbound MCP capabilities. */
@Component
@ConfigurationProperties(prefix = "cretas.mcp")
public class MCPExternalProperties {

    /** Retained only to detect and ignore the unsafe legacy URL list. */
    private String externalServers;
    private List<ExternalTool> externalTools = new ArrayList<>();

    public String getExternalServers() {
        return externalServers;
    }

    public void setExternalServers(String externalServers) {
        this.externalServers = externalServers;
    }

    public List<ExternalTool> getExternalTools() {
        return externalTools;
    }

    public void setExternalTools(List<ExternalTool> externalTools) {
        this.externalTools = externalTools == null ? new ArrayList<>() : new ArrayList<>(externalTools);
    }

    public List<MCPExternalToolPolicy> validatedPolicies() {
        List<MCPExternalToolPolicy> policies = new ArrayList<>();
        for (int index = 0; index < externalTools.size(); index++) {
            try {
                policies.add(externalTools.get(index).toPolicy());
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Invalid cretas.mcp.external-tools[" + index + "]: "
                        + exception.getMessage(), exception);
            }
        }
        return List.copyOf(policies);
    }

    public static class ExternalTool {
        private String serverId;
        private String origin;
        private String basePath;
        private String remoteToolName;
        private String schemaDigest;
        private String localToolName;
        private String description;
        private String actionType;
        private String riskLevel;
        private List<String> requiredPermissions = new ArrayList<>();
        private List<String> domainTags = new ArrayList<>();
        private String version;
        private List<String> allowedSources = new ArrayList<>();
        private List<String> egressContextFields = new ArrayList<>();

        MCPExternalToolPolicy toPolicy() {
            return new MCPExternalToolPolicy(
                    MCPExternalEndpoint.of(serverId, origin, basePath),
                    remoteToolName,
                    schemaDigest,
                    localToolName,
                    description,
                    parseEnum(ToolExecutor.ActionType.class, actionType, "actionType"),
                    parseEnum(ToolExecutor.RiskLevel.class, riskLevel, "riskLevel"),
                    stringSet(requiredPermissions),
                    stringSet(domainTags),
                    version,
                    enumSet(ToolExecutionSource.class, allowedSources, "allowedSources"),
                    stringSet(egressContextFields));
        }

        private static Set<String> stringSet(List<String> values) {
            return values == null ? Set.of() : new LinkedHashSet<>(values);
        }

        private static <E extends Enum<E>> E parseEnum(
                Class<E> type, String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must be explicit");
            }
            try {
                return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(field + " has unsupported value " + value, exception);
            }
        }

        private static <E extends Enum<E>> Set<E> enumSet(
                Class<E> type, List<String> values, String field) {
            if (values == null) {
                return Set.of();
            }
            LinkedHashSet<E> result = new LinkedHashSet<>();
            for (String value : values) {
                result.add(parseEnum(type, value, field));
            }
            return result;
        }

        public String getServerId() { return serverId; }
        public void setServerId(String serverId) { this.serverId = serverId; }
        public String getOrigin() { return origin; }
        public void setOrigin(String origin) { this.origin = origin; }
        public String getBasePath() { return basePath; }
        public void setBasePath(String basePath) { this.basePath = basePath; }
        public String getRemoteToolName() { return remoteToolName; }
        public void setRemoteToolName(String remoteToolName) { this.remoteToolName = remoteToolName; }
        public String getSchemaDigest() { return schemaDigest; }
        public void setSchemaDigest(String schemaDigest) { this.schemaDigest = schemaDigest; }
        public String getLocalToolName() { return localToolName; }
        public void setLocalToolName(String localToolName) { this.localToolName = localToolName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getActionType() { return actionType; }
        public void setActionType(String actionType) { this.actionType = actionType; }
        public String getRiskLevel() { return riskLevel; }
        public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
        public List<String> getRequiredPermissions() { return requiredPermissions; }
        public void setRequiredPermissions(List<String> requiredPermissions) { this.requiredPermissions = requiredPermissions; }
        public List<String> getDomainTags() { return domainTags; }
        public void setDomainTags(List<String> domainTags) { this.domainTags = domainTags; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public List<String> getAllowedSources() { return allowedSources; }
        public void setAllowedSources(List<String> allowedSources) { this.allowedSources = allowedSources; }
        public List<String> getEgressContextFields() { return egressContextFields; }
        public void setEgressContextFields(List<String> egressContextFields) { this.egressContextFields = egressContextFields; }
    }
}
