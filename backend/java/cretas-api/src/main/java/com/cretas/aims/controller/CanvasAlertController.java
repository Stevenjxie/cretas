package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertSeverity;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.service.alerts.AlertEngineService;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator.SpelEvaluationFailure;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 告警规则 / 事件 REST 接口 — Phase 2 Canvas-Alerts.
 *
 * <p>类名 {@code CanvasAlertController} 区分老的 {@link AlertController}
 * (生产告警). Path prefix /rules + /events 跟老 controller 不冲突.
 *
 * <p><b>RBAC</b>: factory_super_admin / permission_admin 才能 CRUD 规则 +
 * 查询事件. 普通用户通过 dashboard / push notification 收事件.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/alerts")
@Tag(name = "Canvas-告警规则", description = "Phase 2 Canvas-Alerts — 预警规则配置 + 事件管理")
@RequiredArgsConstructor
public class CanvasAlertController {

    private final AlertRuleRepository ruleRepository;
    private final AlertEngineService alertEngineService;
    private final SandboxedSpelEvaluator spelEvaluator;

    /** Max length of {@code rule_name} column (AlertRule entity {@code @Column(length=255)}). */
    private static final int RULE_NAME_MAX_LENGTH = 255;

    @GetMapping("/rules")
    @Operation(summary = "列出告警规则", description = "返回工厂全部告警规则 (含 disabled)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<List<Map<String, Object>>> listRules(@PathVariable String factoryId) {
        log.debug("GET /alerts/rules factoryId={}", factoryId);
        List<AlertRule> rules = ruleRepository.findByFactoryId(factoryId);
        List<Map<String, Object>> data = new ArrayList<>();
        for (AlertRule r : rules) {
            data.add(serializeRule(r));
        }
        return ApiResponse.success("操作成功", data);
    }

    @PostMapping("/rules")
    @Operation(summary = "创建告警规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> createRule(@PathVariable String factoryId,
                                                       @RequestBody Map<String, Object> body) {
        log.info("POST /alerts/rules factoryId={} body keys={}", factoryId, body.keySet());

        try {
            String ruleName = (String) body.get("ruleName");
            String alertTypeStr = (String) body.get("alertType");
            if (ruleName == null || ruleName.isBlank()) {
                return ApiResponse.errorWithHint(400, "ruleName 必填",
                        "请提供规则名称", null, null);
            }
            // B-A3 fix: explicit length check produces user-friendly 400 instead of
            // generic 409 "数据处理异常" from PG VARCHAR(255) overflow at flush time.
            if (ruleName.length() > RULE_NAME_MAX_LENGTH) {
                return ApiResponse.errorWithCode(400, "VALIDATION",
                        "规则名称最长 " + RULE_NAME_MAX_LENGTH + " 字符 (当前 " + ruleName.length() + ")",
                        "请使用更短的规则名称", "warning");
            }
            if (alertTypeStr == null || alertTypeStr.isBlank()) {
                return ApiResponse.errorWithHint(400, "alertType 必填",
                        "请选择告警类型 (8 选 1)", null, null);
            }

            AlertType type;
            try {
                type = AlertType.valueOf(alertTypeStr.toUpperCase());
            } catch (IllegalArgumentException ex) {
                return ApiResponse.errorWithHint(400,
                        "alertType 不合法: " + alertTypeStr,
                        "允许值: INVENTORY_LOW/INVENTORY_EXPIRING/QUALITY_ANOMALY/PO_AMOUNT_THRESHOLD/SO_AMOUNT_THRESHOLD/SALES_DECLINE/CUSTOMER_PAYMENT_OVERDUE/SUPPLIER_PAYABLE_DUE",
                        null, null);
            }

            // B-A4/A5 fix: empty notifyChannels / notifyRoles makes the rule useless
            // (no one gets the alert). Reject at write time instead of silent shipping.
            List<String> notifyChannels = toStringList(body.get("notifyChannels"));
            List<String> notifyRoles = toStringList(body.get("notifyRoles"));
            ApiResponse<Map<String, Object>> notifyError = validateNotifyArrays(notifyChannels, notifyRoles);
            if (notifyError != null) {
                return notifyError;
            }

            // B-A7 P0 fix: validate SpEL syntax + reject RCE attempts at write time.
            // Without this, T(java.lang.Runtime).getRuntime().exec(...) was persisted
            // raw and could be executed by AlertEngineService at trigger time.
            String spel = (String) body.get("triggerConditionSpel");
            ApiResponse<Map<String, Object>> spelError = validateSpel(spel);
            if (spelError != null) {
                return spelError;
            }

            Optional<AlertRule> dup = ruleRepository.findByFactoryIdAndRuleName(factoryId, ruleName);
            if (dup.isPresent()) {
                return ApiResponse.errorWithHint(409,
                        "规则名称已存在: " + ruleName + " (id=" + dup.get().getId() + ")",
                        "请换一个唯一的规则名称", null, null);
            }

            AlertRule rule = AlertRule.builder()
                    .factoryId(factoryId)
                    .alertType(type)
                    .ruleName(ruleName)
                    .triggerConditionSpel(spel)
                    .severity(parseSeverity(body.get("severity")))
                    .enabled(parseBoolean(body.get("enabled"), true))
                    .notifyChannels(notifyChannels)
                    .notifyRoles(notifyRoles)
                    .build();

            AlertRule saved = ruleRepository.save(rule);
            log.info("Created AlertRule: id={}, factoryId={}, ruleName={}",
                    saved.getId(), factoryId, ruleName);

            return ApiResponse.success("告警规则创建成功", serializeRule(saved));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.errorWithHint(400, ex.getMessage(), "请检查参数", null, null);
        }
    }

