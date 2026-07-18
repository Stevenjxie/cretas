package com.cretas.aims.mcp;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalToolPolicy;
import com.cretas.aims.mcp.MCPProtocol.MCPError;
import com.cretas.aims.mcp.MCPProtocol.MCPRequest;
import com.cretas.aims.mcp.MCPProtocol.MCPResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Local-policy-governed proxy for exactly one remote MCP tool. */
@Slf4j
public class MCPToolProxy implements ToolExecutor {

    private final MCPExternalToolPolicy policy;
    private final Map<String, Object> inputSchema;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public MCPToolProxy(
            MCPExternalToolPolicy policy,
            Map<String, Object> inputSchema,
            RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.policy = policy;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        JsonNode schemaNode = objectMapper.valueToTree(inputSchema);
        if (!schemaNode.isObject()) {
            throw new IllegalArgumentException("inputSchema must be a JSON object");
        }
        this.inputSchema = objectMapper.convertValue(schemaNode.deepCopy(), new TypeReference<>() { });
    }

    @Override
    public String getToolName() {
        return policy.localToolName();
    }

    @Override
    public String getDescription() {
        return policy.description();
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return objectMapper.convertValue(
                objectMapper.valueToTree(inputSchema).deepCopy(), new TypeReference<>() { });
    }

    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
        validateToolCall(toolCall);
        ToolExecutionSource source = executionSource(context);
        if (!policy.allowedSources().contains(source)) {
            throw new SecurityException("MCP tool source is not allowed by local policy");
        }

        JsonNode argumentsNode;
        try {
            argumentsNode = objectMapper.readTree(toolCall.getFunction().getArguments());
        } catch (Exception exception) {
            throw new IllegalArgumentException("MCP tool arguments must be valid JSON", exception);
        }
        if (argumentsNode == null || !argumentsNode.isObject()) {
            throw new IllegalArgumentException("MCP tool arguments must be a JSON object");
        }
        Map<String, Object> arguments = objectMapper.convertValue(
                argumentsNode, new TypeReference<>() { });

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", policy.remoteToolName());
        params.put("arguments", arguments);
        Map<String, Object> egressContext = minimalContext(context);
        if (!egressContext.isEmpty()) {
            params.put("context", egressContext);
        }

        MCPRequest request = MCPRequest.builder()
                .id(toolCall.getId() == null || toolCall.getId().isBlank()
                        ? UUID.randomUUID().toString()
                        : toolCall.getId())
                .method("tools/call")
                .params(params)
                .build();

        log.info("MCP Proxy: invoking serverId={}, localTool={}",
                policy.endpoint().serverId(), policy.localToolName());
        ResponseEntity<MCPResponse> response = restTemplate.exchange(
                policy.endpoint().toolsCallUri(),
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                MCPResponse.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("MCP tools/call returned HTTP "
                    + response.getStatusCode().value());
        }
        MCPResponse body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("MCP tools/call returned an empty response");
        }
        if (body.getError() != null) {
            MCPError error = body.getError();
            throw new IllegalStateException("MCP tools/call error [" + error.getCode() + "]: "
                    + error.getMessage());
        }
        return objectMapper.writeValueAsString(body.getResult());
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    /** Legacy role-only paths cannot authorize permission-code-governed external tools. */
    @Override
    public boolean hasPermission(String userRole) {
        return false;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return policy.requiredPermissions();
    }

    @Override
    public ActionType getActionType() {
        return policy.actionType();
    }

    @Override
    public RiskLevel getRiskLevel() {
        return policy.riskLevel();
    }

    @Override
    public String getVersion() {
        return policy.version();
    }

    @Override
    public Set<String> getDomainTags() {
        return policy.domainTags();
    }

    MCPExternalToolPolicy getPolicy() {
        return policy;
    }

    private void validateToolCall(ToolCall toolCall) {
        if (toolCall == null || toolCall.getFunction() == null) {
            throw new IllegalArgumentException("MCP tool call/function must not be null");
        }
        if (!policy.localToolName().equals(toolCall.getFunction().getName())) {
            throw new IllegalArgumentException("MCP tool call name does not match local policy");
        }
        if (toolCall.getFunction().getArguments() == null
                || toolCall.getFunction().getArguments().isBlank()) {
            throw new IllegalArgumentException("MCP tool arguments must not be blank");
        }
    }

    private ToolExecutionSource executionSource(Map<String, Object> context) {
        if (context == null) {
            throw new SecurityException("MCP execution source is required");
        }
        Object value = context.get(MCPExternalToolPolicy.EXECUTION_SOURCE_CONTEXT_KEY);
        if (value instanceof ToolExecutionSource source) {
            return source;
        }
        if (value instanceof String source && !source.isBlank()) {
            try {
                return ToolExecutionSource.valueOf(source.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new SecurityException("MCP execution source is invalid", exception);
            }
        }
        throw new SecurityException("MCP execution source is required");
    }

    private Map<String, Object> minimalContext(Map<String, Object> context) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : policy.egressContextFields()) {
            if (context.containsKey(field)) {
                result.put(field, context.get(field));
            }
        }
        return result;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
