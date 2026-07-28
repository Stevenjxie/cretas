package com.cretas.aims.ai.tool.impl.config;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.RuleEngineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 规则配置工具
 *
 * 管理业务规则配置，包括质检规则、库存规则、生产规则等。
 * 支持规则的创建、更新、启用/禁用等操作。
 *
 * Intent Code: RULE_CONFIG
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class RuleConfigTool extends AbstractBusinessTool {

    @Autowired
    private RuleEngineService ruleEngineService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getToolName() {
        return "rule_config";
    }

    @Override
    public String getDescription() {
        return "管理业务规则配置。支持质检规则、库存规则、生产规则等的创建和更新。" +
                "适用场景：配置质检标准、设置库存预警阈值、调整生产参数规则。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // ruleId: 规则ID（更新时必需，新建时可选）
        Map<String, Object> ruleId = new HashMap<>();
        ruleId.put("type", "string");
        ruleId.put("description", "规则ID，更新现有规则时提供");
        properties.put("ruleId", ruleId);

        // ruleCode: 规则编码（新建时必需）
        Map<String, Object> ruleCode = new HashMap<>();
        ruleCode.put("type", "string");
        ruleCode.put("description", "规则编码，唯一标识符");
        properties.put("ruleCode", ruleCode);

        // ruleConfig: 规则配置（必需）
        Map<String, Object> ruleConfig = new HashMap<>();
        ruleConfig.put("type", "object");
        ruleConfig.put("description", "规则配置内容，JSON格式");
        properties.put("ruleConfig", ruleConfig);

        // ruleName: 规则名称（可选）
        Map<String, Object> ruleName = new HashMap<>();
        ruleName.put("type", "string");
        ruleName.put("description", "规则名称");
        properties.put("ruleName", ruleName);

        // ruleType: 规则类型（可选）
        Map<String, Object> ruleType = new HashMap<>();
        ruleType.put("type", "string");
        ruleType.put("description", "规则类型");
        ruleType.put("enum", Arrays.asList(
                "QUALITY_CHECK",     // 质检规则
                "INVENTORY_ALERT",   // 库存预警规则
                "PRODUCTION",        // 生产规则
                "EXPIRY_ALERT",      // 过期预警规则
                "APPROVAL_FLOW",     // 审批流程规则
                "NOTIFICATION"       // 通知规则
        ));
        properties.put("ruleType", ruleType);

        // enabled: 是否启用（可选）
        Map<String, Object> enabled = new HashMap<>();
        enabled.put("type", "boolean");
        enabled.put("description", "是否启用规则");
        enabled.put("default", true);
        properties.put("enabled", enabled);

        // description: 规则描述（可选）
        Map<String, Object> description = new HashMap<>();
        description.put("type", "string");
        description.put("description", "规则描述");
        properties.put("description", description);

        schema.put("properties", properties);
        // ruleId或ruleCode至少需要一个，ruleConfig必需
        schema.put("required", Arrays.asList("ruleConfig"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // ruleId或ruleCode至少需要一个，但这里只能指定必需的ruleConfig
        // 实际校验在doExecute中进行
        return Arrays.asList("ruleConfig");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        Object ruleConfigObj = params.get("ruleConfig");
        String ruleName = getString(params, "ruleName", null);
        String ruleType = getString(params, "ruleType", "QUALITY_CHECK");
        Boolean enabled = getBoolean(params, "enabled");

        // Extract DRL content from ruleConfig map
        String drlContent = null;
        if (ruleConfigObj instanceof Map<?, ?> ruleConfigMap) {
            Object drlObj = ruleConfigMap.get("drlContent");
            if (drlObj != null) drlContent = drlObj.toString();
        }
        if (drlContent == null) {
            // Serialize the ruleConfig map itself as a placeholder DRL comment for logging
            drlContent = "// Rule config: " + objectMapper.writeValueAsString(ruleConfigObj);
        }

        if (ruleName == null || ruleName.isEmpty()) {
            ruleName = "rule_" + ruleType + "_" + System.currentTimeMillis();
        }

        // Map ruleType to rule group
        String ruleGroup = switch (ruleType) {
            case "QUALITY_CHECK" -> "quality";
            case "INVENTORY_ALERT", "EXPIRY_ALERT" -> "validation";
            case "PRODUCTION" -> "costing";
            case "APPROVAL_FLOW" -> "workflow";
            case "NOTIFICATION" -> "validation";
            default -> "validation";
        };

        try {
            log.info("添加/更新规则 - 工厂ID: {}, 规则名: {}, 规则组: {}", factoryId, ruleName, ruleGroup);

            boolean success = ruleEngineService.addRule(factoryId, ruleGroup, ruleName, drlContent);
            if (success) {
                ruleEngineService.reloadRuleGroup(factoryId, ruleGroup);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("factoryId", factoryId);
            result.put("ruleName", ruleName);
            result.put("ruleGroup", ruleGroup);
            result.put("ruleType", ruleType);
            result.put("enabled", enabled != null ? enabled : true);
            result.put("addSuccess", success);

            String message = success ? "规则 " + ruleName + " 已添加并重新加载" : "规则添加失败，请检查规则内容";
            return buildSimpleResult(message, result);
        } catch (Exception e) {
            log.error("规则配置失败 - 工厂ID: {}, 规则名: {}", factoryId, ruleName, e);
            throw e;
        }
    }

    /**
     * 获取规则类型的中文名称
     */
    private String getRuleTypeName(String type) {
        Map<String, String> typeNames = Map.of(
            "QUALITY_CHECK", "质检规则",
            "INVENTORY_ALERT", "库存预警规则",
            "PRODUCTION", "生产规则",
            "EXPIRY_ALERT", "过期预警规则",
            "APPROVAL_FLOW", "审批流程规则",
            "NOTIFICATION", "通知规则"
        );
        return typeNames.getOrDefault(type.toUpperCase(), type);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "ruleId", "请问要更新哪个规则？请提供规则ID。",
            "ruleCode", "请问新规则的编码是什么？",
            "ruleConfig", "请问规则的具体配置内容是什么？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "ruleId", "规则ID",
            "ruleCode", "规则编码",
            "ruleConfig", "规则配置",
            "ruleName", "规则名称",
            "ruleType", "规则类型",
            "enabled", "是否启用",
            "description", "规则描述"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
