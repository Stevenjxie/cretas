package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.utils.TokenUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/config/v2/ai")
@RequiredArgsConstructor
@Tag(name = "Canvas AI Agent", description = "Canvas AI 配置助手 (DashScope Qwen)")
public class CanvasAIController {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final DashScopeClient dashScopeClient;
    private final MobileService mobileService;

    private static final String PRODUCT_WORK_PROCESS_MODULE = "product_work_process_config";
    private static final String PRODUCT_WORK_PROCESS_TOOL = "canvas_product_work_process_config";
    private static final String PRODUCT_WORK_PROCESS_DRAFT_REPLY = "已生成工序配置草稿";

    private static final String PRODUCT_PROCESS_WORKFLOW_MODULE = "product_process_workflow_config";
    private static final String PRODUCT_PROCESS_WORKFLOW_TOOL = "canvas_product_process_workflow_config";
    private static final String PRODUCT_PROCESS_WORKFLOW_REPLY = "已生成可审核的 Workflow 本地补丁";
    private static final String PRODUCT_PROCESS_WORKFLOW_SYSTEM_PROMPT = """
            You are a preview-only product-process Workflow configuration assistant.
            Return only a JSON WorkflowPatch[] array. Never call tools, APIs, save, publish,
            activate, create products, create reports, create tasks, or change inventory.
            Allowed operations: UPSERT_NODE, REMOVE_NODE, UPSERT_EDGE, REMOVE_EDGE, SET_NODE_FIELD.
            SET_NODE_FIELD roots: name, skuId, skuCode, specification, ports, conversionRule,
            reportingRequired. Do not return any other operation or field path.
            The patch array is atomic. Order dependent patches as UPSERT_NODE first,
            SET_NODE_FIELD second, and UPSERT_EDGE last. Any invalid member rejects the whole array.

            Current definition: %s
            Selected node id: %s
            """;

    // 长久方案 (弃补丁): LLM 只出「语义规格」(rawMaterials + steps), 不再拼底层图补丁。
    // 前端确定性编译器 (复用 createProcessBranch) 把规格建成合法图 —— LLM 再也不碰 node/edge/id。
    private static final String PRODUCT_PROCESS_WORKFLOW_SPEC_PROMPT = """
            You are a product-process Workflow planning assistant. From the user's natural-language
            description, output ONLY a JSON object describing the workflow at a SEMANTIC level.
            Do NOT output graph nodes / edges / ids / patches. Never call tools, save, publish, or
            change data. The frontend deterministically builds the graph from your JSON.

            Output shape (JSON only, no prose):
            {
              "rawMaterials": ["<entry raw material name>", ...],
              "steps": [
                {
                  "process": "<work process name, e.g. 清洗/卤制/切片装盒>",
                  "outputs": [
                    { "kind": "SEMI_FINISHED"|"FINISHED_GOOD", "name": "<output product name>", "unit": "<kg/盒/只/...>" }
                  ]
                }
              ]
            }
            Rules:
            - steps are ordered; each step consumes the previous step's first output
              (step 1 consumes the rawMaterials).
            - a step with multiple outputs = 分流/多产出 (one process yields several products).
            - the LAST step output is usually FINISHED_GOOD; intermediate outputs are SEMI_FINISHED.
            - use the user's own wording for process/output names. Keep it minimal, valid JSON only.

            Current definition (context, may be empty): %s
            Selected node id: %s
            """;

    // D3: narrow route for work-process catalog (create/update work-process master data)
    private static final String WORK_PROCESS_CATALOG_MODULE = "work_process_catalog";
    private static final String WORK_PROCESS_CATALOG_TOOL = "canvas_work_process_catalog";

    /**
     * Only canvas_* tools are allowed via AI chat to prevent prompt injection from
     * calling data-destructive or system-level tools.
     */
    private static final Pattern CANVAS_TOOL_PATTERN = Pattern.compile("^canvas_[a-z0-9_]+$");

    private void validateCanvasTool(String toolName) {
        if (toolName == null || !CANVAS_TOOL_PATTERN.matcher(toolName).matches()) {
            throw new BusinessException("Canvas AI only allows canvas_* tools, rejected: " + toolName);
        }
    }

