package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertSeverity;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI Tool — 创建告警规则 (Phase 2 Canvas-Alerts).
 *
 * <p>支持 8 类型: INVENTORY_LOW / INVENTORY_EXPIRING / QUALITY_ANOMALY /
 * PO_AMOUNT_THRESHOLD / SO_AMOUNT_THRESHOLD / SALES_DECLINE /
 * CUSTOMER_PAYMENT_OVERDUE / SUPPLIER_PAYABLE_DUE.
 *
 * <p>Preview 显示规则草稿; execute 持久化.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertRuleCreateTool extends AbstractBusinessTool {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Override
    public String getToolName() {
        return "alert_rule_create";
    }

    @Override
    public String getDescription() {
        return "创建告警规则 (8 类型: inventory_low/expiring/quality_anomaly/po_amount/"
                + "so_amount/sales_decline/payment_overdue/payable_due). "
                + "需提供 alertType + ruleName, 可选 triggerConditionSpel / severity / "
                + "notifyChannels / notifyRoles. 适用场景: 配置库存预警 / 大额订单预警等.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> alertType = new HashMap<>();
        alertType.put("type", "string");
        alertType.put("description", "告警类型 (8 选 1)");
        alertType.put("enum", List.of(
                "INVENTORY_LOW", "INVENTORY_EXPIRING", "QUALITY_ANOMALY",
                "PO_AMOUNT_THRESHOLD", "SO_AMOUNT_THRESHOLD", "SALES_DECLINE",
                "CUSTOMER_PAYMENT_OVERDUE", "SUPPLIER_PAYABLE_DUE"));

        Map<String, Object> ruleName = new HashMap<>();
        ruleName.put("type", "string");
        ruleName.put("description", "规则名称 (工厂内唯一)");

        Map<String, Object> spel = new HashMap<>();
        spel.put("type", "string");
        spel.put("description", "SpEL 触发条件, 评估时绑定 #context.* (可选)");

        Map<String, Object> severity = new HashMap<>();
        severity.put("type", "string");
        severity.put("description", "严重度");
        severity.put("enum", List.of("LOW", "MID", "HIGH"));

        Map<String, Object> notifyChannels = new HashMap<>();
        notifyChannels.put("type", "array");
        notifyChannels.put("description", "通知渠道 list (WECHAT/DINGTALK/EMAIL/IN_APP)");

        Map<String, Object> notifyRoles = new HashMap<>();
        notifyRoles.put("type", "array");
        notifyRoles.put("description", "通知接收角色 list (role codes)");

        Map<String, Object> properties = new HashMap<>();
        properties.put("alertType", alertType);
        properties.put("ruleName", ruleName);
        properties.put("triggerConditionSpel", spel);
        properties.put("severity", severity);
        properties.put("notifyChannels", notifyChannels);
        properties.put("notifyRoles", notifyRoles);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("alertType", "ruleName"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("alertType", "ruleName");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        AlertType type = parseAlertType(params);
        String ruleName = getString(params, "ruleName");
        Optional<AlertRule> existing = ruleRepository.findByFactoryIdAndRuleName(factoryId, ruleName);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "PREVIEW");
        result.put("action", "CREATE");
        result.put("factoryId", factoryId);
        result.put("alertType", type.name());
        result.put("ruleName", ruleName);
        result.put("triggerConditionSpel", getString(params, "triggerConditionSpel"));
        result.put("severity", parseSeverity(params).name());
        result.put("notifyChannels", parseList(params, "notifyChannels"));
        result.put("notifyRoles", parseList(params, "notifyRoles"));
        if (existing.isPresent()) {
            result.put("warning", "规则名称已存在 (id=" + existing.get().getId() + "), execute 将失败");
        }
        result.put("message", String.format(
                "即将创建告警规则: %s (类型=%s)%s",
                ruleName, type.name(),
                existing.isPresent() ? " — 名称冲突, 请改名" : ""));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        AlertType type = parseAlertType(params);
        String ruleName = getString(params, "ruleName");

        Optional<AlertRule> existing = ruleRepository.findByFactoryIdAndRuleName(factoryId, ruleName);
        if (existing.isPresent()) {
            throw new IllegalArgumentException(
                    "告警规则名称 '" + ruleName + "' 已存在 (id=" + existing.get().getId() + ")");
        }

        AlertRule rule = AlertRule.builder()
                .factoryId(factoryId)
                .alertType(type)
                .ruleName(ruleName)
                .triggerConditionSpel(getString(params, "triggerConditionSpel"))
                .severity(parseSeverity(params))
                .enabled(Boolean.TRUE)
                .notifyChannels(parseList(params, "notifyChannels"))
                .notifyRoles(parseList(params, "notifyRoles"))
                .build();

        AlertRule saved = ruleRepository.save(rule);
        log.info("alert_rule_create: factoryId={}, ruleId={}, ruleName={}, type={}",
                factoryId, saved.getId(), ruleName, type);

        Map<String, Object> data = new HashMap<>();
        data.put("ruleId", saved.getId().toString());
        data.put("factoryId", factoryId);
        data.put("alertType", type.name());
        data.put("ruleName", ruleName);
        data.put("severity", saved.getSeverity().name());
        data.put("enabled", saved.getEnabled());

        return buildSimpleResult(String.format("告警规则 [%s] 创建成功 (id=%s)",
                ruleName, saved.getId()), data);
    }

    private AlertType parseAlertType(Map<String, Object> params) {
        String raw = getString(params, "alertType");
        try {
            return AlertType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("alertType 不合法: " + raw
                    + " (允许 INVENTORY_LOW/INVENTORY_EXPIRING/QUALITY_ANOMALY/"
                    + "PO_AMOUNT_THRESHOLD/SO_AMOUNT_THRESHOLD/SALES_DECLINE/"
                    + "CUSTOMER_PAYMENT_OVERDUE/SUPPLIER_PAYABLE_DUE)");
        }
    }

    private AlertSeverity parseSeverity(Map<String, Object> params) {
        String raw = getString(params, "severity", "MID");
        try {
            return AlertSeverity.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("severity 不合法: " + raw + " (允许 LOW/MID/HIGH)");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseList(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return new ArrayList<>();
        }
        if (value instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<Object>) value) {
                if (o != null) out.add(o.toString());
            }
            return out;
        }
        return new ArrayList<>();
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