    @PutMapping("/rules/{id}")
    @Operation(summary = "更新告警规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> updateRule(@PathVariable String factoryId,
                                                       @PathVariable String id,
                                                       @RequestBody Map<String, Object> body) {
        return updateRuleInternal(factoryId, id, body, "PUT");
    }

    /**
     * B-A8 fix: PATCH alias for updateRule. Mirrors the canvas-rules PATCH contract
     * (PR #44 added PATCH alongside PUT for the same partial-update semantics).
     * Some clients (HTTP libraries, REST playgrounds) default to PATCH for partial
     * updates; previously returned 405 because only PUT was registered.
     */
    @PatchMapping("/rules/{id}")
    @Operation(summary = "更新告警规则 (PATCH alias of PUT)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> patchRule(@PathVariable String factoryId,
                                                      @PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        return updateRuleInternal(factoryId, id, body, "PATCH");
    }

    private ApiResponse<Map<String, Object>> updateRuleInternal(String factoryId, String id,
                                                                Map<String, Object> body,
                                                                String httpMethod) {
        log.info("{} /alerts/rules/{} factoryId={} body keys={}",
                httpMethod, id, factoryId, body.keySet());
        try {
            AlertRule rule = loadRule(factoryId, id);

            // AUD-4 wiring (PR #94 follow-up): explicit optimistic-lock check fires
            // BEFORE field updates so JPA optimistic lock actually trips on stale
            // client version. The findById-then-save pattern reads version=N from DB
            // on every load, so parallel PUTs with stale `version:0` would both refetch
            // a "fresh" copy and both save without conflict. Comparing the client's
            // submitted version (snapshotted when the user opened the edit form) against
            // the just-loaded DB version surfaces stale-writes as 409 instead of silent
            // last-writer-wins. Null = lenient (legacy clients keep working).
            ApiResponse<Map<String, Object>> versionError =
                    checkVersion(body.get("version"), rule.getVersion());
            if (versionError != null) {
                return versionError;
            }

            // PATCH semantics: only update fields present in body.
            if (body.containsKey("ruleName")) {
                String newName = (String) body.get("ruleName");
                // B-A3 fix: length check produces specific 400 before PG VARCHAR overflow.
                if (newName != null && newName.length() > RULE_NAME_MAX_LENGTH) {
                    return ApiResponse.errorWithCode(400, "VALIDATION",
                            "规则名称最长 " + RULE_NAME_MAX_LENGTH + " 字符 (当前 " + newName.length() + ")",
                            "请使用更短的规则名称", "warning");
                }
                if (newName != null && !newName.isBlank() && !newName.equals(rule.getRuleName())) {
                    Optional<AlertRule> dup = ruleRepository.findByFactoryIdAndRuleName(factoryId, newName);
                    if (dup.isPresent() && !dup.get().getId().equals(rule.getId())) {
                        return ApiResponse.errorWithHint(409,
                                "规则名称已存在: " + newName,
                                "请换一个唯一的规则名称", null, null);
                    }
                    rule.setRuleName(newName);
                }
            }
            if (body.containsKey("triggerConditionSpel")) {
                // B-A7 P0 fix: validate at write time to block RCE injection on update path.
                String spel = (String) body.get("triggerConditionSpel");
                ApiResponse<Map<String, Object>> spelError = validateSpel(spel);
                if (spelError != null) {
                    return spelError;
                }
                rule.setTriggerConditionSpel(spel);
            }
            if (body.containsKey("severity")) {
                rule.setSeverity(parseSeverity(body.get("severity")));
            }
            // B-A4/A5 fix: reject empty arrays on update (consistent with create).
            // If client sends {notifyChannels:[]} explicitly, treat as validation error.
            // (Omitting the field is OK — PATCH semantics keep current value.)
            List<String> newChannels = body.containsKey("notifyChannels")
                    ? toStringList(body.get("notifyChannels")) : null;
            List<String> newRoles = body.containsKey("notifyRoles")
                    ? toStringList(body.get("notifyRoles")) : null;
            if (newChannels != null && newChannels.isEmpty()) {
                return ApiResponse.errorWithCode(400, "VALIDATION",
                        "notifyChannels 不可为空",
                        "至少选择一个通知渠道: EMAIL / SMS / IN_APP / WECHAT", "warning");
            }
            if (newRoles != null && newRoles.isEmpty()) {
                return ApiResponse.errorWithCode(400, "VALIDATION",
                        "notifyRoles 不可为空",
                        "至少选择一个通知接收角色", "warning");
            }
            if (newChannels != null) {
                rule.setNotifyChannels(newChannels);
            }
            if (newRoles != null) {
                rule.setNotifyRoles(newRoles);
            }
            if (body.containsKey("enabled")) {
                rule.setEnabled(parseBoolean(body.get("enabled"), rule.getEnabled()));
            }

            // saveAndFlush forces @Version increment + UPDATE before serializeRule
            // reads in-memory entity (per HARD rule — `final` + serializeRule pattern
            // captures pre-flush state otherwise, shipping stale version to client).
            AlertRule saved = ruleRepository.saveAndFlush(rule);
            return ApiResponse.success("告警规则更新成功", serializeRule(saved));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.errorWithHint(400, ex.getMessage(), "请检查参数", null, null);
        }
    }