    private Map<String, Object> buildToolContext(String factoryId, String authorization) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", factoryId);
        try {
            if (authorization != null) {
                String token = TokenUtils.extractToken(authorization);
                Long userId = mobileService.getUserFromToken(token).getId();
                if (userId != null) {
                    ctx.put("userId", userId);
                    ctx.put("operatorId", userId);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract userId from token: {}", e.getMessage());
        }
        return ctx;
    }

    private static final String CANVAS_SYSTEM_PROMPT = """
            你是工厂配置助手。用户通过 Canvas 配置系统管理工厂的模块、字段、工作流、校验规则、触发链和AI工具。

            你可以调用以下 Canvas 配置工具:
            - canvas_toggle_module: 启用/禁用工厂模块 (参数: moduleCode, enabled)
            - canvas_toggle_tool: 启用/禁用AI工具 (参数: toolName, enabled)
            - canvas_toggle_skill: 启用/禁用AI技能 (参数: skillName, enabled)
            - canvas_update_field: 修改字段属性 (参数: moduleCode, fieldCode, property[visible/required/label/defaultValue], value)
            - canvas_update_trigger_chain: 修改触发链 (参数: chainCode, enabled, stepOrder, stepEnabled)
            - canvas_apply_template: 应用行业模板 (参数: templateCode[FOOD_PROCESSING/BAKERY/RESTAURANT/AQUACULTURE])

            可用模块: sales_order, purchase_order, bom, production_plan, production_report,
            quality_inspection, inventory, inbound, outbound, equipment, customer, supplier,
            finance_ar, finance_ap, hr_employee, transfer, traceability
            """;

    @Data
    public static class AIRequest {
        private String message;
        private String mode; // autopilot, plan, action
        private String moduleCode;
        private Map<String, Object> params;
    }

    @Data
    public static class AIResponse {
        private String reply;
        private List<Map<String, Object>> diffs;
        private boolean applied;
    }

    @PostMapping("/chat")
    @Operation(summary = "Canvas AI 对话 (DashScope Qwen)")
    // Round 7a P0 fix: AI chat in autopilot mode calls arbitrary canvas_* tools via LLM.
    // Previously NO auth check — any authenticated user (viewer/operator/etc) could run
    // "禁用 production / 应用 RESTAURANT 模板" and the LLM would execute DDL-level changes.
    // Now restricted to canvas config admins only.
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<AIResponse> chat(
            @PathVariable String factoryId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody AIRequest request) {

        AIResponse response = new AIResponse();
        String mode = request.getMode() != null ? request.getMode() : "action";
        String message = request.getMessage();
        log.info("Canvas AI [{}] factory={}: {}", mode, factoryId, message);
        Map<String, Object> toolContext = buildToolContext(factoryId, authorization);

        try {
            if (PRODUCT_PROCESS_WORKFLOW_MODULE.equals(request.getModuleCode())) {
                return ApiResponse.success(handleProductProcessWorkflowConfigChat(request, toolContext));
            }
            if (PRODUCT_WORK_PROCESS_MODULE.equals(request.getModuleCode())) {
                return ApiResponse.success(handleProductWorkProcessConfigChat(request, toolContext));
            }
            // D3: work_process_catalog narrow route — bypasses LLM prompt entirely
            if (WORK_PROCESS_CATALOG_MODULE.equals(request.getModuleCode())) {
                return ApiResponse.success(handleWorkProcessCatalogChat(request, toolContext));
            }

            switch (mode) {
                case "autopilot" -> {
                    response.setReply(executeAutopilot(factoryId, message, toolContext));
                    response.setApplied(true);
                }
                case "plan" -> {
                    List<Map<String, Object>> diffs = generatePlan(factoryId, message);
                    response.setDiffs(diffs);
                    response.setReply("已生成 " + diffs.size() + " 项变更方案，请逐项审核后应用。");
                    response.setApplied(false);
                }
                case "action" -> {
                    response.setReply(analyzeImpact(factoryId, message));
                    response.setApplied(false);
                }
                default -> response.setReply("未知模式: " + mode);
            }
        } catch (Exception e) {
            log.error("Canvas AI error: {}", e.getMessage(), e);
            response.setReply("AI 处理失败: " + e.getMessage());
        }

        return ApiResponse.success(response);
    }

    /**
     * Workflow-only route. It always returns before generic autopilot/plan/action handling and
     * can only call the dedicated tool's public preview entry point.
     */
    private AIResponse handleProductProcessWorkflowConfigChat(
            AIRequest request, Map<String, Object> toolContext) throws Exception {
        ToolExecutor executor = toolRegistry.getExecutor(PRODUCT_PROCESS_WORKFLOW_TOOL)
                .orElseThrow(() -> new BusinessException("工具不存在: " + PRODUCT_PROCESS_WORKFLOW_TOOL));

        Map<String, Object> requestParams = request.getParams() != null
                ? request.getParams()
                : Map.of();
        Map<String, Object> nestedContext = readStringObjectMap(requestParams.get("context"));
        Object definition = requestParams.containsKey("definition")
                ? requestParams.get("definition")
                : nestedContext.get("definition");
        Object selectedNodeId = requestParams.containsKey("selectedNodeId")
                ? requestParams.get("selectedNodeId")
                : nestedContext.get("selectedNodeId");

        // 弃补丁: 让 LLM 只产出语义规格 (rawMaterials + steps), 前端确定性编译成图。
        String prompt = PRODUCT_PROCESS_WORKFLOW_SPEC_PROMPT.formatted(
                objectMapper.writeValueAsString(definition),
                Objects.toString(selectedNodeId, "null"));
        String llmResponse = dashScopeClient.chatLowTemp(prompt, request.getMessage());
        Map<String, Object> spec;
        try {
            spec = objectMapper.readValue(
                    extractJson(llmResponse), new TypeReference<Map<String, Object>>() {});
        } catch (Exception parseError) {
            return workflowSpecFailureResponse();
        }
        // 只校验规格 shape (steps 非空); 图的合法性由前端编译器保证 (端口/边/id 全代码生成)。
        if (!(spec.get("steps") instanceof List<?> stepList) || stepList.isEmpty()) {
            return workflowSpecFailureResponse();
        }

        Map<String, Object> diffParams = new LinkedHashMap<>();
        diffParams.put("spec", spec);

        AIResponse response = new AIResponse();
        response.setApplied(false);
        response.setReply(PRODUCT_PROCESS_WORKFLOW_REPLY);
        response.setDiffs(List.of(Map.of(
                "type", "PRODUCT_PROCESS_WORKFLOW_SPEC",
                "tool", PRODUCT_PROCESS_WORKFLOW_TOOL,
                "params", diffParams,
                "description", PRODUCT_PROCESS_WORKFLOW_REPLY)));
        return response;
    }

    private AIResponse workflowPatchFailureResponse() {
        AIResponse response = new AIResponse();
        response.setApplied(false);
        response.setDiffs(List.of());
        response.setReply("AI 未返回可审核的 Workflow 补丁，请调整描述后重试");
        return response;
    }

    private AIResponse workflowSpecFailureResponse() {
        AIResponse response = new AIResponse();
        response.setApplied(false);
        response.setDiffs(List.of());
        response.setReply("AI 未能从描述里解析出可用的工序步骤，请把每一步的工序名和产出说清楚后重试");
        return response;
    }

    private Map<String, Object> readStringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, entryValue) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, entryValue);
            }
        });
        return result;
    }

    /**
     * D1 narrow module route: product work-process config needs catalog-aware
     * parsing by a business Tool, not the generic Canvas prompt. Existing canvas
     * modules keep the original LLM path above.
     */
    private AIResponse handleProductWorkProcessConfigChat(
            AIRequest request, Map<String, Object> toolContext) throws Exception {
        ToolExecutor executor = toolRegistry.getExecutor(PRODUCT_WORK_PROCESS_TOOL)
                .orElseThrow(() -> new BusinessException("工具不存在: " + PRODUCT_WORK_PROCESS_TOOL));

        Map<String, Object> params = new LinkedHashMap<>();
        if (request.getParams() != null) {
            params.putAll(request.getParams());
        }
        params.put("message", request.getMessage());
        params.put("apply", false);

        String argsJson = objectMapper.writeValueAsString(params);
        ToolCall toolCall = ToolCall.of(
                "d1-preview-" + System.currentTimeMillis(), PRODUCT_WORK_PROCESS_TOOL, argsJson);
        String raw = executor.supportsPreview()
                ? executor.preview(toolCall, toolContext)
                : executor.execute(toolCall, toolContext);

        Map<String, Object> toolResponse = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});

        // D1 B-1: surface tool failures instead of silently returning a fake success draft
        if (!Boolean.TRUE.equals(toolResponse.get("success"))) {
            String errorMsg = Objects.toString(toolResponse.get("error"), "工序配置工具执行失败，请检查输入后重试");
            AIResponse errorResponse = new AIResponse();
            errorResponse.setApplied(false);
            errorResponse.setReply(errorMsg);
            errorResponse.setDiffs(List.of());
            return errorResponse;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) toolResponse.getOrDefault("data", Map.of());

        AIResponse response = new AIResponse();
        response.setApplied(false);
        response.setReply(Objects.toString(
                data.getOrDefault("message", PRODUCT_WORK_PROCESS_DRAFT_REPLY),
                PRODUCT_WORK_PROCESS_DRAFT_REPLY));
        response.setDiffs(List.of(Map.of(
                "type", "PRODUCT_WORK_PROCESS_DRAFT",
                "tool", PRODUCT_WORK_PROCESS_TOOL,
                "params", data,
                "description", response.getReply()
        )));
        return response;
    }

    /**
     * D3 narrow module route: work-process catalog (create/update work-process master data).
     * Mirrors handleProductWorkProcessConfigChat — directly calls the tool, no LLM intermediary.
     * This ensures canvas_work_process_catalog is reachable without being listed in
     * CANVAS_SYSTEM_PROMPT (where it was previously missing and thus never called via autopilot/plan).
     */
    private AIResponse handleWorkProcessCatalogChat(
            AIRequest request, Map<String, Object> toolContext) throws Exception {
        ToolExecutor executor = toolRegistry.getExecutor(WORK_PROCESS_CATALOG_TOOL)
                .orElseThrow(() -> new BusinessException("工具不存在: " + WORK_PROCESS_CATALOG_TOOL));

        Map<String, Object> params = new LinkedHashMap<>();
        if (request.getParams() != null) {
            params.putAll(request.getParams());
        }
        params.put("message", request.getMessage());

        String argsJson = objectMapper.writeValueAsString(params);
        ToolCall toolCall = ToolCall.of(
                "d3-catalog-" + System.currentTimeMillis(), WORK_PROCESS_CATALOG_TOOL, argsJson);
        String raw = executor.execute(toolCall, toolContext);

        Map<String, Object> toolResponse = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});

        // D3 B-1: surface tool failures — never return a fake success
        if (!Boolean.TRUE.equals(toolResponse.get("success"))) {
            String errorMsg = Objects.toString(toolResponse.get("error"), "工序管理工具执行失败，请检查输入后重试");
            AIResponse errorResponse = new AIResponse();
            errorResponse.setApplied(false);
            errorResponse.setReply(errorMsg);
            errorResponse.setDiffs(List.of());
            return errorResponse;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) toolResponse.getOrDefault("data", Map.of());
        String replyMsg = Objects.toString(data.getOrDefault("message", "工序操作已完成"), "工序操作已完成");

        AIResponse response = new AIResponse();
        response.setApplied(true);
        response.setReply(replyMsg);
        response.setDiffs(List.of(Map.of(
                "type", "WORK_PROCESS_CATALOG",
                "tool", WORK_PROCESS_CATALOG_TOOL,
                "params", data,
                "description", replyMsg
        )));
        return response;
    }

    @PostMapping("/apply-diffs")
    @Operation(summary = "批量应用 Plan Mode 生成的变更")
    // Round 7a P0 fix: same exposure as /chat — plan-mode diffs execute canvas_* tools.
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<String> applyDiffs(
            @PathVariable String factoryId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody List<Map<String, Object>> diffs) {

        Map<String, Object> toolContext = buildToolContext(factoryId, authorization);
        int applied = 0;
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> diff : diffs) {
            String toolName = (String) diff.get("tool");
            if (toolName == null) continue;

            if (PRODUCT_PROCESS_WORKFLOW_TOOL.equals(toolName)) {
                errors.add(toolName + ": WORKFLOW_AI_PREVIEW_ONLY");
                continue;
            }

            try {
                validateCanvasTool(toolName);
            } catch (Exception e) {
                errors.add(e.getMessage());
                continue;
            }

            Optional<ToolExecutor> executor = toolRegistry.getExecutor(toolName);
            if (executor.isEmpty()) {
                errors.add("工具不存在: " + toolName);
                continue;
            }

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) diff.getOrDefault("params", Map.of());
                String argsJson = objectMapper.writeValueAsString(params);
                ToolCall toolCall = ToolCall.of("ai-apply-" + applied, toolName, argsJson);
                executor.get().execute(toolCall, toolContext);
                applied++;
            } catch (Exception e) {
                errors.add(toolName + ": " + e.getMessage());
                log.warn("Failed to apply diff {}: {}", toolName, e.getMessage());
            }
        }

        String msg = "已应用 " + applied + "/" + diffs.size() + " 项变更";
        if (!errors.isEmpty()) msg += "。失败: " + String.join("; ", errors);
        return ApiResponse.success(msg);
    }

    /**
     * Autopilot: LLM 分析用户意图 → 直接调用 canvas tools 执行
     */
    private String executeAutopilot(String factoryId, String message, Map<String, Object> toolContext) {
        String prompt = CANVAS_SYSTEM_PROMPT + """

                模式: AUTOPILOT (全自动执行)
                工厂ID: %s

                用户指令: "%s"

                请分析用户需求，返回需要执行的操作列表。格式为 JSON 数组:
                [{"tool":"canvas_xxx","params":{"key":"value"},"description":"说明"}]

                只返回 JSON 数组，不要其他文字。如果不需要任何操作，返回空数组 []。
                """.formatted(factoryId, message);

        String llmResponse = dashScopeClient.chatFast(prompt, message);
        log.info("Canvas AI Autopilot LLM response: {}", llmResponse);

        // Parse and execute
        try {
            // Extract JSON array from response — if LLM didn't return JSON, surface the raw reply
            // so user sees what the AI actually said (instead of silently saying "no operations").
            String json = extractJson(llmResponse);
            if ("[]".equals(json) && llmResponse != null && !llmResponse.trim().isEmpty()
                    && !llmResponse.contains("[") && !llmResponse.contains("]")) {
                // LLM returned plain text, not JSON — show it to the user
                return "AI 回复 (非 JSON): " + llmResponse.trim();
            }
            List<Map<String, Object>> actions = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});

            if (actions.isEmpty()) return "分析完成，当前无需操作。";

            StringBuilder result = new StringBuilder("已执行 " + actions.size() + " 项操作:\n");
            for (Map<String, Object> action : actions) {
                String toolName = (String) action.get("tool");
                String desc = (String) action.getOrDefault("description", toolName);
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) action.getOrDefault("params", Map.of());

                try {
                    validateCanvasTool(toolName);
                } catch (Exception e) {
                    result.append("- ❌ ").append(desc).append(" (").append(e.getMessage()).append(")\n");
                    continue;
                }

                Optional<ToolExecutor> executor = toolRegistry.getExecutor(toolName);
                if (executor.isEmpty()) {
                    result.append("- ❌ ").append(desc).append(" (工具不存在)\n");
                    continue;
                }

                try {
                    String argsJson = objectMapper.writeValueAsString(params);
                    ToolCall toolCall = ToolCall.of("autopilot-" + toolName, toolName, argsJson);
                    executor.get().execute(toolCall, toolContext);
                    result.append("- ✅ ").append(desc).append("\n");
                } catch (Exception e) {
                    result.append("- ❌ ").append(desc).append(": ").append(e.getMessage()).append("\n");
                }
            }
            return result.toString();
        } catch (Exception e) {
            log.warn("Autopilot JSON parse failed, returning raw LLM response: {}", e.getMessage());
            return llmResponse;
        }
    }

    /**
     * Plan Mode: LLM 生成变更方案 (不执行)，用户逐项审核后批量应用
     */
    private List<Map<String, Object>> generatePlan(String factoryId, String message) {
        String prompt = CANVAS_SYSTEM_PROMPT + """

                模式: PLAN (生成方案，不执行)
                工厂ID: %s

                用户需求: "%s"

                请分析需求，生成配置变更方案。格式为 JSON 数组:
                [{"type":"TOOL_TOGGLE|FIELD_CHANGE|TRIGGER_CHAIN_CHANGE|VALIDATION_RULE_CHANGE",
                  "tool":"canvas_xxx",
                  "params":{"key":"value"},
                  "description":"中文说明这个变更的目的"}]

                只返回 JSON 数组。每个变更必须有清晰的中文 description。
                """.formatted(factoryId, message);

        String llmResponse = dashScopeClient.chatFast(prompt, message);
        log.info("Canvas AI Plan LLM response: {}", llmResponse);

        try {
            String json = extractJson(llmResponse);
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Plan JSON parse failed: {}", e.getMessage());
            // Return a single item explaining the error
            return List.of(Map.of(
                    "type", "INFO",
                    "tool", "",
                    "params", Map.of(),
                    "description", "AI 分析: " + llmResponse
            ));
        }
    }

    /**
     * Action Mode: LLM 分析用户操作的关联影响
     */
    private String analyzeImpact(String factoryId, String message) {
        String prompt = CANVAS_SYSTEM_PROMPT + """

                模式: ACTION (实时提示)
                工厂ID: %s

                用户正在操作: "%s"

                请分析这个操作可能的关联影响:
                1. 哪些模块/字段/工具会受影响？
                2. 有什么潜在风险？
                3. 有什么建议？

                用简洁的中文回答，不超过 200 字。
                """.formatted(factoryId, message);

        return dashScopeClient.chatFast(prompt, message);
    }

    /**
     * Extract JSON array from LLM response (handles markdown code blocks)
     */
    private String extractJson(String response) {
        if (response == null) return "[]";
        String trimmed = response.trim();
        // Remove markdown code block if present
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();
        // Find first [ and last ]
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return "[]";
    }
}
