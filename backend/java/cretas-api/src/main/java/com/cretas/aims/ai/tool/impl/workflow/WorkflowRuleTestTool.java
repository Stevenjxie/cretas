package com.cretas.aims.ai.tool.impl.workflow;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.WorkflowRule;
import com.cretas.aims.service.workflow.WorkflowRuleEvaluator;
import com.cretas.aims.service.workflow.WorkflowRuleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AIChat tool — 测试 {@link WorkflowRule} 求值结果.
 *
 * <p>Sprint 4 Wave 1 (C-WF-RULE-1) — 输入 ruleId + mock context,
 * 返回 evaluator 结果. 用户友好层供 LLM 调用诊断流转规则.
 *
 * <p>Intent binding 在 Flyway V20260606_01__workflow_rules.sql 末尾 INSERT.
 *
 * @since 2026-05-16
 */
@Slf4j
@Component
public class WorkflowRuleTestTool extends AbstractBusinessTool {

    @Autowired
    private WorkflowRuleService ruleService;

    @Autowired
    private WorkflowRuleEvaluator ruleEvaluator;

    @Override
    public String getToolName() {
        return "workflow_rule_test";
    }

    @Override
    public String getDescription() {
        return "测试 ApprovalWorkflow 流转规则: 输入 ruleId + mock context, 返回 evaluator 结果 (true/false)."
                + " 适用场景: 诊断为什么某条规则在生产数据上表现异常 / 验证新规则的逻辑是否符合预期.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> ruleId = new HashMap<>();
        ruleId.put("type", "string");
        ruleId.put("description", "WorkflowRule.id (UUID)");
        properties.put("ruleId", ruleId);

        Map<String, Object> mockContext = new HashMap<>();
        mockContext.put("type", "object");
        mockContext.put("description", "mock context 字段 (e.g. amount=20000, department='finance', role='FACTORY_SUPER_ADMIN')");
        properties.put("mockContext", mockContext);

        schema.put("properties", properties);
        schema.put("required", List.of("ruleId", "mockContext"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("ruleId", "mockContext");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "ruleId" -> "请提供 WorkflowRule.id (可在审批工作流编辑器 → condition 节点 → 管理规则 中复制).";
            case "mockContext" -> "请提供 mock context, 例如: {\"amount\": 20000, \"department\": \"finance\"}";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String ruleId = getString(params, "ruleId");
        @SuppressWarnings("unchecked")
        Map<String, Object> mockContext = (Map<String, Object>) params.get("mockContext");
        if (mockContext == null) mockContext = Map.of();

        log.info("workflow_rule_test - factoryId={}, ruleId={}, ctx={}", factoryId, ruleId, mockContext);

        WorkflowRule rule = ruleService.getRequired(factoryId, ruleId);
        boolean result = ruleEvaluator.evaluate(rule, mockContext);

        Map<String, Object> data = new HashMap<>();
        data.put("ruleId", rule.getId());
        data.put("ruleType", rule.getRuleType().name());
        data.put("expression", rule.getExpression());
        data.put("priority", rule.getPriority());
        data.put("enabled", rule.getEnabled());
        data.put("trueTargetNodeId", rule.getTrueTargetNodeId());
        data.put("falseTargetNodeId", rule.getFalseTargetNodeId());
        data.put("result", result);
        data.put("mockContext", mockContext);
        data.put("interpretation", result
                ? "规则命中, 流转会走 trueTargetNodeId=" + rule.getTrueTargetNodeId()
                : (rule.getFalseTargetNodeId() != null
                    ? "规则未命中, 流转会走 falseTargetNodeId=" + rule.getFalseTargetNodeId()
                    : "规则未命中且无 falseTargetNodeId, 流转会继续评估下一规则 (按 priority asc) 或 fall through 到 edge.condition"));

        return buildSimpleResult(result ? "规则命中" : "规则未命中", data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
