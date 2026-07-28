package com.cretas.aims.ai.tool.impl.rules;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.repository.rules.BusinessRuleRepository;
import com.cretas.aims.service.rules.RuleEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: dry-run a Canvas-Rules BusinessRule against sample input.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * <p>Delegates to {@link RuleEngine#preview(String, java.util.UUID, Object)} —
 * no side effects, no log row, no real mutation. Used by Canvas UI "测试评估" button
 * and by LLM "如果客户 X 下单 5 万会触发哪些规则?" Q&A.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class RuleTestEvaluateTool extends AbstractBusinessTool {

    @Autowired
    private BusinessRuleRepository ruleRepository;

    @Autowired
    private RuleEngine ruleEngine;

    @Override
    public String getToolName() {
        return "rule_test_evaluate";
    }

    @Override
    public String getDescription() {
        return "在不实际修改数据的前提下, 模拟一条规则对某个样本输入的评估结果。"
             + "例: '如果 SO ¥5 万会触发哪些规则?' / '测试 po_blacklist 规则'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("ruleCode", Map.of("type", "string", "description", "要测试的规则代码"));
        properties.put("sampleInput", Map.of("type", "object",
                "description", "样本 input 对象, 作为 SpEL 评估的 root context (绑定为 #input)"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("ruleCode", "sampleInput"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("ruleCode", "sampleInput");
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String ruleCode = getString(params, "ruleCode");
        Object sampleInput = params.get("sampleInput");
        if (!(sampleInput instanceof Map)) {
            throw new IllegalArgumentException("sampleInput 必须为 object 类型 (Map)");
        }

        BusinessRule rule = ruleRepository.findByFactoryIdAndRuleCode(factoryId, ruleCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "规则 " + ruleCode + " 不存在 (factoryId=" + factoryId + ")"));

        boolean matched = ruleEngine.preview(factoryId, rule.getId(), sampleInput);
        log.info("rule_test_evaluate - factory={}, ruleCode={}, matched={}",
                factoryId, ruleCode, matched);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleId", String.valueOf(rule.getId()));
        data.put("ruleCode", rule.getRuleCode());
        data.put("ruleName", rule.getRuleName());
        data.put("scope", rule.getScope());
        data.put("actionType", rule.getActionType());
        data.put("conditionSpel", rule.getConditionSpel());
        data.put("matched", matched);
        if (matched) {
            data.put("wouldApply", Map.of(
                    "actionType", rule.getActionType(),
                    "actionConfig", rule.getActionConfigJson()));
        }
        return buildSimpleResult(
                matched
                        ? "规则 " + ruleCode + " 条件命中 — 会执行 " + rule.getActionType()
                        : "规则 " + ruleCode + " 条件未命中, 无动作",
                data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
