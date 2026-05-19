package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.entity.rules.RuleActionType;
import com.cretas.aims.entity.rules.RuleExecutionLog;
import com.cretas.aims.entity.rules.RuleScope;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.rules.BusinessRuleRepository;
import com.cretas.aims.repository.rules.RuleExecutionLogRepository;
import com.cretas.aims.service.rules.RuleEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas-Rules REST API (Phase 4a — pure auto business rule engine).
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * NOTE — Name disambiguation: there is also a {@code BusinessRuleController} under the
 * same package which handles Canvas V2 validation rules / default values / formulas /
 * scheduler (older). This Canvas-Rules Phase 4a controller is named {@code CanvasRuleController}
 * to avoid bean name collision and class name confusion.
 *
 * Role gate: factory_super_admin / permission_admin only (config-tier change).
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-rules")
@RequiredArgsConstructor
@Tag(name = "Canvas-Rules", description = "Canvas 业务规则引擎 (Phase 4a)")
public class CanvasRuleController {

    private final BusinessRuleRepository ruleRepository;
    private final RuleExecutionLogRepository logRepository;
    private final RuleEngine ruleEngine;

    @Operation(summary = "列出所有业务规则", description = "可按 scope 过滤")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listRules(
            @PathVariable String factoryId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Boolean enabled) {
        log.debug("GET /canvas-rules factoryId={} scope={} enabled={}", factoryId, scope, enabled);

        RuleScope scopeEnum = parseScope(scope);
        List<BusinessRule> rules;
        if (scopeEnum != null && enabled != null) {
            rules = ruleRepository.findByFactoryIdAndScopeAndEnabledOrderByPriorityAsc(
                    factoryId, scopeEnum, enabled);
        } else if (scopeEnum != null) {
            rules = ruleRepository.findByFactoryIdAndScopeOrderByPriorityAsc(factoryId, scopeEnum);
        } else if (enabled != null) {
            rules = ruleRepository.findByFactoryIdAndEnabledOrderByPriorityAsc(factoryId, enabled);
        } else {
            rules = ruleRepository.findByFactoryIdOrderByPriorityAsc(factoryId);
        }

        List<Map<String, Object>> data = new ArrayList<>();
        for (BusinessRule r : rules) {
            data.add(serializeRule(r));
        }
        return ApiResponse.success("操作成功", data);
    }

