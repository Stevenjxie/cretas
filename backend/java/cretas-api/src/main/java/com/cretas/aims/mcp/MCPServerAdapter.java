package com.cretas.aims.mcp;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.mcp.MCPProtocol.*;
import com.cretas.aims.util.ErrorSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP Server Adapter — 将内部 ToolRegistry 暴露为 MCP 兼容的 HTTP 端点
 *
 * <p>实现两个核心 MCP 方法：
 * <ul>
 *   <li>{@code tools/list} — 列出所有已注册工具及其 schema</li>
 *   <li>{@code tools/call} — 根据工具名称和参数执行工具</li>
 * </ul>
 *
 * <p>安全边界：端点仅在 {@code cretas.mcp.enabled=true} 时注册，且启用时必须配置
 * API Key、精确 Tool allowlist 与服务端 Principal。MCP Phase 0 只暴露 allowlist 中的
 * READ/ANALYZE 工具；调用者提交的 context 不会进入工具执行上下文，tenant/user/role
 * 均由服务端配置注入。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-09
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
@ConditionalOnProperty(prefix = "cretas.mcp", name = "enabled", havingValue = "true")
public class MCPServerAdapter {

    private static final Set<String> MCP_IDENTITY_FIELDS = Set.of(
            "factoryid", "tenantid", "userid", "userrole");

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final WriteGuardService writeGuardService;
    private final String apiKey;
    private final String principalFactoryId;
    private final Long principalUserId;
    private final String principalUserRole;
    private final Set<String> allowedTools;

