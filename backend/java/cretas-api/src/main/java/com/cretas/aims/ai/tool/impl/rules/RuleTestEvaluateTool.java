package com.cretas.aims.ai.tool.impl.rules;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: dry-run a Canvas-Rules BusinessRule against sample input.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * Delegates to RuleEngine.preview (no side effects, no log row, no real mutation).
 * Used by Canvas UI "测试评估" button + LLM "如果客户 X 下单 5 万会触发哪些规则?" Q&A.
 *
 * ⚠️ SKELETON: doExecute throws UnsupportedOperationException. Sister chat to wire to
 * RuleEngine.preview.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class RuleTestEvaluateTool extends AbstractBusinessTool {

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
                "description", "样本 input 对象, 作为 SpEL 评估的 root context"));

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

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "RuleTestEvaluateTool.doExecute not yet implemented (Phase 4a skeleton). "
                + "Sister chat: lookup rule, call ruleEngine.preview, return match flag + action preview.");
    }
}
