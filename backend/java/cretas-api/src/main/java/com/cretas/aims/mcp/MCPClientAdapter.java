package com.cretas.aims.mcp;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalEndpoint;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalHttpClientFactory;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalRuntimeRegistry;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalSchemaDigest;
import com.cretas.aims.ai.tool.gateway.mcp.MCPExternalToolPolicy;
import com.cretas.aims.mcp.MCPProtocol.MCPRequest;
import com.cretas.aims.mcp.MCPProtocol.MCPResponse;
import com.cretas.aims.mcp.MCPProtocol.MCPToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Discovers only locally allowlisted outbound MCP tools.
 *
 * <p>The legacy URL list is intentionally not an authority. With no complete local policy this
 * component performs no network calls and registers zero external capabilities. Validated proxies
 * enter an isolated runtime registry, never the ordinary {@link ToolRegistry}.</p>
 */
@Slf4j
@Service
public class MCPClientAdapter {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final MCPExternalProperties properties;
    private final MCPExternalRuntimeRegistry externalRuntimeRegistry;
    private final RestTemplate restTemplate;

    @Autowired
    public MCPClientAdapter(
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            MCPExternalProperties properties,
            MCPExternalRuntimeRegistry externalRuntimeRegistry) {
        this(toolRegistry, objectMapper, properties, externalRuntimeRegistry,
                MCPExternalHttpClientFactory.create());
    }

    MCPClientAdapter(
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            MCPExternalProperties properties,
            MCPExternalRuntimeRegistry externalRuntimeRegistry,
            RestTemplate restTemplate) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.externalRuntimeRegistry = externalRuntimeRegistry;
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    public void discoverAndRegister() {
        List<MCPExternalToolPolicy> policies = properties.validatedPolicies();
        validatePolicySet(policies);

        if (policies.isEmpty()) {
            if (properties.getExternalServers() != null
                    && !properties.getExternalServers().isBlank()) {
                log.warn("MCP Client: ignoring legacy cretas.mcp.external-servers because no "
                        + "governed external-tools policy is configured");
            }
            log.info("MCP Client: no governed outbound policies; registered 0 external tools");
            return;
        }

        Map<MCPExternalEndpoint, List<MCPExternalToolPolicy>> byEndpoint = new LinkedHashMap<>();
        policies.forEach(policy -> byEndpoint
                .computeIfAbsent(policy.endpoint(), ignored -> new ArrayList<>())
                .add(policy));

        int registered = 0;
        for (Map.Entry<MCPExternalEndpoint, List<MCPExternalToolPolicy>> entry : byEndpoint.entrySet()) {
            try {
                registered += discoverEndpoint(entry.getKey(), entry.getValue());
            } catch (RuntimeException exception) {
                log.warn("MCP Client: server {} discovery rejected: {}",
                        entry.getKey().serverId(), exception.getMessage());
            }
        }
        log.info("MCP Client: governed discovery completed; registered {} external tools", registered);
    }

    private int discoverEndpoint(
            MCPExternalEndpoint endpoint,
            List<MCPExternalToolPolicy> policies) {
        List<MCPToolDefinition> remoteTools = fetchTools(endpoint);
        Map<String, List<MCPToolDefinition>> byName = new HashMap<>();
        for (MCPToolDefinition definition : remoteTools) {
            if (definition.getName() != null) {
                byName.computeIfAbsent(definition.getName(), ignored -> new ArrayList<>())
                        .add(definition);
            }
        }

        int registered = 0;
        for (MCPExternalToolPolicy policy : policies) {
            List<MCPToolDefinition> matches = byName.getOrDefault(policy.remoteToolName(), List.of());
            if (matches.size() != 1) {
                if (matches.size() > 1) {
                    log.warn("MCP Client: duplicate remote definition rejected: {}",
                            policy.identity());
                }
                continue;
            }

            MCPToolDefinition definition = matches.getFirst();
            if (definition.getInputSchema() == null) {
                log.warn("MCP Client: missing schema rejected: {}", policy.identity());
                continue;
            }
            String actualDigest = MCPExternalSchemaDigest.sha256(
                    definition.getInputSchema(), objectMapper);
            if (!MCPExternalSchemaDigest.matches(policy.schemaDigest(), actualDigest)) {
                log.warn("MCP Client: schema drift rejected: {}", policy.identity());
                continue;
            }

            if (toolRegistry.hasExecutor(policy.localToolName())) {
                log.warn("MCP Client: local tool name collision rejected: {}",
                        policy.localToolName());
                continue;
            }
            if (externalRuntimeRegistry.hasCapability(policy.localToolName())) {
                log.warn("MCP Client: duplicate external runtime capability rejected: {}",
                        policy.localToolName());
                continue;
            }

            MCPToolProxy proxy = new MCPToolProxy(
                    policy, definition.getInputSchema(), restTemplate, objectMapper);
            if (externalRuntimeRegistry.registerValidated(proxy)) {
                registered++;
                log.info("MCP Client: isolated governed external tool {} ({})",
                        policy.localToolName(), policy.identity());
            }
        }
        return registered;
    }

    private List<MCPToolDefinition> fetchTools(MCPExternalEndpoint endpoint) {
        URI listUri = endpoint.toolsListUri();
        MCPRequest request = MCPRequest.builder()
                .id(UUID.randomUUID().toString())
                .method("tools/list")
                .params(Map.of())
                .build();
        ResponseEntity<MCPResponse> response = restTemplate.exchange(
                listUri,
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                MCPResponse.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("tools/list returned HTTP "
                    + response.getStatusCode().value());
        }

        MCPResponse body = response.getBody();
        if (body == null || body.getError() != null || !(body.getResult() instanceof Map<?, ?> result)) {
            throw new IllegalStateException("tools/list returned an invalid MCP response");
        }
        Object rawTools = result.get("tools");
        if (!(rawTools instanceof List<?> list)) {
            throw new IllegalStateException("tools/list result.tools must be an array");
        }

        List<MCPToolDefinition> definitions = new ArrayList<>();
        for (Object rawTool : list) {
            if (!(rawTool instanceof Map<?, ?> map)) {
                continue;
            }
            Object name = map.get("name");
            Object schema = map.get("inputSchema");
            if (!(name instanceof String remoteName) || remoteName.isBlank()
                    || !(schema instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> inputSchema = objectMapper.convertValue(
                    schema, new TypeReference<>() { });
            definitions.add(MCPToolDefinition.builder()
                    .name(remoteName)
                    .inputSchema(inputSchema)
                    .build());
        }
        return definitions;
    }

    private void validatePolicySet(List<MCPExternalToolPolicy> policies) {
        Set<String> identities = new LinkedHashSet<>();
        Set<String> localNames = new LinkedHashSet<>();
        Map<String, MCPExternalEndpoint> endpointByServer = new HashMap<>();
        for (MCPExternalToolPolicy policy : policies) {
            if (!identities.add(policy.identity())) {
                throw new IllegalStateException("duplicate outbound MCP policy identity: "
                        + policy.identity());
            }
            if (!localNames.add(policy.localToolName())) {
                throw new IllegalStateException("duplicate outbound MCP localToolName: "
                        + policy.localToolName());
            }
            MCPExternalEndpoint previous = endpointByServer.putIfAbsent(
                    policy.endpoint().serverId(), policy.endpoint());
            if (previous != null && !previous.equals(policy.endpoint())) {
                throw new IllegalStateException("serverId is bound to multiple origin/path values: "
                        + policy.endpoint().serverId());
            }
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
