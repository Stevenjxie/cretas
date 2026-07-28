package com.cretas.aims.ai.tool.impl.rules;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.repository.rules.BusinessRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: toggle a Canvas-Rules BusinessRule enabled flag.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class RuleToggleTool extends AbstractBusinessTool {

    @Autowired
    private BusinessRuleRepository ruleRepository;

    @Override
    public String getToolName() {
        return "rule_toggle";
    }

    @Override
    public String getDescription() {
        return "启用或停用某条业务规则。例: '把 vip_discount 停掉' / '启用 po_blacklist'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("ruleCode", Map.of("type", "string", "description", "要切换的规则代码"));
        properties.put("enabled", Map.of("type", "boolean", "description", "true=启用, false=停用"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("ruleCode", "enabled"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("ruleCode", "enabled");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String ruleCode = getString(params, "ruleCode");
        Boolean enabled = getBoolean(params, "enabled");
        if (enabled == null) {
            throw new IllegalArgumentException("enabled 参数必须为 true 或 false");
        }

        BusinessRule rule = ruleRepository.findByFactoryIdAndRuleCode(factoryId, ruleCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "规则 " + ruleCode + " 不存在 (factoryId=" + factoryId + ")"));

        if (Boolean.valueOf(enabled).equals(rule.getEnabled())) {
            return buildSimpleResult(
                    "规则 " + ruleCode + " 已是 " + (enabled ? "启用" : "停用") + " 状态, 无需切换",
                    Map.of("id", String.valueOf(rule.getId()), "enabled", enabled));
        }

        rule.setEnabled(enabled);
        BusinessRule saved = ruleRepository.save(rule);

        log.info("rule_toggle - factory={}, ruleCode={}, newEnabled={}",
                factoryId, ruleCode, enabled);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(saved.getId()));
        data.put("ruleCode", saved.getRuleCode());
        data.put("enabled", saved.getEnabled());
        return buildSimpleResult(
                "规则 " + ruleCode + " 已" + (enabled ? "启用" : "停用"), data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
