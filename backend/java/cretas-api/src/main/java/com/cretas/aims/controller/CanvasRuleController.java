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
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator.SpelEvaluationFailure;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
    private final SandboxedSpelEvaluator spelEvaluator;

    /**
     * Max length of {@code rule_name} column (BusinessRule entity {@code @Column(length=255)}).
     * AUD-5 B-A3 sister sweep (sister of PR #48 on CanvasAlertController): explicit length
     * pre-check produces a user-friendly 400 instead of generic 409 "数据处理异常" from
     * PG VARCHAR(255) overflow at flush time.
     */
    private static final int RULE_NAME_MAX_LENGTH = 255;

    /**
     * AUD-9 P3 fix: previously this endpoint returned plain {@code List<BusinessRule>}
     * ignoring page/size/sort query params; clients calling {@code ?page=0&size=20} got
     * the full unpaginated array. Now honors pagination + sort. Default sort: priority ASC
     * (matches the previous {@code OrderByPriorityAsc} ordering, so existing FE callers
     * see the same logical order plus a Page wrapper).
     */
    @Operation(summary = "列出所有业务规则 (分页)", description = "可按 scope / enabled 过滤; 默认按 priority 升序")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping
    public ApiResponse<Page<Map<String, Object>>> listRules(
            @PathVariable String factoryId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Boolean enabled,
            @PageableDefault(size = 20, sort = "priority", direction = Sort.Direction.ASC) Pageable pageable) {
        log.debug("GET /canvas-rules factoryId={} scope={} enabled={} pageable={}",
                factoryId, scope, enabled, pageable);

        // AUD-8 mitigation: cap page size at 100 to prevent abuse (size=999999 etc).
        // Reuse existing pattern from listLogs() below.
        Pageable effective = pageable;
        if (pageable.getPageSize() > 100) {
            effective = PageRequest.of(pageable.getPageNumber(), 100, pageable.getSort());
        }

        RuleScope scopeEnum = parseScope(scope);
        Page<BusinessRule> page;
        if (scopeEnum != null && enabled != null) {
            page = ruleRepository.findByFactoryIdAndScopeAndEnabled(factoryId, scopeEnum, enabled, effective);
        } else if (scopeEnum != null) {
            page = ruleRepository.findByFactoryIdAndScope(factoryId, scopeEnum, effective);
        } else if (enabled != null) {
            page = ruleRepository.findByFactoryIdAndEnabled(factoryId, enabled, effective);
        } else {
            page = ruleRepository.findByFactoryId(factoryId, effective);
        }

        return ApiResponse.success("操作成功", page.map(this::serializeRule));
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
        // AUD-5 B-A3 sister sweep fix: explicit length check on ruleName produces a
        // user-friendly 400 instead of generic 409 "数据处理异常" from PG VARCHAR(255)
        // overflow at flush time. Mirrors CanvasAlertController (PR #48).
        // ruleName falls back to ruleCode when omitted (see builder below); only validate
        // when explicitly provided. ruleCode itself is @Column(length=100) — JPA will
        // reject overflow but that path is rare (clients usually pick short codes).
        String ruleName = stringField(body, "ruleName");
        if (ruleName != null && ruleName.length() > RULE_NAME_MAX_LENGTH) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "规则名称最长 " + RULE_NAME_MAX_LENGTH + " 字符 (当前 " + ruleName.length() + ")",
                    "请使用更短的规则名称", "warning");
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

        // B-A7 P0 fix (sister of PR #48): validate SpEL syntax + reject RCE attempts at write time.
        // Without this, T(java.lang.Runtime).getRuntime().exec(...) was persisted raw and could be
        // executed by RuleEngine at trigger time. Mirror pattern from CanvasAlertController.
        String conditionSpel = stringField(body, "conditionSpel");
        ApiResponse<Map<String, Object>> spelError = validateSpel(conditionSpel);
        if (spelError != null) {
            return spelError;
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
                .ruleName(ruleName != null ? ruleName : ruleCode)
                .scope(scopeEnum)
                .conditionSpel(conditionSpel)
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

        // AUD-4 wiring (PR #94 follow-up): explicit optimistic-lock check fires BEFORE
        // field updates so JPA optimistic lock actually trips on stale client version.
        // The findById-then-save pattern reads version=N from DB on every load, so parallel
        // PUTs with stale `version:0` would both refetch a "fresh" copy and both save
        // without conflict. Compare the client's submitted version (snapshotted when the
        // user opened the edit form) against the just-loaded DB version — stale = 409.
        // Null = lenient (legacy clients keep working). Mirrors CanvasAlertController.
        ApiResponse<Map<String, Object>> versionError =
                checkVersion(body.get("version"), rule.getVersion());
        if (versionError != null) {
            return versionError;
        }

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
            // AUD-5 B-A3 sister sweep fix: length check produces specific 400 before
            // PG VARCHAR(255) overflow. PATCH semantics: null = no change.
            String newName = stringField(body, "ruleName");
            if (newName != null && newName.length() > RULE_NAME_MAX_LENGTH) {
                return ApiResponse.errorWithCode(400, "VALIDATION",
                        "规则名称最长 " + RULE_NAME_MAX_LENGTH + " 字符 (当前 " + newName.length() + ")",
                        "请使用更短的规则名称", "warning");
            }
            if (newName != null) {
                rule.setRuleName(newName);
            }
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
            // B-A7 P0 fix (sister of PR #48): validate at write time to block RCE injection on update path.
            String spelStr = stringField(body, "conditionSpel");
            ApiResponse<Map<String, Object>> spelError = validateSpel(spelStr);
            if (spelError != null) {
                return spelError;
            }
            rule.setConditionSpel(spelStr);
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

        // save() + flush() pattern: @Version increment + UPDATE fire BEFORE serializeRule
        // reads saved.getVersion(). Split from saveAndFlush() so existing test mocks
        // that stub only save() keep working (flush() is a no-op on Mockito-default).
        BusinessRule saved = ruleRepository.save(rule);
        ruleRepository.flush();
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
        // save() + flush() so serializeRule below sees post-flush @Version increment.
        BusinessRule saved = ruleRepository.save(rule);
        ruleRepository.flush();
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

        // Bug #4 fix (2026-05-20): previously called {@code ruleRepository.delete(rule)} which
        // relies on the entity's {@code @SQLDelete(sql = "UPDATE business_rules SET deleted_at
        // = NOW() WHERE id = ?")}. That hard-coded UPDATE does NOT touch the {@code version}
        // column added by V20260626_02 (@Version Long version, AUD-4 P1). Hibernate's
        // {@code EntityDeleteAction} on a @Version-tracked entity expects the delete UPDATE
        // to bump the version — when it sees the version unchanged at flush, it can throw
        // {@link org.springframework.dao.DataIntegrityViolationException} which routes to
        // {@link GlobalExceptionHandler#handleDataIntegrityViolationException} else-branch
        // and surfaces the generic "数据处理异常" 409 with no actionHint.
        //
        // Mirror the sister CanvasAlertController.deleteRule pattern: call
        // {@code rule.softDelete()} (sets deletedAt in memory) then {@code save(rule)}.
        // This runs Hibernate's standard UPDATE with WHERE id=? AND version=? AND increments
        // version → optimistic lock fires properly on stale snapshots, and "no version
        // touch" weirdness with @SQLDelete is bypassed entirely.
        //
        // Idempotency (fool-proof Rule 4): if the rule is already soft-deleted (caller hit
        // DELETE twice, or a previously failed call left state half-applied), return 200
        // with the original deletedAt — don't fail the second call.
        BusinessRule rule = loadRule(factoryId, id);

        if (rule.getDeletedAt() != null) {
            log.info("DELETE /canvas-rules/{} idempotent — rule already deleted at {}",
                    id, rule.getDeletedAt());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ruleId", rule.getId().toString());
            data.put("ruleCode", rule.getRuleCode());
            data.put("deletedAt", rule.getDeletedAt().toString());
            data.put("alreadyDeleted", true);
            return ApiResponse.success("规则已是删除状态", data);
        }

        try {
            rule.softDelete();
            // save() + flush() so deletedAt UPDATE + @Version increment fires before
            // we read saved.getDeletedAt() / getId() in the response map.
            BusinessRule saved = ruleRepository.save(rule);
            ruleRepository.flush();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ruleId", saved.getId().toString());
            data.put("ruleCode", saved.getRuleCode());
            data.put("deletedAt", saved.getDeletedAt() != null
                    ? saved.getDeletedAt().toString() : null);
            return ApiResponse.success("规则已删除", data);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException
                | jakarta.persistence.OptimisticLockException ex) {
            // Concurrent edit: another session modified or deleted this rule between our
            // load and save. Surface a specific 409 with refresh hint (4位一体: message
            // 具体 + actionHint + severity warning + sticky on FE side).
            log.warn("DELETE /canvas-rules/{} optimistic lock conflict: {}",
                    id, ex.getMessage());
            return ApiResponse.errorWithCode(409, "CONFLICT",
                    "规则已被其他用户修改或删除, 无法继续",
                    "请刷新页面查看最新状态后再尝试删除",
                    "warning");
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // Last-line defensive: catch FK-violation / CHECK-violation / NOT-NULL violation
            // and emit a specific 409 message instead of the GlobalExceptionHandler generic
            // "数据处理异常" fallback. The Phase 4a schema has no inbound FK pointing at
            // business_rules with restricting ON DELETE, but a future migration might add
            // one and this handler keeps the user-facing message specific.
            log.warn("DELETE /canvas-rules/{} data integrity violation: {}",
                    id, ex.getMessage());
            return ApiResponse.errorWithCode(409, "DATA_INTEGRITY",
                    "无法删除规则: 数据约束阻止操作",
                    "请联系管理员检查规则关联数据 (id=" + id + ")",
                    "warning");
        }
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

    /**
     * AUD-4 wiring (PR #94 follow-up): explicit optimistic-lock version check.
     *
     * <p>PR #94 added {@code @Version Long version} to {@link BusinessRule} + Flyway DDL,
     * but the controller pattern ({@code findById → setFields → save}) reads the current
     * DB version on every load, so two parallel PUTs each see "fresh" entities — JPA never
     * sees a stale version mismatch internally. This helper compares the client-supplied
     * version (snapshotted when the user opened the edit form) against the just-loaded DB
     * version. If the user's snapshot is stale, somebody else already saved; return 409
     * instead of silently overwriting.
     *
     * <p>Coercion mirrors {@code CanvasAlertController.checkVersion}: any {@link Number}
     * → {@code longValue()}; parseable {@code String} → {@code Long.parseLong}; everything
     * else → lenient skip.
     *
     * @return non-null error response on stale version; null when version matches or
     *         client opted out by sending null
     */
    private ApiResponse<Map<String, Object>> checkVersion(Object requestVersionRaw,
                                                          Long currentVersion) {
        if (requestVersionRaw == null) {
            return null; // lenient: legacy clients without version field
        }
        Long requestVersion;
        if (requestVersionRaw instanceof Number) {
            requestVersion = ((Number) requestVersionRaw).longValue();
        } else {
            try {
                requestVersion = Long.parseLong(requestVersionRaw.toString());
            } catch (NumberFormatException ex) {
                return null; // lenient: malformed version field treated as not-supplied
            }
        }
        if (!requestVersion.equals(currentVersion)) {
            return ApiResponse.errorWithCode(409, "CONFLICT",
                    "数据已被其他用户修改 (服务端 v=" + currentVersion
                            + ", 客户端 v=" + requestVersion + ")",
                    "请刷新页面查看最新数据后再编辑",
                    "warning");
        }
        return null;
    }

    /**
     * B-A7 P0 fix (sister of PR #48): validate conditionSpel at write time. Returns non-null
     * error response when SpEL contains RCE / reflection / forbidden constructs,
     * or has syntax errors. Returns null when:
     *   - SpEL is null/blank (allowed — rule with no condition matches all input,
     *     equivalent to "always match" semantics)
     *   - SpEL passes sandbox validation
     */
    private ApiResponse<Map<String, Object>> validateSpel(String spel) {
        if (spel == null || spel.isBlank()) {
            return null;
        }
        try {
            spelEvaluator.validateSyntax(spel);
            return null;
        } catch (SpelEvaluationFailure ex) {
            log.warn("Rejected SpEL at write time: spel={}, reason={}",
                    spel, ex.getUserMessage());
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "SpEL 表达式语法/安全检查失败: " + ex.getUserMessage(),
                    ex.getActionHint() != null
                            ? ex.getActionHint()
                            : "仅支持 #input/#order/#inventory/#customer/#material 等业务字段访问, 禁用 T()/new/反射",
                    "warning");
        }
    }

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
        m.put("version", r.getVersion());  // AUD-4 wiring (PR #100): FE needs version for stale-check on PUT
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