    @Operation(summary = "新建业务规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PostMapping
    @Transactional
    public ApiResponse<Map<String, Object>> createRule(@PathVariable String factoryId,
                                                       @RequestBody Map<String, Object> body) {
        log.info("POST /canvas-rules factoryId={} body keys={}", factoryId, body.keySet());

        String ruleCode = stringField(body, "ruleCode");
        if (ruleCode == null || ruleCode.isBlank()) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "ruleCode 必填", "请填写规则代码 (factoryId 内唯一)", "warning");
        }
        String scopeStr = stringField(body, "scope");
        if (scopeStr == null || scopeStr.isBlank()) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "scope 必填", "请选择规则适用范围: ORDER / INVENTORY / CUSTOMER / CUSTOM", "warning");
        }
        String actionTypeStr = stringField(body, "actionType");
        if (actionTypeStr == null || actionTypeStr.isBlank()) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "actionType 必填", "请选择动作类型: LOG / REJECT / MODIFY / TRIGGER_WORKFLOW", "warning");
        }

        RuleScope scopeEnum;
        try {
            scopeEnum = RuleScope.valueOf(scopeStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "scope 不合法: " + scopeStr,
                    "允许值: ORDER / INVENTORY / CUSTOMER / CUSTOM", "warning");
        }
        RuleActionType actionTypeEnum;
        try {
            actionTypeEnum = RuleActionType.valueOf(actionTypeStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "actionType 不合法: " + actionTypeStr,
                    "允许值: LOG / REJECT / MODIFY / TRIGGER_WORKFLOW", "warning");
        }

        // fool-proof Rule 4: idempotent — same (factoryId, ruleCode) → 409 with actionHint to jump to existing
        Optional<BusinessRule> dup = ruleRepository.findByFactoryIdAndRuleCode(factoryId, ruleCode);
        if (dup.isPresent()) {
            BusinessRule existing = dup.get();
            return ApiResponse.errorWithCode(409, "DUPLICATE",
                    "规则代码已存在: " + ruleCode + " (id=" + existing.getId() + ")",
                    "请使用其他代码, 或前往编辑该规则", "warning");
        }

        BusinessRule rule = BusinessRule.builder()
                .factoryId(factoryId)
                .ruleCode(ruleCode)
                .ruleName(stringField(body, "ruleName", ruleCode))
                .scope(scopeEnum)
                .conditionSpel(stringField(body, "conditionSpel"))
                .actionType(actionTypeEnum)
                .actionConfigJson(mapField(body, "actionConfigJson"))
                .priority(integerField(body, "priority", 100))
                .enabled(booleanField(body, "enabled", true))
                .build();

        BusinessRule saved = ruleRepository.save(rule);
        log.info("Created BusinessRule id={} factoryId={} ruleCode={} scope={} actionType={}",
                saved.getId(), factoryId, ruleCode, scopeEnum, actionTypeEnum);

        return ApiResponse.success("规则创建成功", serializeRule(saved));
    }

    @Operation(summary = "更新业务规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> updateRule(@PathVariable String factoryId,
                                                       @PathVariable UUID id,
                                                       @RequestBody Map<String, Object> body) {
        log.info("PUT /canvas-rules/{} factoryId={} body keys={}", id, factoryId, body.keySet());

        BusinessRule rule = loadRule(factoryId, id);

        // PATCH semantics: only update fields present in body
        if (body.containsKey("ruleCode")) {
            String newCode = stringField(body, "ruleCode");
            if (newCode != null && !newCode.isBlank() && !newCode.equals(rule.getRuleCode())) {
                Optional<BusinessRule> dup = ruleRepository.findByFactoryIdAndRuleCode(factoryId, newCode);
                if (dup.isPresent() && !dup.get().getId().equals(rule.getId())) {
                    return ApiResponse.errorWithCode(409, "DUPLICATE",
                            "规则代码已存在: " + newCode,
                            "请使用其他唯一代码", "warning");
                }
                rule.setRuleCode(newCode);
            }
        }
        if (body.containsKey("ruleName")) {
            rule.setRuleName(stringField(body, "ruleName"));
        }
        if (body.containsKey("scope")) {
            String raw = stringField(body, "scope");
            // F1 fix: PATCH body {scope: null} returns null here; previous code NPE'd on raw.toUpperCase().
            // Per PATCH semantics treat explicit null as "no change"; explicit "" → 400.
            if (raw != null) {
                if (raw.isBlank()) {
                    return ApiResponse.errorWithCode(400, "VALIDATION",
                            "scope 不可为空字符串",
                            "如不修改 scope 请省略该字段; 否则填 ORDER/INVENTORY/CUSTOMER/CUSTOM", "warning");
                }
                try {
                    rule.setScope(RuleScope.valueOf(raw.toUpperCase()));
                } catch (IllegalArgumentException ex) {
                    return ApiResponse.errorWithCode(400, "VALIDATION",
                            "scope 取值非法: " + raw,
                            "允许值: ORDER / INVENTORY / CUSTOMER / CUSTOM", "warning");
                }
            }
        }
        if (body.containsKey("conditionSpel")) {
            rule.setConditionSpel(stringField(body, "conditionSpel"));
        }
        if (body.containsKey("actionType")) {
            String raw = stringField(body, "actionType");
            // F1 fix: same NPE risk as scope — explicit null → no change, explicit "" → 400.
            if (raw != null) {
                if (raw.isBlank()) {
                    return ApiResponse.errorWithCode(400, "VALIDATION",
                            "actionType 不可为空字符串",
                            "如不修改 actionType 请省略该字段; 否则填 LOG/REJECT/MODIFY/TRIGGER_WORKFLOW", "warning");
                }
                try {
                    rule.setActionType(RuleActionType.valueOf(raw.toUpperCase()));
                } catch (IllegalArgumentException ex) {
                    return ApiResponse.errorWithCode(400, "VALIDATION",
                            "actionType 取值非法: " + raw,
                            "允许值: LOG / REJECT / MODIFY / TRIGGER_WORKFLOW", "warning");
                }
            }
        }
        if (body.containsKey("actionConfigJson")) {
            rule.setActionConfigJson(mapField(body, "actionConfigJson"));
        }
        if (body.containsKey("priority")) {
            Integer newPriority = integerField(body, "priority", null);
            if (newPriority != null) {
                rule.setPriority(newPriority);
            }
        }
        if (body.containsKey("enabled")) {
            rule.setEnabled(booleanField(body, "enabled", rule.getEnabled()));
        }

        BusinessRule saved = ruleRepository.save(rule);
        log.info("Updated BusinessRule id={} factoryId={} ruleCode={}",
                saved.getId(), factoryId, saved.getRuleCode());
        return ApiResponse.success("规则更新成功", serializeRule(saved));
    }

    @Operation(summary = "切换规则启用/禁用")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PostMapping("/{id}/toggle")
    @Transactional
    public ApiResponse<Map<String, Object>> toggleRule(@PathVariable String factoryId,
                                                       @PathVariable UUID id) {
        log.info("POST /canvas-rules/{}/toggle factoryId={}", id, factoryId);
        BusinessRule rule = loadRule(factoryId, id);
        rule.setEnabled(!Boolean.TRUE.equals(rule.getEnabled()));
        BusinessRule saved = ruleRepository.save(rule);
        return ApiResponse.success(
                Boolean.TRUE.equals(saved.getEnabled()) ? "规则已启用" : "规则已禁用",
                serializeRule(saved));
    }

    @Operation(summary = "删除业务规则 (软删除)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> deleteRule(@PathVariable String factoryId,
                                                       @PathVariable UUID id) {
        log.info("DELETE /canvas-rules/{} factoryId={}", id, factoryId);
        BusinessRule rule = loadRule(factoryId, id);
        // SQLDelete annotation on BusinessRule entity (@SQLDelete) sets deleted_at on delete()
        ruleRepository.delete(rule);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleId", rule.getId().toString());
        data.put("ruleCode", rule.getRuleCode());
        return ApiResponse.success("规则已删除", data);
    }

    @Operation(summary = "测试评估单条规则 (dry-run)",
               description = "对样本 input 跑一次 condition, 不写日志, 不真实修改")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PostMapping("/{id}/test-evaluate")
    public ApiResponse<Map<String, Object>> testEvaluate(@PathVariable String factoryId,
                                                         @PathVariable UUID id,
                                                         @RequestBody Map<String, Object> sampleInput) {
        log.info("POST /canvas-rules/{}/test-evaluate factoryId={}", id, factoryId);

        // F2 fix: clarify guard order — cross-factory check is via loadRule below (throws BusinessException 403
        // with severity=warning, surfaces as 403 not 500). RuleEngine.preview has its own internal cross-factory
        // assertion as a defensive 2nd line of defense, but loadRule fires FIRST and is the authoritative gate.
        BusinessRule rule = loadRule(factoryId, id);
        boolean matched;
        try {
            matched = ruleEngine.preview(factoryId, id, sampleInput);
        } catch (IllegalArgumentException ex) {
            // Reframe: this catch handles SpEL evaluation failures (bad expression, missing fields in sample input),
            // NOT cross-factory access (loadRule already rejected those with 403).
            return ApiResponse.errorWithCode(400, "PREVIEW_ERROR",
                    "SpEL 表达式评估失败 — 检查 condition 语法 + sample input 字段是否齐全: " + ex.getMessage(),
                    "示例: 若 conditionSpel 引用 #input.totalAmount, 请确保 sampleInput 包含 totalAmount 键", "warning");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleId", rule.getId().toString());
        data.put("ruleCode", rule.getRuleCode());
        data.put("ruleName", rule.getRuleName());
        data.put("scope", rule.getScope() != null ? rule.getScope().name() : null);
        data.put("actionType", rule.getActionType() != null ? rule.getActionType().name() : null);
        data.put("conditionSpel", rule.getConditionSpel());
        data.put("matched", matched);
        if (matched) {
            Map<String, Object> wouldApply = new LinkedHashMap<>();
            wouldApply.put("actionType", rule.getActionType() != null ? rule.getActionType().name() : null);
            wouldApply.put("actionConfig", rule.getActionConfigJson());
            data.put("wouldApply", wouldApply);
        }
        return ApiResponse.success(
                matched
                        ? "规则条件命中 — 实际执行会触发 " + rule.getActionType()
                        : "规则条件未命中, 无动作",
                data);
    }

    @Operation(summary = "查询规则执行历史", description = "分页, 倒序")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/{id}/logs")
    public ApiResponse<Map<String, Object>> listLogs(@PathVariable String factoryId,
                                                     @PathVariable UUID id,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        log.debug("GET /canvas-rules/{}/logs factoryId={} page={} size={}",
                id, factoryId, page, size);

        // factory guard: validate rule belongs to factoryId before exposing logs
        loadRule(factoryId, id);

        if (size > 100) size = 100;
        if (size < 1) size = 20;
        if (page < 0) page = 0;

        Pageable pageable = PageRequest.of(page, size);
        Page<RuleExecutionLog> result = logRepository
                .findByFactoryIdAndRuleIdOrderByExecutedAtDesc(factoryId, id, pageable);

        List<Map<String, Object>> content = new ArrayList<>();
        for (RuleExecutionLog l : result.getContent()) {
            content.add(serializeLog(l));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", content);
        data.put("totalElements", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());
        data.put("number", result.getNumber());
        data.put("size", result.getSize());
        return ApiResponse.success("操作成功", data);
    }

    // ==================== helpers ====================

    private BusinessRule loadRule(String factoryId, UUID id) {
        BusinessRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "规则不存在: id=" + id));
        if (!factoryId.equals(rule.getFactoryId())) {
            throw new BusinessException(403,
                    "无权访问其他工厂的规则 (rule.factoryId=" + rule.getFactoryId() + ")")
                    .withSeverity("warning");
        }
        return rule;
    }

    private RuleScope parseScope(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return RuleScope.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400,
                    "scope 不合法: " + raw + " (允许 ORDER/INVENTORY/CUSTOMER/CUSTOM)")
                    .withSeverity("warning");
        }
    }

    private Map<String, Object> serializeRule(BusinessRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId() != null ? r.getId().toString() : null);
        m.put("factoryId", r.getFactoryId());
        m.put("ruleCode", r.getRuleCode());
        m.put("ruleName", r.getRuleName());
        m.put("scope", r.getScope() != null ? r.getScope().name() : null);
        m.put("conditionSpel", r.getConditionSpel());
        m.put("actionType", r.getActionType() != null ? r.getActionType().name() : null);
        m.put("actionConfigJson", r.getActionConfigJson());
        m.put("priority", r.getPriority());
        m.put("enabled", r.getEnabled());
        m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        m.put("updatedAt", r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> serializeLog(RuleExecutionLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId() != null ? l.getId().toString() : null);
        m.put("ruleId", l.getRuleId() != null ? l.getRuleId().toString() : null);
        m.put("factoryId", l.getFactoryId());
        m.put("triggerEvent", l.getTriggerEvent());
        m.put("inputJson", l.getInputJson());
        m.put("resultJson", l.getResultJson());
        m.put("executedAt", l.getExecutedAt() != null ? l.getExecutedAt().toString() : null);
        return m;
    }

    private String stringField(Map<String, Object> body, String key) {
        return stringField(body, key, null);
    }

    private String stringField(Map<String, Object> body, String key, String defaultValue) {
        Object v = body.get(key);
        if (v == null) return defaultValue;
        return v.toString();
    }

    private Integer integerField(Map<String, Object> body, String key, Integer defaultValue) {
        Object v = body.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private Boolean booleanField(Map<String, Object> body, String key, Boolean defaultValue) {
        Object v = body.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(v.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) v);
        }
        return new HashMap<>();
    }
}
