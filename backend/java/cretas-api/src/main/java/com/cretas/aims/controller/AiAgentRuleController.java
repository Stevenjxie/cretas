package com.cretas.aims.controller;

import com.cretas.aims.dto.ai.CreateAiAgentRuleRequest;
import com.cretas.aims.dto.ai.UpdateAiAgentRuleRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.smartbi.AiAgentRule;
import com.cretas.aims.repository.smartbi.AiAgentRuleRepository;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator.SpelEvaluationFailure;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.cretas.aims.config.RequireRole;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI Agent 规则管理控制器
 *
 * 提供 AI Agent 规则的 CRUD 管理 API:
 * - 规则列表查询（支持按触发类型、工厂过滤）
 * - 规则创建、更新、删除
 * - 规则启用/禁用切换
 * - 规则优先级调整
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-22
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/ai/agent/rules")
@RequiredArgsConstructor
@Tag(name = "AI Agent规则管理", description = "AI Agent 规则配置管理 API")
public class AiAgentRuleController {

    private final AiAgentRuleRepository aiAgentRuleRepository;
    private final SandboxedSpelEvaluator spelEvaluator;

    // ==================== 规则查询 ====================

    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping
    @Operation(summary = "获取所有规则", description = "获取工厂的所有启用的 AI Agent 规则列表")
    public ResponseEntity<ApiResponse<List<AiAgentRule>>> getAllRules(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        List<AiAgentRule> rules = aiAgentRuleRepository.findAllActiveByFactoryId(factoryId);
        log.debug("获取工厂 {} 的 AI Agent 规则，共 {} 条", factoryId, rules.size());
        return ResponseEntity.ok(ApiResponse.success(rules));
    }

