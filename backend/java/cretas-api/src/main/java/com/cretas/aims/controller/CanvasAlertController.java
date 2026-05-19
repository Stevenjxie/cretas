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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
                    .triggerConditionSpel((String) body.get("triggerConditionSpel"))
                    .severity(parseSeverity(body.get("severity")))
                    .enabled(parseBoolean(body.get("enabled"), true))
                    .notifyChannels(toStringList(body.get("notifyChannels")))
                    .notifyRoles(toStringList(body.get("notifyRoles")))
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
        log.info("PUT /alerts/rules/{} factoryId={} body keys={}", id, factoryId, body.keySet());
        try {
            AlertRule rule = loadRule(factoryId, id);

            // PATCH semantics: only update fields present in body.
            if (body.containsKey("ruleName")) {
                String newName = (String) body.get("ruleName");
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
                rule.setTriggerConditionSpel((String) body.get("triggerConditionSpel"));
            }
            if (body.containsKey("severity")) {
                rule.setSeverity(parseSeverity(body.get("severity")));
            }
            if (body.containsKey("notifyChannels")) {
                rule.setNotifyChannels(toStringList(body.get("notifyChannels")));
            }
            if (body.containsKey("notifyRoles")) {
                rule.setNotifyRoles(toStringList(body.get("notifyRoles")));
            }
            if (body.containsKey("enabled")) {
                rule.setEnabled(parseBoolean(body.get("enabled"), rule.getEnabled()));
            }

            AlertRule saved = ruleRepository.save(rule);
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
            AlertRule saved = ruleRepository.save(rule);
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
            AlertRule saved = ruleRepository.save(rule);
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

    // ==================== helpers ====================

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
