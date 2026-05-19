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
 * AI Tool: soft-delete a Canvas-Rules BusinessRule.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * <p>Uses BaseEntity soft delete — BusinessRule is annotated with
 * {@code @SQLDelete(sql = "UPDATE business_rules SET deleted_at = NOW() WHERE id = ?")}
 * and {@code @Where(clause = "deleted_at IS NULL")} so the rule disappears from
 * RuleEngine evaluation and can be restored via DB ops.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class RuleDeleteTool extends AbstractBusinessTool {

    @Autowired
    private BusinessRuleRepository ruleRepository;

    @Override
    public String getToolName() {
        return "rule_delete";
    }

    @Override
    public String getDescription() {
        return "删除一条业务规则 (软删除)。例: '删除 vip_discount 规则'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("ruleCode", Map.of("type", "string", "description", "要删除的规则代码"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("ruleCode"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("ruleCode");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String ruleCode = getString(params, "ruleCode");
        BusinessRule rule = ruleRepository.findByFactoryIdAndRuleCode(factoryId, ruleCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "规则 " + ruleCode + " 不存在 (factoryId=" + factoryId + ")"));

        ruleRepository.delete(rule);  // BaseEntity @SQLDelete → soft delete
        log.info("rule_delete - factory={}, ruleCode={}, id={}",
                factoryId, ruleCode, rule.getId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(rule.getId()));
        data.put("ruleCode", ruleCode);
        data.put("ruleName", rule.getRuleName());
        data.put("deletedAt", java.time.LocalDateTime.now().toString());
        return buildSimpleResult("规则 " + ruleCode + " 已删除 (软删除, 可恢复)", data);
    }
}
