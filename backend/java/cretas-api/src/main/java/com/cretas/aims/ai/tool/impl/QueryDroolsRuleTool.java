package com.cretas.aims.ai.tool.impl;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 查询Drools验证规则工具
 *
 * 查询现有的Drools规则文件，提取与指定实体类型和操作类型相关的验证规则。
 * 帮助LLM理解现有的验证逻辑，避免创建重复或冲突的规则。
 *
 * 适用场景：
 * - 创建新意图前了解现有验证规则
 * - 分析某个实体类型的验证逻辑
 * - 排查验证失败原因
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Slf4j
@Component
public class QueryDroolsRuleTool extends AbstractTool {

    /**
     * Drools规则文件路径
     */
    private static final String RULES_PATH = "classpath:rules/*.drl";

    /**
     * 规则名称正则
     */
    private static final Pattern RULE_NAME_PATTERN = Pattern.compile("rule\\s+\"([^\"]+)\"");

    /**
     * Salience正则
     */
    private static final Pattern SALIENCE_PATTERN = Pattern.compile("salience\\s+(\\d+)");

    @Override
    public String getToolName() {
        return "query_drools_rules";
    }

    @Override
    public String getDescription() {
        return "查询现有的Drools验证规则，提取与指定实体类型和操作类型相关的规则。" +
                "返回规则名称、优先级、条件、动作等信息。" +
                "适用场景：创建新意图前了解现有验证规则、分析验证逻辑、排查验证失败原因。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // entityType: 实体类型（可选，不提供则返回所有规则）
        Map<String, Object> entityType = new HashMap<>();
        entityType.put("type", "string");
        entityType.put("description", "实体类型，用于过滤相关规则。如：MaterialBatch、ProcessingBatch等");
        properties.put("entityType", entityType);

        // operationType: 操作类型（可选）
        Map<String, Object> operationType = new HashMap<>();
        operationType.put("type", "string");
        operationType.put("description", "操作类型，用于过滤相关规则");
        operationType.put("enum", Arrays.asList("QUERY", "CREATE", "UPDATE", "DELETE", "BATCH_UPDATE", "BATCH_DELETE"));
        properties.put("operationType", operationType);

        // ruleFile: 规则文件（可选）
        Map<String, Object> ruleFile = new HashMap<>();
        ruleFile.put("type", "string");
        ruleFile.put("description", "指定规则文件名，如：intent-validation.drl、field-validation.drl等");
        properties.put("ruleFile", ruleFile);

        schema.put("properties", properties);
        // 所有参数都是可选的
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
        logExecutionStart(toolCall, context);

        try {
            // 1. 解析参数
            Map<String, Object> arguments = parseArguments(toolCall);
            String entityType = getOptionalParam(arguments, "entityType", null);
            String operationType = getOptionalParam(arguments, "operationType", null);
            String ruleFile = getOptionalParam(arguments, "ruleFile", null);

            // 2. 加载规则文件
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(RULES_PATH);

            if (resources.length == 0) {
                return buildErrorResult("未找到Drools规则文件");
            }

            // 3. 解析规则文件
            List<Map<String, Object>> allRules = new ArrayList<>();
            for (Resource resource : resources) {
                String fileName = resource.getFilename();

                // 如果指定了规则文件，只处理该文件
                if (ruleFile != null && !fileName.equals(ruleFile)) {
                    continue;
                }

                List<Map<String, Object>> fileRules = parseRuleFile(resource, fileName);
                allRules.addAll(fileRules);
            }

            // 4. 过滤规则
            List<Map<String, Object>> filteredRules = filterRules(allRules, entityType, operationType);

            // 5. 按优先级排序
            filteredRules.sort((r1, r2) -> {
                Integer p1 = (Integer) r1.getOrDefault("salience", 0);
                Integer p2 = (Integer) r2.getOrDefault("salience", 0);
                return p2.compareTo(p1); // 降序
            });

            // 6. 构建返回结果
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("totalRules", allRules.size());
            resultData.put("filteredRules", filteredRules.size());
            resultData.put("rules", filteredRules);

            // 添加统计信息
            Map<String, Long> rulesByFile = filteredRules.stream()
                    .collect(Collectors.groupingBy(r -> (String) r.get("file"), Collectors.counting()));
            resultData.put("rulesByFile", rulesByFile);

            // 添加过滤条件说明
            Map<String, String> filters = new HashMap<>();
            if (entityType != null) filters.put("entityType", entityType);
            if (operationType != null) filters.put("operationType", operationType);
            if (ruleFile != null) filters.put("ruleFile", ruleFile);
            resultData.put("appliedFilters", filters);

            String result = buildSuccessResult(resultData);
            logExecutionSuccess(toolCall, result);

            return result;

        } catch (IllegalArgumentException e) {
            log.warn("⚠️  参数验证失败: {}", e.getMessage());
            return buildErrorResult("参数验证失败: " + e.getMessage());

        } catch (Exception e) {
            logExecutionFailure(toolCall, e);
            return buildErrorResult("查询Drools规则失败: " + e.getMessage());
        }
    }

