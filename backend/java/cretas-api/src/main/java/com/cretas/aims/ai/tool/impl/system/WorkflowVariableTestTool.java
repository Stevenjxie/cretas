package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.WorkflowVariableService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AIChat Tool: workflow_variable_test (C-WF-VAR-1).
 *
 * <p>用户场景: "测试一下 #order.amount > 10000 这个条件" → 用 sample context
 * 跑 sandbox SpEL, 返结果 / R5 友好错误 (变量不存在 / 语法错 / 禁止反射).
 *
 * <p>调用样例 (LLM 自然语言):
 * <pre>
 *   "测试条件: #own.role == 'factory_admin' 在 alice 这个用户身上是否成立"
 *   → params: { "spel": "#own.role == 'factory_admin'",
 *               "sampleContext": {"own": {"username":"alice", "role":"factory_admin"}} }
 * </pre>
 *
 * @since 2026-05-17
 */
@Slf4j
@Component
public class WorkflowVariableTestTool extends AbstractBusinessTool {

    @Autowired
    private WorkflowVariableService variableService;

    @Override
    public String getToolName() {
        return "workflow_variable_test";
    }

    @Override
    public String getDescription() {
        return "测试工作流 SpEL 表达式 — 用样例上下文 (own/order/customer/businessEntity) 跑 sandbox 求值, " +
                "返结果 + 友好错误 hint (变量不存在 / 语法错 / 禁止反射)。" +
                "适用场景: 调试审批工作流条件 / 验证表达式语法 / 检查变量名拼写。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> props = new HashMap<>();

        Map<String, Object> spel = new HashMap<>();
        spel.put("type", "string");
        spel.put("description",
                "SpEL 表达式, e.g. #order.amount > 10000 或 #own.role == 'admin'");
        props.put("spel", spel);

        Map<String, Object> sampleContext = new HashMap<>();
        sampleContext.put("type", "object");
        sampleContext.put("description",
                "样例上下文 map, 4 命名空间: own/order/customer/businessEntity. " +
                "e.g. {\"own\": {\"role\":\"admin\"}, \"order\": {\"amount\": 15000}}");
        props.put("sampleContext", sampleContext);

        schema.put("properties", props);
        schema.put("required", List.of("spel"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("spel");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String spel = getString(params, "spel");
        Object sampleCtxRaw = params.get("sampleContext");
        Map<String, Object> sampleCtx = sampleCtxRaw instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();

        log.info("workflow_variable_test - factoryId={}, spel={}, sampleKeys={}",
                factoryId, spel, sampleCtx.keySet());

        Map<String, Object> result = variableService.testExpression(factoryId, spel, sampleCtx);

        boolean success = Boolean.TRUE.equals(result.get("success"));
        String message;
        if (success) {
            Object val = result.get("result");
            message = "表达式求值成功 — 结果: " + (val == null ? "null" : val.toString())
                    + " (类型: " + result.get("resultType") + ")";
        } else {
            message = "表达式求值失败 — " + result.get("errorMessage")
                    + " | 建议: " + result.get("actionHint");
        }
        return buildSimpleResult(message, result);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
