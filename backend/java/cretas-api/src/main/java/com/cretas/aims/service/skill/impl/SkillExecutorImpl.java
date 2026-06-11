package com.cretas.aims.service.skill.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.dto.skill.SkillResult;
import com.cretas.aims.service.skill.SkillExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Skill 执行器服务实现
 *
 * 负责执行 Skill 的完整生命周期:
 * 1. 准备 Prompt (替换模板变量)
 * 2. 调用 LLM 获取参数和执行计划
 * 3. 解析 LLM 响应，提取参数
 * 4. 执行关联的 Tools
 *
 * 集成现有服务:
 * - ToolRegistry: 获取和执行工具
 * - DashScopeClient: LLM 调用
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-18
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SkillExecutorImpl implements SkillExecutor {

    private final ToolRegistry toolRegistry;
    private final DashScopeClient dashScopeClient;
    private final ObjectMapper objectMapper;

    // W0 write-guard (intent-w0) — SITE D. Skill orchestration calls ToolExecutor.execute() directly
    // (executeSingleTool), bypassing ToolDispatchService (Site B). Field-injected (not in the Lombok
    // @RequiredArgsConstructor) so existing direct-construction tests stay unaffected; tests set it via
    // reflection. Stateless → safe on the skill worker thread pool.
    @Autowired
    private com.cretas.aims.ai.tool.WriteGuardService writeGuardService;

    // W9 红线 (AI-RBAC 系统性收口) — SITE D: central RBAC enforce alongside the W0 write-guard.
    @Autowired
    private com.cretas.aims.ai.tool.ToolRbacEnforcer toolRbacEnforcer;

    // ==================== Configuration ====================

    @Value("${ai.skill_executor.parallel_execution_enabled:true}")
    private boolean parallelExecutionEnabled;

    @Value("${ai.skill_executor.tool_timeout_seconds:30}")
    private int toolTimeoutSeconds;

    @Value("${ai.skill_executor.max_tools_per_skill:5}")
    private int maxToolsPerSkill;

    @Value("${ai.skill_executor.default_timeout_ms:60000}")
    private long defaultTimeoutMs;

    /**
     * Tool execution thread pool
     */
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    /**
     * Variable placeholder pattern: ${variableName}
     */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== Interface Implementation ====================

    @Override
    public SkillResult execute(SkillDefinition skill, SkillContext context) {
        return execute(skill, context, defaultTimeoutMs);
    }

    @Override
    public SkillResult execute(SkillDefinition skill, SkillContext context, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        List<String> executedTools = new ArrayList<>();

        log.info("Starting skill execution: skill={}, userQuery='{}', timeout={}ms",
                skill.getName(),
                truncateQuery(context.getUserQuery()),
                timeoutMs);

        try {
            // 1. Validate context
            String validationError = validateContext(skill, context);
            if (validationError != null) {
                log.warn("Context validation failed for skill {}: {}", skill.getName(), validationError);
                return SkillResult.error(skill.getName(), validationError,
                        executedTools, System.currentTimeMillis() - startTime);
            }

            // 2. Check if skill is enabled
            if (!skill.isEnabled()) {
                log.warn("Skill is disabled: {}", skill.getName());
                return SkillResult.error(skill.getName(), "Skill is disabled",
                        executedTools, System.currentTimeMillis() - startTime);
            }

            // 3. DAG mode: execute conditional branching graph
            if (skill.hasExecutionGraph()) {
                return executeDAG(skill, context, executedTools, startTime);
            }

            // 4. Deterministic mode: skip LLM for single-tool skills
            if (canExecuteDeterministically(skill, context)) {
                return executeDeterministic(skill, context, executedTools, startTime);
            }

            // 5. LLM mode: Prepare Prompt (replace template variables)
            String prompt = preparePrompt(skill, context);
            log.debug("Prepared prompt for skill {}: length={}", skill.getName(), prompt.length());

            // 5. Call LLM to get parameters and execution plan
            Map<String, Object> params;
            try {
                String llmResponse = callLlm(prompt, context.getUserQuery());
                log.debug("LLM response for skill {}: length={}", skill.getName(),
                        llmResponse != null ? llmResponse.length() : 0);
                // 6. Parse LLM response and extract parameters
                params = extractParams(llmResponse, context);
            } catch (RuntimeException llmEx) {
                // LLM unavailable (timeout / rate-limit / network) — degrade to deterministic
                // tool-list execution instead of failing the whole Skill. Each Tool gets the
                // context's factoryId/userId but no LLM-determined params; Tools that can
                // execute with defaults will succeed. Avoids "Skill 执行失败" UX regression.
                log.warn("Skill {} LLM unavailable ({}), falling back to default-tool execution",
                        skill.getName(), llmEx.getMessage());
                params = new HashMap<>();
                if (context.getFactoryId() != null) params.put("factoryId", context.getFactoryId());
                if (context.getUserId() != null) params.put("userId", context.getUserId());
                params.put("_llmFallback", "deterministic_no_llm");
            }
            context.setExtractedParams(params);
            log.info("Extracted {} parameters for skill {}", params.size(), skill.getName());

            // 7. Execute associated Tools
            Object toolResult = executeTools(skill, params, executedTools, context);

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("Skill execution completed: skill={}, tools={}, time={}ms",
                    skill.getName(), executedTools, executionTime);

            return SkillResult.builder()
                    .success(true)
                    .skillName(skill.getName())
                    .data(toolResult)
                    .executedTools(executedTools)
                    .executionTime(executionTime)
                    .build();

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Skill execution failed: skill={}, error={}",
                    skill.getName(), e.getMessage(), e);

            return SkillResult.error(skill.getName(), e.getMessage(),
                    executedTools, executionTime);
        }
    }

    /**
     * Check if a skill can be executed deterministically (without LLM).
     * Conditions: single tool + all required params available in context.
     */
    private boolean canExecuteDeterministically(SkillDefinition skill, SkillContext context) {
        List<String> tools = skill.getTools();
        if (tools == null || tools.size() != 1) {
            return false; // Multi-tool skills need LLM for orchestration
        }

        String toolName = tools.get(0);
        if (!toolRegistry.hasExecutor(toolName)) {
            return false;
        }

        // Single-tool skill with params available from context — skip LLM
        log.info("Skill {} eligible for deterministic execution (single tool: {})",
                skill.getName(), toolName);
        return true;
    }

    /**
     * Execute a single-tool skill deterministically without calling LLM.
     * Extracts params directly from context instead of LLM response.
     */
    private SkillResult executeDeterministic(SkillDefinition skill, SkillContext context,
                                              List<String> executedTools, long startTime) throws Exception {
        String toolName = skill.getTools().get(0);
        log.info("Deterministic execution: skill={}, tool={}", skill.getName(), toolName);

        // Build params from context (factoryId, userId, etc.)
        Map<String, Object> params = new HashMap<>();
        if (context.getFactoryId() != null) params.put("factoryId", context.getFactoryId());
        if (context.getUserId() != null) params.put("userId", context.getUserId());
        if (context.getExtractedParams() != null) params.putAll(context.getExtractedParams());
        context.setExtractedParams(params);

        // Execute the single tool directly
        Object result = executeSingleTool(toolName, params, context);
        executedTools.add(toolName);

        Map<String, Object> results = new LinkedHashMap<>();
        results.put(toolName, result);
        results.put("_executionOrder", "deterministic");
        results.put("_toolCount", 1);

        long executionTime = System.currentTimeMillis() - startTime;
        log.info("Deterministic execution completed: skill={}, tool={}, time={}ms",
                skill.getName(), toolName, executionTime);

        return SkillResult.builder()
                .success(true)
                .skillName(skill.getName())
                .data(results)
                .executedTools(executedTools)
                .executionTime(executionTime)
                .build();
    }

    @Override
    public String validateContext(SkillDefinition skill, SkillContext context) {
        if (context == null) {
            return "Context is null";
        }

        if (context.getUserQuery() == null || context.getUserQuery().trim().isEmpty()) {
            return "User query is empty";
        }

        // Check required context fields
        List<String> contextNeeded = skill.getContextNeeded();
        if (contextNeeded != null && !contextNeeded.isEmpty()) {
            List<String> missingFields = new ArrayList<>();

            for (String field : contextNeeded) {
                if (!hasContextField(context, field)) {
                    missingFields.add(field);
                }
            }

            if (!missingFields.isEmpty()) {
                return "Missing required context fields: " + String.join(", ", missingFields);
            }
        }

        // Check if required tools are available
        List<String> tools = skill.getTools();
        if (tools != null && !tools.isEmpty()) {
            List<String> missingTools = tools.stream()
                    .filter(t -> !toolRegistry.hasExecutor(t))
                    .collect(Collectors.toList());

            if (!missingTools.isEmpty()) {
                return "Required tools not available: " + String.join(", ", missingTools);
            }
        }

        return null; // Validation passed
    }

    @Override
    public boolean supports(SkillDefinition skill) {
        if (skill == null || skill.getName() == null) {
            return false;
        }

        // Check if all required tools are available
        List<String> tools = skill.getTools();
        if (tools == null || tools.isEmpty()) {
            return true; // Skill with no tools is supported
        }

        return tools.stream().allMatch(toolRegistry::hasExecutor);
    }

    // ==================== Private Helper Methods ====================

    /**
     * Check if context has a specific field
     */
    private boolean hasContextField(SkillContext context, String field) {
        switch (field.toLowerCase()) {
            case "factoryid":
                return context.getFactoryId() != null && !context.getFactoryId().isEmpty();
            case "userid":
                return context.getUserId() != null && !context.getUserId().isEmpty();
            case "sessionid":
                return context.getSessionId() != null && !context.getSessionId().isEmpty();
            case "userquery":
                return context.getUserQuery() != null && !context.getUserQuery().isEmpty();
            default:
                // Check in extracted params
                if (context.getExtractedParams() != null) {
                    return context.getExtractedParams().containsKey(field);
                }
                return false;
        }
    }

    /**
     * Prepare Prompt
     *
     * Replace template variables:
     * - ${userQuery}: User query
     * - ${factoryId}: Factory ID
     * - ${userId}: User ID
     * - ${tools}: Available tools list
     * - ${contextNeeded}: Required context
     * - Custom variables: From context.extractedParams
     */
    private String preparePrompt(SkillDefinition skill, SkillContext context) {
        String template = skill.getPromptTemplate();

        if (template == null || template.isEmpty()) {
            // Use default template
            template = buildDefaultPromptTemplate(skill);
        }

        // Build variable mapping
        Map<String, String> variables = new HashMap<>();
        variables.put("userQuery", context.getUserQuery() != null ? context.getUserQuery() : "");
        variables.put("factoryId", context.getFactoryId() != null ? context.getFactoryId() : "");
        variables.put("userId", context.getUserId() != null ? context.getUserId() : "");
        variables.put("sessionId", context.getSessionId() != null ? context.getSessionId() : "");
        variables.put("skillName", skill.getName());
        variables.put("skillDescription", skill.getDescription() != null ? skill.getDescription() : "");

        // Add tools list
        if (skill.getTools() != null && !skill.getTools().isEmpty()) {
            String toolsList = skill.getTools().stream()
                    .map(this::formatToolInfo)
                    .collect(Collectors.joining("\n"));
            variables.put("tools", toolsList);
        } else {
            variables.put("tools", "No specific tools defined");
        }

        // Add context requirements
        if (skill.getContextNeeded() != null && !skill.getContextNeeded().isEmpty()) {
            variables.put("contextNeeded", String.join(", ", skill.getContextNeeded()));
        } else {
            variables.put("contextNeeded", "None");
        }

        // Add extracted params from context
        if (context.getExtractedParams() != null) {
            for (Map.Entry<String, Object> entry : context.getExtractedParams().entrySet()) {
                variables.put(entry.getKey(),
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }

        // Replace variables
        return replaceVariables(template, variables);
    }

    /**
     * Build default Prompt template
     */
    private String buildDefaultPromptTemplate(SkillDefinition skill) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Role\n");
        sb.append("You are an intelligent assistant executing the '").append(skill.getDisplayName()).append("' skill.\n\n");

        if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
            sb.append("## Skill Description\n");
            sb.append(skill.getDescription()).append("\n\n");
        }

        sb.append("## User Request\n");
        sb.append("${userQuery}\n\n");

        sb.append("## Context Information\n");
        sb.append("- Factory ID: ${factoryId}\n");
        sb.append("- User ID: ${userId}\n");
        sb.append("- Required Context: ${contextNeeded}\n\n");

        if (skill.getTools() != null && !skill.getTools().isEmpty()) {
            sb.append("## Available Tools\n");
            sb.append("${tools}\n\n");
        }

        sb.append("## Task\n");
        sb.append("1. Analyze user request and extract key parameters\n");
        sb.append("2. Determine which tools to call\n");
        sb.append("3. Prepare required parameters for each tool\n\n");

        sb.append("## Output Format (Strict JSON)\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"extracted_params\": {\n");
        sb.append("    \"param_name\": \"param_value\"\n");
        sb.append("  },\n");
        sb.append("  \"tools_to_execute\": [\n");
        sb.append("    {\n");
        sb.append("      \"tool_name\": \"tool_name\",\n");
        sb.append("      \"params\": {}\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"execution_order\": \"sequential or parallel\",\n");
        sb.append("  \"reasoning\": \"Execution plan explanation\"\n");
        sb.append("}\n");
        sb.append("```\n");

        return sb.toString();
    }

    /**
     * Format tool information
     */
    private String formatToolInfo(String toolName) {
        Optional<ToolExecutor> executorOpt = toolRegistry.getExecutor(toolName);
        if (executorOpt.isPresent()) {
            ToolExecutor executor = executorOpt.get();
            return String.format("- %s: %s", toolName, executor.getDescription());
        }
        return String.format("- %s: (Tool info not available)", toolName);
    }

    /**
     * Replace template variables
     */
    private String replaceVariables(String template, Map<String, String> variables) {
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = variables.getOrDefault(varName, "");
            // Escape special characters
            replacement = Matcher.quoteReplacement(replacement);
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Call LLM
     */
    private String callLlm(String prompt, String userQuery) {
        try {
            return dashScopeClient.chat(prompt, userQuery);
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to call LLM: " + e.getMessage(), e);
        }
    }

    /**
     * Extract parameters from LLM response
     */
    private Map<String, Object> extractParams(String llmResponse, SkillContext context) {
        Map<String, Object> params = new HashMap<>();

        // Preserve existing params from context
        if (context.getExtractedParams() != null) {
            params.putAll(context.getExtractedParams());
        }

        // Add base context params
        params.put("factoryId", context.getFactoryId());
        params.put("userId", context.getUserId());
        params.put("sessionId", context.getSessionId());
        params.put("userQuery", context.getUserQuery());

        if (llmResponse == null || llmResponse.isEmpty()) {
            log.warn("Empty LLM response, returning context params only");
            return params;
        }

        try {
            // Extract JSON
            String json = extractJson(llmResponse);
            if (json == null) {
                log.warn("No JSON found in LLM response");
                return params;
            }

            JsonNode root = objectMapper.readTree(json);

            // Extract extracted_params
            if (root.has("extracted_params")) {
                JsonNode paramsNode = root.get("extracted_params");
                Map<String, Object> extractedParams = objectMapper.convertValue(
                        paramsNode, new TypeReference<Map<String, Object>>() {});
                params.putAll(extractedParams);
            }

            // Store tools_to_execute for later use
            if (root.has("tools_to_execute")) {
                params.put("_tools_to_execute", root.get("tools_to_execute"));
            }

            // Store execution_order
            if (root.has("execution_order")) {
                params.put("_execution_order", root.get("execution_order").asText());
            }

            // Store reasoning
            if (root.has("reasoning")) {
                params.put("_reasoning", root.get("reasoning").asText());
            }

        } catch (JsonProcessingException e) {
            // LLM hallucinated broken JSON (unescaped backslash, trailing comma,
            // stray markdown). Code already returns base params so the skill
            // can still run with its defaults — just missing the LLM-extracted
            // extras. WARN is the right level: quality-of-output issue, not
            // a server bug.
            log.warn("LLM response JSON 解析失败, 使用基础 context params: {}", e.getMessage());
        }

        return params;
    }

    /**
     * Extract JSON from response
     */
    private String extractJson(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        // Try to find JSON code block
        int start = response.indexOf("```json");
        if (start != -1) {
            start = response.indexOf("\n", start) + 1;
            int end = response.indexOf("```", start);
            if (end != -1) {
                return response.substring(start, end).trim();
            }
        }

        // Try to find { } brackets
        start = response.indexOf("{");
        if (start != -1) {
            int end = response.lastIndexOf("}");
            if (end != -1 && end > start) {
                return response.substring(start, end + 1);
            }
        }

        return null;
    }

    /**
     * Execute associated Tools
     */
    private Object executeTools(SkillDefinition skill, Map<String, Object> params,
                                 List<String> executedTools, SkillContext context) {

        // 1. Determine tools to execute
        List<ToolExecutionPlan> toolPlans = buildToolExecutionPlans(skill, params);

        if (toolPlans.isEmpty()) {
            log.warn("No tools to execute for skill: {}", skill.getName());
            return Map.of("message", "No tools executed", "params", params);
        }

        // Limit tool count
        if (toolPlans.size() > maxToolsPerSkill) {
            log.warn("Too many tools ({}) for skill {}, limiting to {}",
                    toolPlans.size(), skill.getName(), maxToolsPerSkill);
            toolPlans = toolPlans.subList(0, maxToolsPerSkill);
        }

        // 2. Determine execution order
        String executionOrder = (String) params.getOrDefault("_execution_order", "sequential");
        boolean isParallel = "parallel".equalsIgnoreCase(executionOrder) && parallelExecutionEnabled;

        // 3. Execute tools
        Map<String, Object> results;
        if (isParallel && toolPlans.size() > 1) {
            results = executeToolsParallel(toolPlans, params, executedTools, context);
        } else {
            results = executeToolsSequential(toolPlans, params, executedTools, context);
        }

        // 4. Add execution metadata
        results.put("_executionOrder", executionOrder);
        results.put("_toolCount", executedTools.size());

        String reasoning = (String) params.get("_reasoning");
        if (reasoning != null) {
            results.put("_reasoning", reasoning);
        }

        return results;
    }

    /**
     * Build tool execution plans
     */
    private List<ToolExecutionPlan> buildToolExecutionPlans(SkillDefinition skill,
                                                            Map<String, Object> params) {
        List<ToolExecutionPlan> plans = new ArrayList<>();

        // Prioritize LLM-generated execution plan
        Object toolsToExecute = params.get("_tools_to_execute");
        if (toolsToExecute instanceof JsonNode) {
            JsonNode toolsNode = (JsonNode) toolsToExecute;
            if (toolsNode.isArray()) {
                int order = 1;
                for (JsonNode toolNode : toolsNode) {
                    String toolName = toolNode.has("tool_name") ?
                            toolNode.get("tool_name").asText() : null;

                    if (toolName != null && toolRegistry.hasExecutor(toolName)) {
                        Map<String, Object> toolParams = new HashMap<>();
                        if (toolNode.has("params")) {
                            try {
                                toolParams = objectMapper.convertValue(
                                        toolNode.get("params"),
                                        new TypeReference<Map<String, Object>>() {});
                            } catch (Exception e) {
                                log.warn("Failed to parse tool params for {}", toolName);
                            }
                        }

                        plans.add(new ToolExecutionPlan(toolName, toolParams, order++));
                    } else {
                        log.warn("Tool not found in registry: {}", toolName);
                    }
                }
            }
        }

        // If LLM didn't specify tools, use Skill's default tools
        if (plans.isEmpty() && skill.getTools() != null) {
            int order = 1;
            for (String toolName : skill.getTools()) {
                if (toolRegistry.hasExecutor(toolName)) {
                    plans.add(new ToolExecutionPlan(toolName, new HashMap<>(), order++));
                }
            }
        }

        return plans;
    }

    /**
     * Execute tools sequentially
     */
    private Map<String, Object> executeToolsSequential(List<ToolExecutionPlan> plans,
                                                        Map<String, Object> globalParams,
                                                        List<String> executedTools,
                                                        SkillContext context) {
        Map<String, Object> results = new LinkedHashMap<>();
        Map<String, Object> chainContext = new HashMap<>(globalParams);

        // Sort by order
        plans.sort(Comparator.comparingInt(p -> p.order));

        for (ToolExecutionPlan plan : plans) {
            try {
                // Merge params: global + tool-specific + chain context
                Map<String, Object> mergedParams = new HashMap<>(chainContext);
                mergedParams.putAll(plan.params);

                Object result = executeSingleTool(plan.toolName, mergedParams, context);
                results.put(plan.toolName, result);
                executedTools.add(plan.toolName);

                // Add result to chain context for subsequent tools
                chainContext.put("previous_" + plan.toolName, result);

            } catch (Exception e) {
                log.error("Tool execution failed, stopping chain: {}", plan.toolName, e);
                results.put(plan.toolName, Map.of("error", e.getMessage(), "success", false));
                executedTools.add(plan.toolName + " (failed)");
                break;
            }
        }

        return results;
    }

    /**
     * Execute tools in parallel
     */
    private Map<String, Object> executeToolsParallel(List<ToolExecutionPlan> plans,
                                                      Map<String, Object> globalParams,
                                                      List<String> executedTools,
                                                      SkillContext context) {
        Map<String, Future<Object>> futures = new LinkedHashMap<>();

        for (ToolExecutionPlan plan : plans) {
            Map<String, Object> mergedParams = new HashMap<>(globalParams);
            mergedParams.putAll(plan.params);

            futures.put(plan.toolName, executorService.submit(() ->
                    executeSingleTool(plan.toolName, mergedParams, context)));
        }

        Map<String, Object> results = new LinkedHashMap<>();

        for (Map.Entry<String, Future<Object>> entry : futures.entrySet()) {
            String toolName = entry.getKey();
            try {
                Object result = entry.getValue().get(toolTimeoutSeconds, TimeUnit.SECONDS);
                results.put(toolName, result);
                executedTools.add(toolName);
            } catch (TimeoutException e) {
                log.error("Tool execution timeout: {}", toolName);
                results.put(toolName, Map.of("error", "Execution timeout", "success", false));
                executedTools.add(toolName + " (timeout)");
            } catch (Exception e) {
                log.error("Tool execution failed: {}", toolName, e);
                results.put(toolName, Map.of("error", e.getMessage(), "success", false));
                executedTools.add(toolName + " (failed)");
            }
        }

        return results;
    }

    /**
     * Execute a single tool
     */
    private Object executeSingleTool(String toolName, Map<String, Object> params,
                                      SkillContext context) throws Exception {
        Optional<ToolExecutor> executorOpt = toolRegistry.getExecutor(toolName);
        if (executorOpt.isEmpty()) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }

        // Round 9 Fix (R8-β tail): respect factory_tool_configs disable flag.
        // R7b fixed this at ToolDispatchService but Skill orchestration was a 2nd path
        // that still ran disabled tools. Now skills also honor the Canvas switch.
        if (!toolRegistry.isToolEnabledForFactory(context.getFactoryId(), toolName)) {
            log.warn("Tool disabled for factory via Canvas: tool={}, factoryId={}, skill path",
                    toolName, context.getFactoryId());
            throw new IllegalStateException("Tool disabled for factory: " + toolName);
        }

        // W0 write-guard (intent-w0) — SITE D: Skill orchestration calls executor.execute() directly,
        // bypassing ToolDispatchService (Site B). Block a write tool unless a confirmed=true signal is
        // present (checked across the immediate tool params AND the SkillContext extractedParams).
        // Exception-based (mirrors the tool-not-found / tool-disabled throws above); the skill engine's
        // catch(Exception) blocks surface it. WriteGuardService is stateless → safe on the worker pool.
        com.cretas.aims.ai.tool.ToolExecutor wgTool = executorOpt.get();
        java.util.Map<String, Object> skCtx = params != null ? params
                : (context != null && context.getExtractedParams() != null ? context.getExtractedParams() : java.util.Map.of());
        boolean wgConfirmed = writeGuardService.isConfirmed(skCtx)
                || (context != null && writeGuardService.isConfirmed(context.getExtractedParams()));
        if (writeGuardService.isWriteTool(wgTool) && !wgConfirmed) {
            log.info("W0 write-guard (skill): blocked write tool {} (confirmed=false)", toolName);
            throw new IllegalStateException("W0 write-guard: tool '" + toolName + "' 需要显式确认后才能执行");
        }

        // W9 红线 (AI-RBAC 系统性收口) — SITE D: Skill orchestration bypasses Site B. Central RBAC enforce.
        // skCtx/params may lack userId, so build an RBAC context from the SkillContext (userId source of
        // truth). Fail-closed: if SkillContext carries no userId, the enforcer denies mapped write tools.
        java.util.Map<String, Object> rbacCtx = new java.util.HashMap<>(
                skCtx != null ? skCtx : java.util.Map.of());
        if (context != null) {
            if (context.getUserId() != null) {
                rbacCtx.put("userId", context.getUserId());
            }
            if (context.getFactoryId() != null) {
                rbacCtx.put("factoryId", context.getFactoryId());
            }
        }
        if (!toolRbacEnforcer.isAllowed(wgTool, rbacCtx)) {
            log.warn("W9 AI-RBAC (skill): denied tool={}", toolName);
            throw new IllegalStateException(toolRbacEnforcer.denyMessage(wgTool, rbacCtx));
        }

        ToolExecutor executor = executorOpt.get();

        // Build ToolCall
        ToolCall toolCall = ToolCall.builder()
                .id(UUID.randomUUID().toString())
                .type("function")
                .function(ToolCall.FunctionCall.builder()
                        .name(toolName)
                        .arguments(objectMapper.writeValueAsString(params))
                        .build())
                .build();

        // Build execution context
        Map<String, Object> execContext = new HashMap<>(params);
        execContext.put("factoryId", context.getFactoryId());
        execContext.put("userId", context.getUserId());
        execContext.put("sessionId", context.getSessionId());

        long startTime = System.currentTimeMillis();
        String result = executor.execute(toolCall, execContext);
        long duration = System.currentTimeMillis() - startTime;

        log.info("Tool {} executed in {}ms", toolName, duration);

        // Try to parse as JSON
        try {
            return objectMapper.readTree(result);
        } catch (Exception e) {
            return result;
        }
    }

    /**
     * Truncate query for logging
     */
    private String truncateQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.length() > 50 ? query.substring(0, 50) + "..." : query;
    }

    // ==================== P0: DAG Execution Engine ====================

    /**
     * Execute a skill using its DAG execution graph with conditional branching and error recovery.
     *
     * Algorithm:
     * 1. Build adjacency map from executionGraph
     * 2. Topological sort by dependencies
     * 3. Execute nodes in order, evaluating conditions against previous results
     * 4. Handle errors per errorStrategy (STOP / CONTINUE_ON_ERROR / RETRY / FALLBACK)
     */
    private SkillResult executeDAG(SkillDefinition skill, SkillContext context,
                                    List<String> executedTools, long startTime) {
        List<SkillDefinition.ExecutionNode> nodes = skill.getExecutionGraph();
        SkillDefinition.ErrorStrategy strategy = skill.getErrorStrategy();
        if (strategy == null) strategy = SkillDefinition.ErrorStrategy.STOP;

        log.info("DAG execution: skill={}, nodes={}, errorStrategy={}",
                skill.getName(), nodes.size(), strategy);

        // Build node lookup map
        Map<String, SkillDefinition.ExecutionNode> nodeMap = new LinkedHashMap<>();
        for (SkillDefinition.ExecutionNode node : nodes) {
            nodeMap.put(node.getId(), node);
        }

        // Topological sort
        List<String> sortedIds = topologicalSort(nodes);
        if (sortedIds == null) {
            return SkillResult.error(skill.getName(), "Circular dependency detected in execution graph",
                    executedTools, System.currentTimeMillis() - startTime);
        }

        // Execution state: nodeId -> result
        Map<String, NodeExecutionResult> nodeResults = new LinkedHashMap<>();
        Map<String, Object> partialResults = new LinkedHashMap<>();
        List<String> skippedNodes = new ArrayList<>();
        boolean hasError = false;

        // Build global params from context
        Map<String, Object> globalParams = new HashMap<>();
        if (context.getFactoryId() != null) globalParams.put("factoryId", context.getFactoryId());
        if (context.getUserId() != null) globalParams.put("userId", context.getUserId());
        if (context.getExtractedParams() != null) globalParams.putAll(context.getExtractedParams());

        for (String nodeId : sortedIds) {
            SkillDefinition.ExecutionNode node = nodeMap.get(nodeId);
            if (node == null) continue;

            // Check if all dependencies succeeded (or handle per strategy)
            if (node.hasDependencies()) {
                boolean depsMet = checkDependencies(node, nodeResults, strategy);
                if (!depsMet) {
                    log.info("DAG: skipping node {} — dependencies not met", nodeId);
                    skippedNodes.add(nodeId);
                    nodeResults.put(nodeId, NodeExecutionResult.skipped(nodeId));
                    continue;
                }
            }

            // Evaluate condition
            if (node.hasCondition()) {
                boolean conditionMet = evaluateCondition(node.getCondition(), nodeResults);
                if (!conditionMet) {
                    log.info("DAG: skipping node {} — condition not met: {}", nodeId, node.getCondition());
                    skippedNodes.add(nodeId);
                    nodeResults.put(nodeId, NodeExecutionResult.skipped(nodeId));
                    continue;
                }
            }

            // Execute the tool
            NodeExecutionResult result = executeNodeWithStrategy(
                    node, globalParams, nodeResults, context, strategy);
            nodeResults.put(nodeId, result);

            if (result.success) {
                executedTools.add(node.getToolName());
                partialResults.put(nodeId, result.data);
            } else {
                hasError = true;
                executedTools.add(node.getToolName() + " (failed)");
                partialResults.put(nodeId, Map.of("error", result.errorMessage, "success", false));

                if (strategy == SkillDefinition.ErrorStrategy.STOP) {
                    log.warn("DAG: stopping execution at node {} due to STOP strategy", nodeId);
                    break;
                }
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;

        // Determine overall success: for CONTINUE_ON_ERROR, partial success is OK
        boolean overallSuccess = !hasError ||
                (strategy == SkillDefinition.ErrorStrategy.CONTINUE_ON_ERROR && !partialResults.isEmpty());

        log.info("DAG execution completed: skill={}, executed={}, skipped={}, hasError={}, time={}ms",
                skill.getName(), executedTools.size(), skippedNodes.size(), hasError, executionTime);

        return SkillResult.builder()
                .success(overallSuccess)
                .skillName(skill.getName())
                .data(partialResults)
                .executedTools(executedTools)
                .executionTime(executionTime)
                .partialResults(partialResults)
                .skippedNodes(skippedNodes)
                .errorStrategy(strategy.name())
                .message(overallSuccess ? "DAG execution completed" :
                        "DAG execution failed at one or more nodes")
                .build();
    }

    /**
     * Topological sort of execution nodes by dependencies.
     * Returns null if circular dependency detected.
     */
    private List<String> topologicalSort(List<SkillDefinition.ExecutionNode> nodes) {
        Map<String, List<String>> deps = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();

        for (SkillDefinition.ExecutionNode node : nodes) {
            deps.putIfAbsent(node.getId(), new ArrayList<>());
            inDegree.putIfAbsent(node.getId(), 0);

            if (node.getDependsOn() != null) {
                for (String dep : node.getDependsOn()) {
                    deps.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.getId());
                    inDegree.merge(node.getId(), 1, Integer::sum);
                }
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);
            for (String dependent : deps.getOrDefault(current, List.of())) {
                int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                if (newDegree == 0) queue.add(dependent);
            }
        }

        if (sorted.size() != nodes.size()) {
            log.error("Circular dependency detected: sorted {} out of {} nodes", sorted.size(), nodes.size());
            return null;
        }
        return sorted;
    }

    /**
     * Check if a node's dependencies are satisfied based on the error strategy.
     */
    private boolean checkDependencies(SkillDefinition.ExecutionNode node,
                                       Map<String, NodeExecutionResult> nodeResults,
                                       SkillDefinition.ErrorStrategy strategy) {
        for (String depId : node.getDependsOn()) {
            NodeExecutionResult depResult = nodeResults.get(depId);
            if (depResult == null) return false; // dependency not yet executed
            if (depResult.skipped) continue; // skipped deps don't block

            if (!depResult.success && strategy == SkillDefinition.ErrorStrategy.STOP) {
                return false; // failed dep blocks under STOP
            }
        }
        return true;
    }

    /**
     * Execute a single node with error handling per strategy (RETRY, FALLBACK, etc.)
     */
    private NodeExecutionResult executeNodeWithStrategy(
            SkillDefinition.ExecutionNode node,
            Map<String, Object> globalParams,
            Map<String, NodeExecutionResult> nodeResults,
            SkillContext context,
            SkillDefinition.ErrorStrategy strategy) {

        // Merge params: global + node-specific + chain context from previous nodes
        Map<String, Object> mergedParams = new HashMap<>(globalParams);
        if (node.getParams() != null) mergedParams.putAll(node.getParams());

        // Add previous node results to context
        for (Map.Entry<String, NodeExecutionResult> entry : nodeResults.entrySet()) {
            if (entry.getValue().success && entry.getValue().data != null) {
                mergedParams.put("previous_" + entry.getKey(), entry.getValue().data);
            }
        }

        // Determine max attempts
        int maxAttempts = (strategy == SkillDefinition.ErrorStrategy.RETRY)
                ? Math.max(1, node.getMaxRetries()) : 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Object result = executeSingleTool(node.getToolName(), mergedParams, context);
                return NodeExecutionResult.success(node.getId(), result);
            } catch (Exception e) {
                log.warn("DAG: node {} attempt {}/{} failed: {}",
                        node.getId(), attempt, maxAttempts, e.getMessage());

                if (attempt == maxAttempts) {
                    // Try fallback if available
                    if (strategy == SkillDefinition.ErrorStrategy.FALLBACK
                            && node.getFallbackTool() != null) {
                        return executeFallback(node, mergedParams, context, e.getMessage());
                    }
                    return NodeExecutionResult.error(node.getId(), e.getMessage());
                }
            }
        }
        return NodeExecutionResult.error(node.getId(), "Max retries exceeded");
    }

    /**
     * Execute a fallback tool when the primary tool fails.
     */
    private NodeExecutionResult executeFallback(SkillDefinition.ExecutionNode node,
                                                 Map<String, Object> params,
                                                 SkillContext context,
                                                 String originalError) {
        String fallback = node.getFallbackTool();
        log.info("DAG: executing fallback tool {} for node {} (original error: {})",
                fallback, node.getId(), originalError);
        try {
            Object result = executeSingleTool(fallback, params, context);
            return NodeExecutionResult.success(node.getId(), result);
        } catch (Exception e) {
            log.error("DAG: fallback tool {} also failed: {}", fallback, e.getMessage());
            return NodeExecutionResult.error(node.getId(),
                    "Primary (" + originalError + ") and fallback (" + e.getMessage() + ") both failed");
        }
    }

    /**
     * Evaluate a condition expression against node execution results.
     *
     * Supported syntax:
     * - "{nodeId}.success" → boolean
     * - "{nodeId}.data.{field}" → checks field existence and truthiness
     * - "&&", "||", "!" → boolean operators
     * - "true", "false" → literals
     */
    private boolean evaluateCondition(String condition, Map<String, NodeExecutionResult> nodeResults) {
        if (condition == null || condition.trim().isEmpty()) return true;

        try {
            return evaluateExpression(condition.trim(), nodeResults);
        } catch (Exception e) {
            log.warn("Condition evaluation failed for '{}': {}", condition, e.getMessage());
            return false; // fail-closed: skip node if condition can't be evaluated
        }
    }

    private boolean evaluateExpression(String expr, Map<String, NodeExecutionResult> nodeResults) {
        expr = expr.trim();

        // Handle parentheses
        if (expr.startsWith("(") && findMatchingParen(expr, 0) == expr.length() - 1) {
            return evaluateExpression(expr.substring(1, expr.length() - 1), nodeResults);
        }

        // Handle OR (lowest precedence, split on || not inside parens)
        int orIndex = findOperatorOutsideParens(expr, "||");
        if (orIndex >= 0) {
            return evaluateExpression(expr.substring(0, orIndex), nodeResults)
                    || evaluateExpression(expr.substring(orIndex + 2), nodeResults);
        }

        // Handle AND
        int andIndex = findOperatorOutsideParens(expr, "&&");
        if (andIndex >= 0) {
            return evaluateExpression(expr.substring(0, andIndex), nodeResults)
                    && evaluateExpression(expr.substring(andIndex + 2), nodeResults);
        }

        // Handle NOT
        if (expr.startsWith("!")) {
            return !evaluateExpression(expr.substring(1), nodeResults);
        }

        // Literals
        if ("true".equalsIgnoreCase(expr)) return true;
        if ("false".equalsIgnoreCase(expr)) return false;

        // Evaluate dotted reference: nodeId.success or nodeId.data.fieldName
        return evaluateDottedRef(expr, nodeResults);
    }

    private boolean evaluateDottedRef(String ref, Map<String, NodeExecutionResult> nodeResults) {
        String[] parts = ref.split("\\.", 3);
        if (parts.length < 2) return false;

        String nodeId = parts[0].trim();
        String field = parts[1].trim();

        NodeExecutionResult result = nodeResults.get(nodeId);
        if (result == null) return false;

        if ("success".equals(field)) {
            return result.success;
        }

        if ("data".equals(field) && parts.length >= 3) {
            String dataField = parts[2].trim();
            return evaluateDataField(result.data, dataField);
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean evaluateDataField(Object data, String fieldName) {
        if (data == null) return false;

        // Try JsonNode
        if (data instanceof com.fasterxml.jackson.databind.JsonNode) {
            com.fasterxml.jackson.databind.JsonNode node = (com.fasterxml.jackson.databind.JsonNode) data;
            if (!node.has(fieldName)) return false;
            com.fasterxml.jackson.databind.JsonNode fieldNode = node.get(fieldName);
            if (fieldNode.isBoolean()) return fieldNode.asBoolean();
            if (fieldNode.isNumber()) return fieldNode.asDouble() > 0;
            if (fieldNode.isTextual()) return !fieldNode.asText().isEmpty();
            return !fieldNode.isNull();
        }

        // Try Map
        if (data instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) data;
            Object value = map.get(fieldName);
            if (value == null) return false;
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).doubleValue() > 0;
            if (value instanceof String) return !((String) value).isEmpty();
            return true;
        }

        return false;
    }

    private int findOperatorOutsideParens(String expr, String operator) {
        int depth = 0;
        for (int i = 0; i < expr.length() - operator.length() + 1; i++) {
            char c = expr.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && expr.startsWith(operator, i)) {
                return i;
            }
        }
        return -1;
    }

    private int findMatchingParen(String expr, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < expr.length(); i++) {
            if (expr.charAt(i) == '(') depth++;
            else if (expr.charAt(i) == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Internal result holder for DAG node execution
     */
    private static class NodeExecutionResult {
        final String nodeId;
        final boolean success;
        final boolean skipped;
        final Object data;
        final String errorMessage;

        private NodeExecutionResult(String nodeId, boolean success, boolean skipped,
                                     Object data, String errorMessage) {
            this.nodeId = nodeId;
            this.success = success;
            this.skipped = skipped;
            this.data = data;
            this.errorMessage = errorMessage;
        }

        static NodeExecutionResult success(String nodeId, Object data) {
            return new NodeExecutionResult(nodeId, true, false, data, null);
        }

        static NodeExecutionResult error(String nodeId, String errorMessage) {
            return new NodeExecutionResult(nodeId, false, false, null, errorMessage);
        }

        static NodeExecutionResult skipped(String nodeId) {
            return new NodeExecutionResult(nodeId, false, true, null, "Skipped");
        }
    }

    // ==================== Inner Classes ====================

    /**
     * Tool execution plan
     */
    private static class ToolExecutionPlan {
        final String toolName;
        final Map<String, Object> params;
        final int order;

        ToolExecutionPlan(String toolName, Map<String, Object> params, int order) {
            this.toolName = toolName;
            this.params = params;
            this.order = order;
        }
    }
}