    @PostMapping("/rules/{id}/toggle")
    @Operation(summary = "启用/禁用 告警规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> toggleRule(@PathVariable String factoryId,
                                                       @PathVariable String id) {
        log.info("POST /alerts/rules/{}/toggle factoryId={}", id, factoryId);
        try {
            AlertRule rule = loadRule(factoryId, id);
            boolean current = Boolean.TRUE.equals(rule.getEnabled());
            rule.setEnabled(!current);
            // saveAndFlush so serializeRule sees post-flush version.
            AlertRule saved = ruleRepository.saveAndFlush(rule);
            return ApiResponse.success(
                    saved.getEnabled() ? "告警规则已启用" : "告警规则已禁用",
                    serializeRule(saved));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.errorWithHint(400, ex.getMessage(), "请检查参数", null, null);
        }
    }

    @DeleteMapping("/rules/{id}")
    @Operation(summary = "删除告警规则 (软删)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> deleteRule(@PathVariable String factoryId,
                                                       @PathVariable String id) {
        log.info("DELETE /alerts/rules/{} factoryId={}", id, factoryId);
        try {
            AlertRule rule = loadRule(factoryId, id);
            rule.softDelete();
            // saveAndFlush so deletedAt UPDATE + version increment fires before
            // we read saved.getDeletedAt() into the response map.
            AlertRule saved = ruleRepository.saveAndFlush(rule);
            Map<String, Object> data = new HashMap<>();
            data.put("ruleId", saved.getId().toString());
            data.put("deletedAt", saved.getDeletedAt() != null ? saved.getDeletedAt().toString() : null);
            return ApiResponse.success("告警规则已软删除", data);
        } catch (IllegalArgumentException ex) {
            return ApiResponse.errorWithHint(400, ex.getMessage(), "请检查参数", null, null);
        }
    }

    @GetMapping("/events")
    @Operation(summary = "查询告警事件 (分页)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> listEvents(@PathVariable String factoryId,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        log.debug("GET /alerts/events factoryId={} status={} page={} size={}",
                factoryId, status, page, size);