    @GetMapping("/all")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "获取所有规则（含禁用）", description = "获取工厂的所有 AI Agent 规则，包括已禁用的")
    public ResponseEntity<ApiResponse<List<AiAgentRule>>> getAllRulesIncludeDisabled(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        List<AiAgentRule> rules = aiAgentRuleRepository.findByFactoryIdOrFactoryId(factoryId, "DEFAULT");
        return ResponseEntity.ok(ApiResponse.success(rules));
    }

    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/{ruleId}")
    @Operation(summary = "获取单个规则", description = "根据规则ID获取规则详情")
    public ResponseEntity<ApiResponse<AiAgentRule>> getRule(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "规则ID") @PathVariable String ruleId) {

        Optional<AiAgentRule> rule = aiAgentRuleRepository.findById(ruleId);
        if (rule.isEmpty()) {
            throw new BusinessException(404, "规则不存在: " + ruleId).withHint("请检查 ID 是否正确");
        }

        // 检查规则是否属于该工厂或是全局规则
        AiAgentRule r = rule.get();
        if (!"DEFAULT".equals(r.getFactoryId()) && !factoryId.equals(r.getFactoryId())) {
            throw new BusinessException(403, "无权访问该规则").withSeverity("error");
        }

        return ResponseEntity.ok(ApiResponse.success(r));
    }

    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/trigger/{triggerType}")
    @Operation(summary = "按触发类型获取规则", description = "获取指定触发类型的规则列表")
    public ResponseEntity<ApiResponse<List<AiAgentRule>>> getRulesByTriggerType(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "触发类型 (SOP_UPLOAD, BATCH_COMPLETE, QUALITY_ALERT, SCHEDULE_CHANGE)")
            @PathVariable String triggerType) {

        List<AiAgentRule> rules = aiAgentRuleRepository.findByFactoryIdAndTriggerTypeAndIsActiveTrue(
                factoryId, triggerType);
        return ResponseEntity.ok(ApiResponse.success(rules));
    }

    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/trigger-types")
    @Operation(summary = "获取所有触发类型", description = "获取系统支持的所有触发类型")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getTriggerTypes(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        List<Map<String, String>> types = List.of(
                Map.of("code", "SOP_UPLOAD", "name", "SOP文档上传", "description", "当上传SOP文档时触发"),
                Map.of("code", "BATCH_COMPLETE", "name", "批次完成", "description", "当生产批次完成时触发"),
                Map.of("code", "QUALITY_ALERT", "name", "质量告警", "description", "当发生质量告警时触发"),
                Map.of("code", "SCHEDULE_CHANGE", "name", "排产变更", "description", "当排产计划变更时触发")
        );
        return ResponseEntity.ok(ApiResponse.success(types));
    }

    // ==================== 规则管理 ====================

    @RequirePermission({"system:read_write"})
    @PostMapping
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "创建规则", description = "创建新的 AI Agent 规则（仅工厂管理员）")
    public ResponseEntity<ApiResponse<AiAgentRule>> createRule(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Valid @RequestBody CreateAiAgentRuleRequest request) {

        // B-A7 P0 fix (sister of PR #48 / #50): validate conditionExpression at write time.
        // SopAgentOrchestratorImpl previously used raw StandardEvaluationContext on whatever
        // was persisted here — so T(java.lang.Runtime).getRuntime().exec(...) shipped through
        // POST could RCE on the next SOP-upload event. Reject before persist.
        ResponseEntity<ApiResponse<AiAgentRule>> spelError =
                validateSpel(request.getConditionExpression());
        if (spelError != null) {
            return spelError;
        }

        AiAgentRule rule = toAiAgentRule(request);
        // 设置工厂ID (always overrides any wire value — defense vs cross-tenant write)
        rule.setFactoryId(factoryId);
        rule.setId(null); // 确保是新建

        // Apply controller-owned defaults when caller omits (Rule 17.1: defaults live
        // here, NOT @Builder.Default leaking through Jackson @NoArgsConstructor).
        if (rule.getPriority() == null) {
            rule.setPriority(100);
        }
        if (rule.getIsActive() == null) {
            rule.setIsActive(true);
        }
        if (rule.getUseLlmSelection() == null) {
            rule.setUseLlmSelection(false);
        }

        AiAgentRule saved = aiAgentRuleRepository.save(rule);
        log.info("创建 AI Agent 规则: id={}, name={}, triggerType={}, factoryId={}",
                saved.getId(), saved.getRuleName(), saved.getTriggerType(), factoryId);

        return ResponseEntity.ok(ApiResponse.success("规则创建成功", saved));
    }

    @RequirePermission({"system:read_write"})
    @PutMapping("/{ruleId}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "更新规则", description = "更新现有的 AI Agent 规则（仅工厂管理员）")
    public ResponseEntity<ApiResponse<AiAgentRule>> updateRule(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "规则ID") @PathVariable String ruleId,
            @Valid @RequestBody UpdateAiAgentRuleRequest request) {

        Optional<AiAgentRule> existing = aiAgentRuleRepository.findById(ruleId);
        if (existing.isEmpty()) {
            throw new BusinessException(404, "规则不存在: " + ruleId).withHint("请检查 ID 是否正确");
        }

        AiAgentRule existingRule = existing.get();

        // 检查权限：只能修改自己工厂的规则，不能修改全局规则
        if ("DEFAULT".equals(existingRule.getFactoryId())) {
            throw new BusinessException(409, "无法修改全局规则，请联系平台管理员");
        }
        if (!factoryId.equals(existingRule.getFactoryId())) {
            throw new BusinessException(403, "无权修改该规则").withSeverity("error");
        }

        // B-A7 P0 fix (sister of PR #48 / #50): validate conditionExpression at write time
        // on the update path too. Without this, an attacker who can PUT can still plant
        // RCE payload even if POST is gated.
        ResponseEntity<ApiResponse<AiAgentRule>> spelError =
                validateSpel(request.getConditionExpression());
        if (spelError != null) {
            return spelError;
        }

        // 更新字段 (preserves prior behaviour: each setter unconditionally applied —
        // null wire value clears existing field, mirroring the pre-DTO contract).
        existingRule.setRuleName(request.getRuleName());
        existingRule.setRuleDescription(request.getRuleDescription());
        existingRule.setTriggerType(request.getTriggerType());
        existingRule.setTriggerEntity(request.getTriggerEntity());
        existingRule.setToolChainConfig(request.getToolChainConfig());
        existingRule.setUseLlmSelection(request.getUseLlmSelection());
        existingRule.setLlmSelectionPrompt(request.getLlmSelectionPrompt());
        existingRule.setConditionExpression(request.getConditionExpression());
        existingRule.setPriority(request.getPriority());

        AiAgentRule saved = aiAgentRuleRepository.save(existingRule);
        log.info("更新 AI Agent 规则: id={}, name={}", ruleId, saved.getRuleName());

        return ResponseEntity.ok(ApiResponse.success("规则更新成功", saved));
    }

    @RequirePermission({"system:read_write"})
    @DeleteMapping("/{ruleId}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "删除规则", description = "删除 AI Agent 规则（仅工厂管理员）")
    public ResponseEntity<ApiResponse<Void>> deleteRule(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "规则ID") @PathVariable String ruleId) {

        Optional<AiAgentRule> existing = aiAgentRuleRepository.findById(ruleId);
        if (existing.isEmpty()) {
            throw new BusinessException(404, "规则不存在: " + ruleId).withHint("请检查 ID 是否正确");
        }

        AiAgentRule rule = existing.get();

        // 检查权限
        if ("DEFAULT".equals(rule.getFactoryId())) {
            throw new BusinessException(409, "无法删除全局规则，请联系平台管理员");
        }
        if (!factoryId.equals(rule.getFactoryId())) {
            throw new BusinessException(403, "无权删除该规则").withSeverity("error");
        }

        aiAgentRuleRepository.delete(rule);
        log.info("删除 AI Agent 规则: id={}, name={}", ruleId, rule.getRuleName());

        return ResponseEntity.ok(ApiResponse.successMessage("规则删除成功"));
    }

    @RequirePermission({"system:read_write"})
    @PostMapping("/{ruleId}/toggle")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "切换规则状态", description = "启用或禁用 AI Agent 规则（仅工厂管理员）")
    public ResponseEntity<ApiResponse<AiAgentRule>> toggleRule(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "规则ID") @PathVariable String ruleId) {

        Optional<AiAgentRule> existing = aiAgentRuleRepository.findById(ruleId);
        if (existing.isEmpty()) {
            throw new BusinessException(404, "规则不存在: " + ruleId).withHint("请检查 ID 是否正确");
        }

        AiAgentRule rule = existing.get();

        // 检查权限
        if ("DEFAULT".equals(rule.getFactoryId())) {
            throw new BusinessException(409, "无法修改全局规则状态，请联系平台管理员");
        }
        if (!factoryId.equals(rule.getFactoryId())) {
            throw new BusinessException(403, "无权修改该规则").withSeverity("error");
        }

        // 切换状态
        rule.setIsActive(!rule.getIsActive());
        AiAgentRule saved = aiAgentRuleRepository.save(rule);

        String action = saved.getIsActive() ? "启用" : "禁用";
        log.info("{} AI Agent 规则: id={}, name={}", action, ruleId, saved.getRuleName());

        return ResponseEntity.ok(ApiResponse.success("规则已" + action, saved));
    }

    @RequirePermission({"system:read_write"})
    @PatchMapping("/{ruleId}/priority")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "调整规则优先级", description = "调整 AI Agent 规则的优先级（数值越小越优先）")
    public ResponseEntity<ApiResponse<AiAgentRule>> updatePriority(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "规则ID") @PathVariable String ruleId,
            @RequestBody PriorityRequest request) {

        Optional<AiAgentRule> existing = aiAgentRuleRepository.findById(ruleId);
        if (existing.isEmpty()) {
            throw new BusinessException(404, "规则不存在: " + ruleId).withHint("请检查 ID 是否正确");
        }

        AiAgentRule rule = existing.get();

        // 检查权限
        if ("DEFAULT".equals(rule.getFactoryId())) {
            throw new BusinessException(409, "无法修改全局规则优先级，请联系平台管理员");
        }
        if (!factoryId.equals(rule.getFactoryId())) {
            throw new BusinessException(403, "无权修改该规则").withSeverity("error");
        }

        rule.setPriority(request.getPriority());
        AiAgentRule saved = aiAgentRuleRepository.save(rule);

        log.info("调整 AI Agent 规则优先级: id={}, newPriority={}", ruleId, request.getPriority());

        return ResponseEntity.ok(ApiResponse.success("优先级调整成功", saved));
    }

    // ==================== 统计信息 ====================

    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/stats")
    @Operation(summary = "获取规则统计", description = "获取 AI Agent 规则的统计信息")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        List<AiAgentRule> allRules = aiAgentRuleRepository.findByFactoryIdOrFactoryId(factoryId, "DEFAULT");

        long activeCount = allRules.stream().filter(AiAgentRule::getIsActive).count();
        long sopUploadCount = aiAgentRuleRepository.countByTriggerTypeAndIsActiveTrue(AiAgentRule.TRIGGER_SOP_UPLOAD);
        long batchCompleteCount = aiAgentRuleRepository.countByTriggerTypeAndIsActiveTrue(AiAgentRule.TRIGGER_BATCH_COMPLETE);
        long qualityAlertCount = aiAgentRuleRepository.countByTriggerTypeAndIsActiveTrue(AiAgentRule.TRIGGER_QUALITY_ALERT);
        long scheduleChangeCount = aiAgentRuleRepository.countByTriggerTypeAndIsActiveTrue(AiAgentRule.TRIGGER_SCHEDULE_CHANGE);

        Map<String, Object> stats = Map.of(
                "totalRules", allRules.size(),
                "activeRules", activeCount,
                "disabledRules", allRules.size() - activeCount,
                "byTriggerType", Map.of(
                        "SOP_UPLOAD", sopUploadCount,
                        "BATCH_COMPLETE", batchCompleteCount,
                        "QUALITY_ALERT", qualityAlertCount,
                        "SCHEDULE_CHANGE", scheduleChangeCount
                )
        );

        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // ==================== 可用工具列表 ====================

    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/available-tools")
    @Operation(summary = "获取可用工具列表", description = "获取可在规则中使用的工具列表")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getAvailableTools(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        List<Map<String, String>> tools = List.of(
                Map.of(
                        "name", "sop_parse_document",
                        "description", "解析SOP文档(PDF/Excel/图片)，提取工序步骤、时间要求、技能要求等信息",
                        "category", "SOP处理"
                ),
                Map.of(
                        "name", "sop_analyze_complexity",
                        "description", "分析SOP工序复杂度，输出1-5级复杂度评估及理由",
                        "category", "SOP处理"
                ),
                Map.of(
                        "name", "sku_update_complexity",
                        "description", "更新SKU复杂度等级，并触发排产特征重算",
                        "category", "SKU管理"
                ),
                Map.of(
                        "name", "scheduling_recalculate",
                        "description", "重新计算排产特征向量",
                        "category", "排产管理"
                )
        );

        return ResponseEntity.ok(ApiResponse.success(tools));
    }

    /**
     * B-A7 P0 fix (sister of PR #48 / #50): validate {@code conditionExpression} at write time.
     * Returns non-null ResponseEntity error when SpEL contains RCE / reflection / forbidden
     * constructs or has syntax errors. Returns null when:
     * <ul>
     *   <li>SpEL is null/blank (allowed — empty condition means "always match",
     *       per {@link com.cretas.aims.service.impl.SopAgentOrchestratorImpl#evaluateCondition})</li>
     *   <li>SpEL passes sandbox validation</li>
     * </ul>
     *
     * <p>Note: {@code SopAgentOrchestratorImpl.evaluateCondition} strips a wrapping
     * {@code #{...}} before parsing — we mirror that here so a valid wrapped expression
     * isn't rejected at validate-time.
     */
    private ResponseEntity<ApiResponse<AiAgentRule>> validateSpel(String spel) {
        if (spel == null || spel.isBlank()) {
            return null;
        }
        // Mirror SopAgentOrchestratorImpl.evaluateCondition: strip wrapping #{...} if present.
        String toValidate = spel;
        if (toValidate.startsWith("#{") && toValidate.endsWith("}")) {
            toValidate = toValidate.substring(2, toValidate.length() - 1);
        }
        try {
            spelEvaluator.validateSyntax(toValidate);
            return null;
        } catch (SpelEvaluationFailure ex) {
            log.warn("Rejected AI Agent rule SpEL at write time: spel={}, reason={}",
                    spel, ex.getUserMessage());
            ApiResponse<AiAgentRule> body = ApiResponse.errorWithCode(
                    400, "VALIDATION",
                    "SpEL 表达式语法/安全检查失败: " + ex.getUserMessage(),
                    ex.getActionHint() != null
                            ? ex.getActionHint()
                            : "仅支持 #input/#order/#material/#sopType 等业务字段访问, 禁用 T()/new/反射",
                    "warning");
            return ResponseEntity.badRequest().body(body);
        }
    }

    // ===================================================================
    // Rule 17.1 — wire→entity mappers (Issue #384 batch 6 final).
    // Mapper deliberately does NOT replicate controller-owned defaults:
    //   - factoryId from @PathVariable (overridden after mapping)
    //   - id forced null after mapping (always new on POST)
    //   - priority default 100, isActive default true, useLlmSelection default false
    //     all applied by controller create-path after mapping
    // ===================================================================

    /**
     * Map {@link CreateAiAgentRuleRequest} → {@link AiAgentRule}.
     *
     * <p>Auto-managed by controller after mapping: {@code factoryId} (path var),
     * {@code id} (forced null), {@code priority} (100-fallback), {@code isActive}
     * (true-fallback), {@code useLlmSelection} (false-fallback). Auto-managed by
     * JPA / BaseEntity: {@code createdAt}, {@code updatedAt}, {@code deletedAt}.
     */
    private static AiAgentRule toAiAgentRule(CreateAiAgentRuleRequest r) {
        AiAgentRule rule = new AiAgentRule();
        rule.setTriggerType(r.getTriggerType());
        rule.setTriggerEntity(r.getTriggerEntity());
        rule.setRuleName(r.getRuleName());
        rule.setRuleDescription(r.getRuleDescription());
        rule.setToolChainConfig(r.getToolChainConfig());
        rule.setUseLlmSelection(r.getUseLlmSelection());
        rule.setLlmSelectionPrompt(r.getLlmSelectionPrompt());
        rule.setConditionExpression(r.getConditionExpression());
        rule.setPriority(r.getPriority());
        rule.setIsActive(r.getIsActive());
        return rule;
    }

    // ==================== DTO Classes ====================

    @lombok.Data
    public static class PriorityRequest {
        private Integer priority;
    }
}
