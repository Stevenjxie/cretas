package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool — 创建告警规则 (Phase 2 Canvas-Alerts skeleton).
 *
 * <p>支持 8 类型: INVENTORY_LOW / INVENTORY_EXPIRING / QUALITY_ANOMALY /
 * PO_AMOUNT_THRESHOLD / SO_AMOUNT_THRESHOLD / SALES_DECLINE /
 * CUSTOMER_PAYMENT_OVERDUE / SUPPLIER_PAYABLE_DUE.
 *
 * <p>{@link #doExecute} 当前 throw {@link UnsupportedOperationException},
 * sister chat 在 Phase 2 B-2 实现:
 * <ol>
 *   <li>调 {@code AlertEngineService.createRule(factoryId, ...)} 或直接 repo</li>
 *   <li>preview 模式返 {@code {status:PREVIEW, rule:...}}, execute 持久化</li>
 *   <li>消息含 ruleId + ruleName + 启用状态 (per fool-proof Rule 2)</li>
 * </ol>
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
@Slf4j
@Component
public class AlertRuleCreateTool extends AbstractBusinessTool {

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
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "Phase 2 sister chat impl pending: alert_rule_create");
    }
}