        AlertEventStatus statusEnum = null;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            try {
                statusEnum = AlertEventStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException ex) {
                return ApiResponse.errorWithHint(400,
                        "status 不合法: " + status,
                        "允许值: OPEN/ACKNOWLEDGED/RESOLVED/ALL", null, null);
            }
        }

        if (size > 100) size = 100;
        if (size < 1) size = 20;
        if (page < 0) page = 0;

        Pageable pageable = PageRequest.of(page, size);
        Page<AlertEvent> result = alertEngineService.findEvents(factoryId, statusEnum, pageable);

        List<Map<String, Object>> content = new ArrayList<>();
        for (AlertEvent e : result.getContent()) {
            content.add(serializeEvent(e));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("content", content);
        data.put("totalElements", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());
        data.put("number", result.getNumber());
        data.put("size", result.getSize());

        return ApiResponse.success("操作成功", data);
    }

    @PostMapping("/events/{eventId}/acknowledge")
    @Operation(summary = "确认告警事件 (OPEN → ACKNOWLEDGED)",
               description = "用户在 UI 点 ack 时调用. 记录确认人 + 确认时间.")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> acknowledgeEvent(@PathVariable String factoryId,
                                                             @PathVariable String eventId,
                                                             HttpServletRequest request) {
        log.info("POST /alerts/events/{}/acknowledge factoryId={}", eventId, factoryId);
        try {
            UUID uuid = parseEventId(eventId);
            Long userId = getUserId(request);
            AlertEvent updated = alertEngineService.acknowledge(uuid, userId);

            // factory ownership guard: prevent cross-factory ack via path-param mismatch
            if (updated != null && !factoryId.equals(updated.getFactoryId())) {
                return ApiResponse.errorWithHint(403,
                        "事件不属于工厂 " + factoryId + " (实际工厂=" + updated.getFactoryId() + ")",
                        "请用正确的 factoryId 路径访问", null, null);
            }

            return ApiResponse.success("已确认", serializeEvent(updated));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.errorWithHint(400, ex.getMessage(), "请检查参数", null, null);
        }
    }

    @PostMapping("/events/{eventId}/resolve")
    @Operation(summary = "解决告警事件 (→ RESOLVED)",
               description = "用户在 UI 点 resolve / 系统自动 clear 时调用. 记录解决人 + 解决时间.")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> resolveEvent(@PathVariable String factoryId,
                                                         @PathVariable String eventId,
                                                         HttpServletRequest request) {
        log.info("POST /alerts/events/{}/resolve factoryId={}", eventId, factoryId);
        try {
            UUID uuid = parseEventId(eventId);
            Long userId = getUserId(request);
            AlertEvent updated = alertEngineService.resolve(uuid, userId);

            if (updated != null && !factoryId.equals(updated.getFactoryId())) {
                return ApiResponse.errorWithHint(403,
                        "事件不属于工厂 " + factoryId + " (实际工厂=" + updated.getFactoryId() + ")",
                        "请用正确的 factoryId 路径访问", null, null);
            }

            return ApiResponse.success("已解决", serializeEvent(updated));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.errorWithHint(400, ex.getMessage(), "请检查参数", null, null);
        }
    }

    // ==================== helpers ====================

    /**
     * AUD-4 wiring (PR #94 follow-up): explicit optimistic-lock version check.
     *
     * <p>PR #94 added {@code @Version Long version} to {@link AlertRule} + Flyway DDL,
     * but the controller pattern ({@code findById → setFields → save}) reads the current
     * DB version on every load, so two parallel PUTs each see "fresh" entities — JPA never
     * sees a stale version mismatch internally. This helper compares the client-supplied
     * version (snapshotted when the user opened the edit form) against the just-loaded DB
     * version. If the user's snapshot is stale, somebody else already saved; return 409
     * instead of silently overwriting.
     *
     * <p>Coercion: body values come from Jackson {@code Map<String, Object>} where numeric
     * literals deserialize as {@code Integer} / {@code Long} depending on size. Accept any
     * {@link Number} and compare via {@code longValue()}. Strings that parse cleanly are
     * also accepted (some HTTP clients stringify numbers). Anything else = lenient skip
     * (matches null-tolerance for legacy clients).
     *
     * <p>4-in-1 UX (per fool-proof Rule 1 + qa-prompt v2.4 Rule 8): specific message
     * surfacing both versions + actionHint pointing to "refresh page" + severity warning
     * for sticky toast + 409 status.
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
            return ApiResponse.errorWithHint(409,
                    "数据已被其他用户修改 (服务端 v=" + currentVersion
                            + ", 客户端 v=" + requestVersion + ")",
                    "请刷新页面查看最新数据后再编辑",
                    "warning",
                    null);
        }
        return null;
    }

    /**
     * B-A4/A5 fix: validate notifyChannels / notifyRoles on create path.
     * Returns non-null error response when arrays are empty; null when OK.
     */
    private ApiResponse<Map<String, Object>> validateNotifyArrays(List<String> notifyChannels,
                                                                  List<String> notifyRoles) {
        if (notifyChannels == null || notifyChannels.isEmpty()) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "notifyChannels 不可为空",
                    "至少选择一个通知渠道: EMAIL / SMS / IN_APP / WECHAT", "warning");
        }
        if (notifyRoles == null || notifyRoles.isEmpty()) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "notifyRoles 不可为空",
                    "至少选择一个通知接收角色", "warning");
        }
        return null;
    }

    /**
     * B-A7 P0 fix: validate triggerConditionSpel at write time. Returns non-null
     * error response when SpEL contains RCE / reflection / forbidden constructs,
     * or has syntax errors. Returns null when:
     *   - SpEL is null/blank (allowed — see AlertRule.triggerConditionSpel javadoc:
     *     NULL means "use built-in default logic for this alert type")
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
                            : "仅支持 #input/#order/#material 等业务字段访问, 禁用 T()/new/反射",
                    "warning");
        }
    }

    private UUID parseEventId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("eventId 不是合法 UUID: " + id);
        }
    }

    private Long getUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        if (userId == null) return null;
        if (userId instanceof Long) return (Long) userId;
        if (userId instanceof Integer) return ((Integer) userId).longValue();
        try {
            return Long.parseLong(userId.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private AlertRule loadRule(String factoryId, String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("ruleId 不是合法 UUID: " + id);
        }
        AlertRule rule = ruleRepository.findById(uuid)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: id=" + id));
        if (!rule.getFactoryId().equals(factoryId)) {
            throw new IllegalArgumentException(
                    "告警规则不属于工厂 " + factoryId + " (实际工厂=" + rule.getFactoryId() + ")");
        }
        return rule;
    }

    private Map<String, Object> serializeRule(AlertRule r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId() != null ? r.getId().toString() : null);
        m.put("factoryId", r.getFactoryId());
        m.put("alertType", r.getAlertType() != null ? r.getAlertType().name() : null);
        m.put("ruleName", r.getRuleName());
        m.put("triggerConditionSpel", r.getTriggerConditionSpel());
        m.put("severity", r.getSeverity() != null ? r.getSeverity().name() : null);
        m.put("enabled", r.getEnabled());
        m.put("notifyChannels", r.getNotifyChannels());
        m.put("notifyRoles", r.getNotifyRoles());
        m.put("version", r.getVersion());  // AUD-4 wiring (PR #100): FE needs version for stale-check on PUT
        m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        m.put("updatedAt", r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> serializeEvent(AlertEvent e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId() != null ? e.getId().toString() : null);
        m.put("ruleId", e.getRuleId() != null ? e.getRuleId().toString() : null);
        m.put("factoryId", e.getFactoryId());
        m.put("businessEntityType", e.getBusinessEntityType());
        m.put("businessEntityId", e.getBusinessEntityId());
        m.put("severity", e.getSeverity() != null ? e.getSeverity().name() : null);
        m.put("message", e.getMessage());
        m.put("status", e.getStatus() != null ? e.getStatus().name() : null);
        m.put("ackedByUserId", e.getAckedByUserId());
        m.put("ackedAt", e.getAckedAt() != null ? e.getAckedAt().toString() : null);
        m.put("resolvedByUserId", e.getResolvedByUserId());
        m.put("resolvedAt", e.getResolvedAt() != null ? e.getResolvedAt().toString() : null);
        m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        return m;
    }

    private AlertSeverity parseSeverity(Object raw) {
        if (raw == null) return AlertSeverity.MID;
        try {
            return AlertSeverity.valueOf(raw.toString().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("severity 不合法: " + raw + " (允许 LOW/MID/HIGH)");
        }
    }

    private Boolean parseBoolean(Object raw, Boolean defaultValue) {
        if (raw == null) return defaultValue;
        if (raw instanceof Boolean) return (Boolean) raw;
        return Boolean.parseBoolean(raw.toString());
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object raw) {
        if (raw == null) return new ArrayList<>();
        if (raw instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<Object>) raw) {
                if (o != null) out.add(o.toString());
            }
            return out;
        }
        return new ArrayList<>();
    }
}