    /**
     * 解析规则文件
     */
    private List<Map<String, Object>> parseRuleFile(Resource resource, String fileName) {
        List<Map<String, Object>> rules = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
            String line;
            StringBuilder ruleContent = new StringBuilder();
            boolean inRule = false;
            String currentRuleName = null;
            int braceLevel = 0;

            while ((line = reader.readLine()) != null) {
                String trimmedLine = line.trim();

                // 跳过注释和空行
                if (trimmedLine.startsWith("//") || trimmedLine.startsWith("/*") ||
                        trimmedLine.startsWith("*") || trimmedLine.isEmpty()) {
                    continue;
                }

                // 检测规则开始
                Matcher ruleNameMatcher = RULE_NAME_PATTERN.matcher(trimmedLine);
                if (ruleNameMatcher.find()) {
                    currentRuleName = ruleNameMatcher.group(1);
                    inRule = true;
                    ruleContent = new StringBuilder();
                    braceLevel = 0;
                }

                if (inRule) {
                    ruleContent.append(line).append("\n");

                    // 统计花括号层级
                    for (char c : line.toCharArray()) {
                        if (c == '{') braceLevel++;
                        if (c == '}') braceLevel--;
                    }

                    // 规则结束（when-then块闭合 + end标记）
                    if (trimmedLine.equals("end") && braceLevel == 0) {
                        Map<String, Object> rule = parseRule(currentRuleName, ruleContent.toString(), fileName);
                        rules.add(rule);
                        inRule = false;
                    }
                }
            }

        } catch (Exception e) {
            log.error("❌ 解析规则文件失败: {}", fileName, e);
        }

        return rules;
    }

    /**
     * 解析单个规则
     */
    private Map<String, Object> parseRule(String ruleName, String ruleContent, String fileName) {
        Map<String, Object> rule = new HashMap<>();
        rule.put("ruleName", ruleName);
        rule.put("file", fileName);

        // 提取salience
        Matcher salienceMatcher = SALIENCE_PATTERN.matcher(ruleContent);
        if (salienceMatcher.find()) {
            rule.put("salience", Integer.parseInt(salienceMatcher.group(1)));
        } else {
            rule.put("salience", 0);
        }

        // 提取when条件
        int whenIndex = ruleContent.indexOf("when");
        int thenIndex = ruleContent.indexOf("then");
        if (whenIndex != -1 && thenIndex != -1) {
            String whenBlock = ruleContent.substring(whenIndex + 4, thenIndex).trim();
            rule.put("condition", whenBlock);
        } else {
            rule.put("condition", "");
        }

        // 提取then动作
        if (thenIndex != -1) {
            int endIndex = ruleContent.lastIndexOf("end");
            if (endIndex != -1) {
                String thenBlock = ruleContent.substring(thenIndex + 4, endIndex).trim();
                rule.put("action", thenBlock);
            } else {
                rule.put("action", "");
            }
        } else {
            rule.put("action", "");
        }

        // 提取规则描述（注释）
        String description = extractDescription(ruleContent);
        rule.put("description", description);

        // 分析规则关注的操作类型
        List<String> operationTypes = extractOperationTypes(ruleContent);
        rule.put("operationTypes", operationTypes);

        return rule;
    }

    /**
     * 提取规则描述
     */
    private String extractDescription(String ruleContent) {
        // 查找规则名称前的注释
        String[] lines = ruleContent.split("\n");
        StringBuilder description = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//")) {
                description.append(trimmed.substring(2).trim()).append(" ");
            } else if (trimmed.startsWith("rule")) {
                break;
            }
        }

        return description.toString().trim();
    }

    /**
     * 提取规则关注的操作类型
     */
    private List<String> extractOperationTypes(String ruleContent) {
        List<String> operations = new ArrayList<>();
        String[] possibleOps = {"QUERY", "CREATE", "UPDATE", "DELETE", "BATCH_UPDATE", "BATCH_DELETE", "VIEW"};

        for (String op : possibleOps) {
            if (ruleContent.contains("operation == \"" + op + "\"") ||
                    ruleContent.contains("operation == '" + op + "'")) {
                operations.add(op);
            }
        }

        return operations;
    }

    /**
     * 过滤规则
     */
    private List<Map<String, Object>> filterRules(
            List<Map<String, Object>> allRules,
            String entityType,
            String operationType
    ) {
        return allRules.stream()
                .filter(rule -> {
                    // 如果指定了操作类型，检查规则是否匹配
                    if (operationType != null) {
                        @SuppressWarnings("unchecked")
                        List<String> ruleOps = (List<String>) rule.get("operationTypes");
                        if (!ruleOps.isEmpty() && !ruleOps.contains(operationType)) {
                            return false;
                        }
                    }

                    // 如果指定了实体类型，检查规则是否相关
                    if (entityType != null) {
                        String condition = (String) rule.getOrDefault("condition", "");
                        String action = (String) rule.getOrDefault("action", "");
                        String content = condition + " " + action;

                        // 简单的关键词匹配
                        boolean relevant = content.contains(entityType) ||
                                content.toLowerCase().contains(entityType.toLowerCase());

                        if (!relevant) {
                            return false;
                        }
                    }

                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * 此工具不需要特殊权限
     */
    @Override
    public boolean requiresPermission() {
        return false;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
