package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool — 删除告警规则 (软删, Phase 2 Canvas-Alerts skeleton).
 *
 * <p>sister chat impl: 调 {@code rule.softDelete()} + save. preview 必明确显示
 * "即将删除规则 X (含 N 条历史事件), 是否继续?" (per fool-proof Rule 2).
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
@Slf4j
@Component
public class AlertRuleDeleteTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "alert_rule_delete";
    }

    @Override
    public String getDescription() {
        return "删除告警规则 (软删, 保留历史事件). 必填 ruleId. "
                + "适用场景: 永久关闭某条不再需要的规则.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> ruleId = new HashMap<>();
        ruleId.put("type", "string");
        ruleId.put("description", "告警规则 ID (UUID)");

        Map<String, Object> properties = new HashMap<>();
        properties.put("ruleId", ruleId);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("ruleId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("ruleId");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "Phase 2 sister chat impl pending: alert_rule_delete");
    }
}
