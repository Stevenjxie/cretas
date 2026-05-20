package com.cretas.aims.service.impl;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.entity.smartbi.AiAgentRule;
import com.cretas.aims.event.SopUploadedEvent;
import com.cretas.aims.repository.smartbi.AiAgentRuleRepository;
import com.cretas.aims.service.SopAgentOrchestrator;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator.SpelEvaluationFailure;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SOP Agent 编排服务实现
 *
 * <p>负责编排和执行 SOP 相关的 AI Agent 工作流。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-21
 */
@Slf4j
@Service
public class SopAgentOrchestratorImpl implements SopAgentOrchestrator {

    @Autowired
    @Lazy
    private AiAgentRuleRepository ruleRepository;

    @Autowired
    @Lazy
    private ToolRegistry toolRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SandboxedSpelEvaluator spelEvaluator;

    /**
     * 监听 SOP 上传事件
     */
    @Override
    @EventListener
    @Async
    public void handleSopUpload(SopUploadedEvent event) {
        log.info("收到 SOP 上传事件: {}", event);

        String factoryId = event.getFactoryId();
        Map<String, Object> context = event.toContext();

        try {
            // 查找匹配的规则
            List<AiAgentRule> rules = ruleRepository.findByFactoryIdAndTriggerTypeAndIsActiveTrue(
                    factoryId, AiAgentRule.TRIGGER_SOP_UPLOAD);

            if (rules.isEmpty()) {
                log.info("未找到匹配的 SOP_UPLOAD 规则，尝试使用默认流程");
                // 使用默认流程
                executeDefaultSopFlow(event);
                return;
            }

            for (AiAgentRule rule : rules) {
                // 评估条件表达式
                if (!evaluateCondition(rule.getConditionExpression(), context)) {
                    log.debug("规则条件不满足，跳过: ruleId={}, ruleName={}", rule.getId(), rule.getRuleName());
                    continue;
                }

                log.info("执行规则: ruleId={}, ruleName={}", rule.getId(), rule.getRuleName());

                // 执行工具链
                try {
                    Map<String, Object> result = executeToolChainFromConfig(rule.getToolChainConfig(), context);
                    log.info("规则执行完成: ruleId={}, result={}", rule.getId(), result);
                    break; // 成功执行一个规则后退出
                } catch (Exception e) {
                    log.error("规则执行失败: ruleId={}, error={}", rule.getId(), e.getMessage(), e);
                    throw e; // 失败时直接抛出异常
                }
            }
        } catch (Exception e) {
            log.error("处理 SOP 上传事件失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> executeToolChain(String factoryId, String triggerType, Map<String, Object> context) {
        // 查找匹配的规则
        List<AiAgentRule> rules = ruleRepository.findByFactoryIdAndTriggerTypeAndIsActiveTrue(
                factoryId, triggerType);

        if (rules.isEmpty()) {
            return Map.of("success", false, "message", "未找到匹配的规则");
        }

        for (AiAgentRule rule : rules) {
            if (evaluateCondition(rule.getConditionExpression(), context)) {
                return executeToolChainFromConfig(rule.getToolChainConfig(), context);
            }
        }

        return Map.of("success", false, "message", "没有满足条件的规则");
    }

    @Override
    public Map<String, Object> executeRuleToolChain(String ruleId, Map<String, Object> context) {
        Optional<AiAgentRule> ruleOpt = ruleRepository.findById(ruleId);
        if (ruleOpt.isEmpty()) {
            return Map.of("success", false, "message", "规则不存在: " + ruleId);
        }

        AiAgentRule rule = ruleOpt.get();
        return executeToolChainFromConfig(rule.getToolChainConfig(), context);
    }

    @Override
    public Map<String, Object> analyzeSopAndUpdateComplexity(String factoryId, String fileUrl, String skuCode) {
        Map<String, Object> context = new HashMap<>();
        context.put("factoryId", factoryId);
        context.put("fileUrl", fileUrl);
        context.put("skuCode", skuCode);
        context.put("userId", 0L); // 系统调用

        // 构建默认工具链配置
        String toolChainConfig = """
            {
                "tools": [
                    {
                        "toolName": "sop_parse_document",
                        "paramsMapping": {"fileUrl": "#{fileUrl}"},
                        "outputKey": "parseResult"
                    },
                    {
                        "toolName": "sop_analyze_complexity",
                        "paramsMapping": {
                            "sopContent": "#{parseResult.data.content}",
                            "steps": "#{parseResult.data.steps}"
                        },
                        "outputKey": "complexityResult"
                    },
                    {
                        "toolName": "sku_update_complexity",
                        "paramsMapping": {
                            "skuCode": "#{skuCode}",
                            "complexity": "#{complexityResult.data.level}",
                            "reason": "#{complexityResult.data.reason}",
                            "stepCount": "#{complexityResult.data.stepCount}",
                            "skillRequired": "#{complexityResult.data.skillRequired}",
                            "qualityCheckCount": "#{complexityResult.data.qualityCheckCount}",
                            "specialEquipment": "#{complexityResult.data.specialEquipment}",
                            "estimatedMinutes": "#{complexityResult.data.estimatedMinutes}"
                        },
                        "outputKey": "updateResult"
                    }
                ]
            }
            """;

        return executeToolChainFromConfig(toolChainConfig, context);
    }

    /**
     * 执行默认 SOP 流程
     */
    private void executeDefaultSopFlow(SopUploadedEvent event) {
        if (event.getSkuCode() == null || event.getSkuCode().isEmpty()) {
            log.info("事件中没有 skuCode，跳过默认流程");
            return;
        }

        Map<String, Object> result = analyzeSopAndUpdateComplexity(
                event.getFactoryId(),
                event.getFileUrl(),
                event.getSkuCode()
        );

        log.info("默认 SOP 流程执行完成: {}", result);
    }

    /**
     * 评估条件表达式 — Defense-in-depth (B-A7 P0 follow-up to PR #48/#50).
     *
     * <p>Before the fix this method used raw {@link org.springframework.expression.spel.support.StandardEvaluationContext}
     * — that context grants full reflection / Type / static-method / constructor access,
     * so any rule with a malicious {@code conditionExpression} (e.g.
     * {@code T(java.lang.Runtime).getRuntime().exec("...")}) RCE'd on every SOP-upload event.
     * The controller now validates at write time, but data persisted BEFORE the controller
     * fix still needs runtime protection — and untrusted DB content (per zero-trust) should
     * not be evaluated in StandardEvaluationContext anyway.
     *
     * <p>Now delegates to {@link SandboxedSpelEvaluator} which uses
     * {@code SimpleEvaluationContext.forReadOnlyDataBinding()} — sandbox blocks
     * {@code T()}, {@code new}, reflection, assignment, {@code @bean}, {@code #root}.
     */
    private boolean evaluateCondition(String expression, Map<String, Object> context) {
        if (expression == null || expression.trim().isEmpty()) {
            return true; // 无条件则默认满足
        }

        // Mirror previous behaviour: strip wrapping #{...} if present.
        String spelExpression = expression;
        if (spelExpression.startsWith("#{") && spelExpression.endsWith("}")) {
            spelExpression = spelExpression.substring(2, spelExpression.length() - 1);
        }

        try {
            return spelEvaluator.evaluateBoolean(spelExpression, context);
        } catch (SpelEvaluationFailure ex) {
            // Pre-fix DB content with T() / new / reflection — sandbox rejects, do NOT
            // break the event loop. Log + return false so the rule simply doesn't match.
            log.warn("条件表达式评估被沙箱拒绝 (可能含安全风险): expression={}, reason={}",
                    expression, ex.getUserMessage());
            return false;
        } catch (Exception e) {
            log.warn("条件表达式评估失败: expression={}, error={}", expression, e.getMessage());
            return false;
        }
    }

    /**
     * 从配置执行工具链
     */
    private Map<String, Object> executeToolChainFromConfig(String configJson, Map<String, Object> context) {
        try {
            ToolChainConfig config = objectMapper.readValue(configJson, ToolChainConfig.class);
            return executeToolChainInternal(config, context);
        } catch (Exception e) {
            log.error("解析工具链配置失败: {}", e.getMessage(), e);
            return Map.of("success", false, "error", "工具链配置解析失败: " + e.getMessage());
        }
    }

    /**
     * 执行工具链
     */
    private Map<String, Object> executeToolChainInternal(ToolChainConfig config, Map<String, Object> context) {
        Map<String, Object> outputs = new HashMap<>();
        Map<String, Object> lastResult = null;

        for (ToolStep step : config.getTools()) {
            String toolName = step.getToolName();
            log.info("执行工具: toolName={}", toolName);

            // 获取工具执行器
            Optional<ToolExecutor> executorOpt = toolRegistry.getExecutor(toolName);
            if (executorOpt.isEmpty()) {
                log.error("工具不存在: {}", toolName);
                return Map.of("success", false, "error", "工具不存在: " + toolName);
            }

            ToolExecutor executor = executorOpt.get();

            // 解析参数
            Map<String, Object> params = resolveParams(step.getParamsMapping(), context, outputs);

            // 构建 ToolCall
            ToolCall toolCall = buildToolCall(toolName, params);

            try {
                // 执行工具
                String resultJson = executor.execute(toolCall, context);

                // 解析结果
                Map<String, Object> result = objectMapper.readValue(resultJson,
                        new TypeReference<Map<String, Object>>() {});

                // 保存输出
                if (step.getOutputKey() != null) {
                    outputs.put(step.getOutputKey(), result);
                }

                lastResult = result;

                // 检查是否成功
                if (!Boolean.TRUE.equals(result.get("success"))) {
                    log.warn("工具执行返回失败: toolName={}, result={}", toolName, result);
                }

                log.info("工具执行完成: toolName={}, outputKey={}", toolName, step.getOutputKey());

            } catch (Exception e) {
                log.error("工具执行异常: toolName={}, error={}", toolName, e.getMessage(), e);
                return Map.of(
                        "success", false,
                        "error", "工具执行失败: " + toolName + " - " + e.getMessage(),
                        "failedStep", toolName
                );
            }
        }

        // 返回最后一个工具的结果
        Map<String, Object> finalResult = new HashMap<>();
        finalResult.put("success", true);
        finalResult.put("outputs", outputs);
        if (lastResult != null) {
            finalResult.put("lastResult", lastResult);
        }

        return finalResult;
    }

    /**
     * 解析参数映射
     */
    private Map<String, Object> resolveParams(Map<String, String> paramsMapping,
                                               Map<String, Object> context,
                                               Map<String, Object> outputs) {
        Map<String, Object> params = new HashMap<>();

        if (paramsMapping == null) {
            return params;
        }

        for (Map.Entry<String, String> entry : paramsMapping.entrySet()) {
            String paramName = entry.getKey();
            String expression = entry.getValue();

            Object value = resolveExpression(expression, context, outputs);
            if (value != null) {
                params.put(paramName, value);
            }
        }

        return params;
    }

    /**
     * 解析表达式
     */
    private Object resolveExpression(String expression, Map<String, Object> context, Map<String, Object> outputs) {
        if (expression == null) {
            return null;
        }

        // 处理 #{xxx} 格式的表达式
        Pattern pattern = Pattern.compile("#\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(expression);

        if (matcher.matches()) {
            String path = matcher.group(1);
            return resolvePath(path, context, outputs);
        } else if (expression.startsWith("#{") && expression.endsWith("}")) {
            String path = expression.substring(2, expression.length() - 1);
            return resolvePath(path, context, outputs);
        }

        // 直接返回字面值
        return expression;
    }

    /**
     * 解析路径表达式
     */
    @SuppressWarnings("unchecked")
    private Object resolvePath(String path, Map<String, Object> context, Map<String, Object> outputs) {
        String[] parts = path.split("\\.");

        // 首先在 context 中查找
        Object current = context.get(parts[0]);

        // 如果没找到，在 outputs 中查找
        if (current == null) {
            current = outputs.get(parts[0]);
        }

        if (current == null) {
            return null;
        }

        // 遍历路径
        for (int i = 1; i < parts.length; i++) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(parts[i]);
            } else {
                return null;
            }

            if (current == null) {
                return null;
            }
        }

        return current;
    }

    /**
     * 构建 ToolCall 对象
     */
    private ToolCall buildToolCall(String toolName, Map<String, Object> params) {
        ToolCall toolCall = new ToolCall();
        toolCall.setId(UUID.randomUUID().toString());
        toolCall.setType("function");

        ToolCall.FunctionCall function = new ToolCall.FunctionCall();
        function.setName(toolName);

        try {
            function.setArguments(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            function.setArguments("{}");
        }

        toolCall.setFunction(function);
        return toolCall;
    }

    /**
     * 工具链配置内部类
     */
    private static class ToolChainConfig {
        private List<ToolStep> tools;

        public List<ToolStep> getTools() {
            return tools != null ? tools : new ArrayList<>();
        }

        public void setTools(List<ToolStep> tools) {
            this.tools = tools;
        }
    }

    /**
     * 工具步骤内部类
     */
    private static class ToolStep {
        private String toolName;
        private Map<String, String> paramsMapping;
        private String outputKey;

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public Map<String, String> getParamsMapping() { return paramsMapping; }
        public void setParamsMapping(Map<String, String> paramsMapping) { this.paramsMapping = paramsMapping; }
        public String getOutputKey() { return outputKey; }
        public void setOutputKey(String outputKey) { this.outputKey = outputKey; }
    }
}