    public MCPServerAdapter(
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            WriteGuardService writeGuardService,
            @Value("${cretas.mcp.api-key:}") String apiKey,
            @Value("${cretas.mcp.principal.factory-id:}") String principalFactoryId,
            @Value("${cretas.mcp.principal.user-id:}") String principalUserId,
            @Value("${cretas.mcp.principal.user-role:}") String principalUserRole,
            @Value("${cretas.mcp.allowed-tools:}") String allowedTools) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.writeGuardService = Objects.requireNonNull(writeGuardService, "writeGuardService");
        this.apiKey = requireConfigured(apiKey, "cretas.mcp.api-key", false);
        this.principalFactoryId = requireConfigured(
                principalFactoryId, "cretas.mcp.principal.factory-id", true);
        String configuredUserId = requireConfigured(
                principalUserId, "cretas.mcp.principal.user-id", true);
        this.principalUserRole = requireConfigured(
                principalUserRole, "cretas.mcp.principal.user-role", true);
        this.allowedTools = parseAllowedTools(allowedTools);
        try {
            this.principalUserId = Long.valueOf(configuredUserId);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "MCP is enabled but cretas.mcp.principal.user-id is not a valid Long", e);
        }
    }

    /**
     * tools/list — 列出所有已注册的工具定义
     *
     * <p>请求体示例:
     * <pre>
     * { "jsonrpc": "2.0", "id": "1", "method": "tools/list" }
     * </pre>
     *
     * <p>响应示例:
     * <pre>
     * {
     *   "jsonrpc": "2.0",
     *   "id": "1",
     *   "result": {
     *     "tools": [
     *       { "name": "material_batch_query", "description": "...", "inputSchema": {...} }
     *     ]
     *   }
     * }
     * </pre>
     *
     * @param request MCP JSON-RPC 请求
     * @param mcpApiKey 可选的 API Key 请求头
     * @return MCP 响应，包含工具列表
     */
    @PostMapping("/tools/list")
    public ResponseEntity<MCPResponse> listTools(
            @RequestBody(required = false) MCPRequest request,
            @RequestHeader(value = "X-MCP-API-Key", required = false) String mcpApiKey) {

        String requestId = request != null ? request.getId() : null;

        // 认证检查
        if (!authenticateRequest(mcpApiKey)) {
            log.warn("MCP tools/list 认证失败");
            return ResponseEntity.status(401)
                    .body(MCPResponse.error(requestId, MCPProtocol.ERROR_INVALID_REQUEST, "Invalid or missing API key"));
        }

        try {
            List<MCPToolDefinition> tools = toolRegistry.getAllToolNames().stream()
                    .map(name -> {
                        Optional<ToolExecutor> executor = toolRegistry.getExecutor(name);
                        if (executor.isEmpty()) return null;
                        ToolExecutor exec = executor.get();
                        if (!allowedTools.contains(name)
                                || !isMcpReadOnlyTool(exec)
                                || !toolRegistry.isToolEnabledForFactory(principalFactoryId, name)) {
                            return null;
                        }
                        return MCPToolDefinition.builder()
                                .name(exec.getToolName())
                                .description(exec.getDescription())
                                .inputSchema(exec.getParametersSchema())
                                .build();
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tools", tools);

            log.info("MCP tools/list: 返回 {} 个工具", tools.size());
            return ResponseEntity.ok(MCPResponse.success(requestId, result));

        } catch (Exception e) {
            log.error("MCP tools/list 执行失败", e);
            return ResponseEntity.internalServerError()
                    .body(MCPResponse.error(requestId, MCPProtocol.ERROR_INTERNAL,
                            ErrorSanitizer.sanitize(e)));
        }
    }

    /**
     * tools/call — 调用指定工具
     *
     * <p>请求体示例:
     * <pre>
     * {
     *   "jsonrpc": "2.0",
     *   "id": "2",
     *   "method": "tools/call",
     *   "params": {
     *     "name": "material_batch_query",
     *     "arguments": { "batchNumber": "B001" }
     *   }
     * }
     * </pre>
     *
     * @param request MCP JSON-RPC 请求
     * @param mcpApiKey 可选的 API Key 请求头
     * @return MCP 响应，包含工具执行结果
     */
    @PostMapping("/tools/call")
    public ResponseEntity<MCPResponse> callTool(
            @RequestBody MCPRequest request,
            @RequestHeader(value = "X-MCP-API-Key", required = false) String mcpApiKey) {

        String requestId = request != null ? request.getId() : null;

        // 认证检查
        if (!authenticateRequest(mcpApiKey)) {
            log.warn("MCP tools/call 认证失败");
            return ResponseEntity.status(401)
                    .body(MCPResponse.error(requestId, MCPProtocol.ERROR_INVALID_REQUEST, "Invalid or missing API key"));
        }

        if (request == null || request.getParams() == null) {
            return ResponseEntity.badRequest()
                    .body(MCPResponse.error(requestId, MCPProtocol.ERROR_INVALID_PARAMS, "Missing params"));
        }

        Map<String, Object> params = request.getParams();
        Object rawToolName = params.get("name");
        if (!(rawToolName instanceof String toolName) || toolName.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(MCPResponse.error(requestId, MCPProtocol.ERROR_INVALID_PARAMS, "Missing tool name in params.name"));
        }

        // 查找工具
        Optional<ToolExecutor> executorOpt = toolRegistry.getExecutor(toolName);
        if (executorOpt.isEmpty()) {
            log.warn("MCP tools/call: 工具不存在 - {}", toolName);
            return ResponseEntity.ok(
                    MCPResponse.error(requestId, MCPProtocol.ERROR_METHOD_NOT_FOUND,
                            "Tool not found: " + toolName));
        }

        try {
            ToolExecutor executor = executorOpt.get();

            if (!allowedTools.contains(toolName)) {
                log.warn("MCP tools/call: 工具不在显式 allowlist - tool={}", toolName);
                return ResponseEntity.status(403)
                        .body(MCPResponse.error(requestId, MCPProtocol.ERROR_INVALID_REQUEST,
                                "Tool is not allowed for the configured MCP server"));
            }

            if (!toolRegistry.isToolEnabledForFactory(principalFactoryId, toolName)) {
                log.warn("MCP tools/call: 工具未对服务端工厂启用 - tool={}, factoryId={}",
                        toolName, principalFactoryId);
                return ResponseEntity.status(403)
                        .body(MCPResponse.error(requestId, MCPProtocol.ERROR_INVALID_REQUEST,
                                "Tool is disabled for the configured MCP principal"));
            }

            // Gateway 落地前 MCP 严格只读；禁止通过 API Key 直接触发写操作、通知或产物生成。
            if (!isMcpReadOnlyTool(executor)) {
                log.warn("MCP tools/call: 拒绝非只读工具 - tool={}, actionType={}",
                        toolName, executor.getActionType());
                return ResponseEntity.status(403)
                        .body(MCPResponse.error(requestId, MCPProtocol.ERROR_INVALID_REQUEST,
                                "MCP Phase 0 only permits READ and ANALYZE tools"));
            }

            // 构建 ToolCall 对象
            String argumentsJson;
            try {
                argumentsJson = serializeValidatedArguments(params.get("arguments"));
            } catch (IllegalArgumentException e) {
                log.warn("MCP tools/call: 参数拒绝 - tool={}, reason={}", toolName, e.getMessage());
                return ResponseEntity.badRequest()
                        .body(MCPResponse.error(requestId, MCPProtocol.ERROR_INVALID_PARAMS,
                                e.getMessage()));
            }

            ToolCall toolCall = ToolCall.of(
                    requestId != null ? requestId : UUID.randomUUID().toString(),
                    toolName,
                    argumentsJson
            );

            // 调用者 context 不是身份凭证，必须完全忽略；执行身份只来自服务端配置。
            if (params.containsKey("context")) {
                log.debug("MCP tools/call: 忽略调用者提交的 context, tool={}", toolName);
            }
            Map<String, Object> context = buildServerPrincipalContext();

            // 执行工具
            log.info("MCP tools/call: 执行工具 {} (factoryId={}, userId={})",
                    toolName, principalFactoryId, principalUserId);
            String resultJson = executor.execute(toolCall, context);

            Map<String, Object> resultWrapper = new LinkedHashMap<>();
            resultWrapper.put("content", List.of(Map.of("type", "text", "text", resultJson)));
            resultWrapper.put("isError", false);

            return ResponseEntity.ok(MCPResponse.success(requestId, resultWrapper));

        } catch (Exception e) {
            log.error("MCP tools/call 执行失败: tool={}", toolName, e);

            String safeMessage = ErrorSanitizer.sanitize(e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("content", List.of(Map.of("type", "text", "text", safeMessage)));
            errorResult.put("isError", true);

            return ResponseEntity.ok(MCPResponse.success(requestId, errorResult));
        }
    }

    /**
     * 校验 API Key
     *
     * @param providedKey 请求中携带的 API Key
     * @return true 表示认证通过
     */
    private boolean authenticateRequest(String providedKey) {
        if (providedKey == null || providedKey.isBlank()) return false;
        return MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8),
                providedKey.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isMcpReadOnlyTool(ToolExecutor executor) {
        if (writeGuardService.isWriteTool(executor)) return false;
        ToolExecutor.ActionType actionType = executor.getActionType();
        return actionType == ToolExecutor.ActionType.READ
                || actionType == ToolExecutor.ActionType.ANALYZE;
    }

    private Map<String, Object> buildServerPrincipalContext() {
        Map<String, Object> context = new HashMap<>();
        context.put("factoryId", principalFactoryId);
        context.put("userId", principalUserId);
        context.put("userRole", principalUserRole);
        context.put("source", "mcp");
        return context;
    }

    private String serializeValidatedArguments(Object arguments) {
        if (arguments == null) return "{}";
        Object normalized = arguments;
        try {
            if (arguments instanceof String stringArguments) {
                normalized = objectMapper.readValue(stringArguments, Object.class);
            }
            rejectCallerIdentityFields(normalized);
            return objectMapper.writeValueAsString(normalized);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("MCP arguments must be valid JSON", e);
        }
    }

    private void rejectCallerIdentityFields(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String normalizedKey = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (MCP_IDENTITY_FIELDS.contains(normalizedKey)) {
                    throw new IllegalArgumentException(
                            "Caller-supplied identity field is not allowed: " + key);
                }
                rejectCallerIdentityFields(entry.getValue());
            }
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(this::rejectCallerIdentityFields);
        }
    }

    private static String requireConfigured(String value, String propertyName, boolean trim) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("MCP is enabled but " + propertyName + " is not configured");
        }
        return trim ? value.trim() : value;
    }

    private static Set<String> parseAllowedTools(String value) {
        String configured = requireConfigured(value, "cretas.mcp.allowed-tools", true);
        Set<String> tools = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (tools.isEmpty()) {
            throw new IllegalStateException(
                    "MCP is enabled but cretas.mcp.allowed-tools contains no tool names");
        }
        return tools;
    }
}
