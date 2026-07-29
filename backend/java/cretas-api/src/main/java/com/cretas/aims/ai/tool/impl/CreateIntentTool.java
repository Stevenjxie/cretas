package com.cretas.aims.ai.tool.impl;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractTool;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.AIIntentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 创建意图配置工具
 *
 * 当用户请求无法匹配现有意图时，LLM 可调用此工具自动创建新的意图配置。
 * 创建的意图默认为 inactive 状态，需要人工审核激活。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Slf4j
@Component
public class CreateIntentTool extends AbstractTool {

    @Autowired
    @Lazy
    private AIIntentService aiIntentService;

    @Override
    public String getToolName() {
        return "create_new_intent";
    }

    @Override
    public String getDescription() {
        return "当用户请求无法匹配现有意图时，创建新的意图配置。" +
                "新意图将处于待审核状态，需要管理员激活后才能使用。" +
                "适用场景：用户提出新的查询需求、数据操作需求或表单生成需求。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        // 定义属性
        Map<String, Object> properties = new HashMap<>();

        // intentCode: 意图代码（必需）
        Map<String, Object> intentCode = new HashMap<>();
        intentCode.put("type", "string");
        intentCode.put("description", "意图唯一代码，建议格式：[类别]_[动作]_[对象]，如 QUERY_MATERIAL_BATCH");
        intentCode.put("pattern", "^[A-Z_]+$");
        properties.put("intentCode", intentCode);

        // intentName: 意图名称（必需）
        Map<String, Object> intentName = new HashMap<>();
        intentName.put("type", "string");
        intentName.put("description", "意图的中文名称，简洁明了，如：查询原料批次");
        intentName.put("maxLength", 50);
        properties.put("intentName", intentName);

        // category: 意图类别（必需）
        Map<String, Object> category = new HashMap<>();
        category.put("type", "string");
        category.put("description", "意图类别，决定路由到哪个Handler");
        category.put("enum", Arrays.asList(
                "QUERY",        // 查询类
                "DATA_OP",      // 数据操作类
                "FORM",         // 表单生成类
                "REPORT",       // 报表类
                "MATERIAL",     // 原料管理类
                "PRODUCTION",   // 生产管理类
                "QUALITY",      // 质量管理类
                "SHIPMENT",     // 出货管理类
                "EQUIPMENT",    // 设备管理类
                "ATTENDANCE",   // 考勤管理类
                "SYSTEM"        // 系统管理类
        ));
        properties.put("category", category);

        // keywords: 关键词列表（必需）
        Map<String, Object> keywords = new HashMap<>();
        keywords.put("type", "array");
        keywords.put("description", "触发此意图的关键词列表，建议3-5个");
        Map<String, Object> keywordItems = new HashMap<>();
        keywordItems.put("type", "string");
        keywords.put("items", keywordItems);
        keywords.put("minItems", 2);
        keywords.put("maxItems", 10);
        properties.put("keywords", keywords);

        // description: 意图描述（可选）
        Map<String, Object> description = new HashMap<>();
        description.put("type", "string");
        description.put("description", "详细描述此意图的用途和触发场景");
        description.put("maxLength", 500);
        properties.put("description", description);

        // semanticDomain: 语义域（可选）
        Map<String, Object> semanticDomain = new HashMap<>();
        semanticDomain.put("type", "string");
        semanticDomain.put("description", "语义分类 - 领域，如：物料、生产、质量、设备");
        properties.put("semanticDomain", semanticDomain);

        // semanticAction: 语义动作（可选）
        Map<String, Object> semanticAction = new HashMap<>();
        semanticAction.put("type", "string");
        semanticAction.put("description", "语义分类 - 动作，如：查询、创建、更新、删除");
        properties.put("semanticAction", semanticAction);

        // semanticObject: 语义对象（可选）
        Map<String, Object> semanticObject = new HashMap<>();
        semanticObject.put("type", "string");
        semanticObject.put("description", "语义分类 - 对象，如：批次、计划、记录");
        properties.put("semanticObject", semanticObject);

        // sensitivityLevel: 敏感级别（可选）
        Map<String, Object> sensitivityLevel = new HashMap<>();
        sensitivityLevel.put("type", "string");
        sensitivityLevel.put("description", "数据敏感级别，决定权限要求");
        sensitivityLevel.put("enum", Arrays.asList("LOW", "MEDIUM", "HIGH", "CRITICAL"));
        sensitivityLevel.put("default", "MEDIUM");
        properties.put("sensitivityLevel", sensitivityLevel);

        schema.put("properties", properties);

        // 必需字段
        schema.put("required", Arrays.asList("intentCode", "intentName", "category", "keywords"));

        return schema;
    }

    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
        logExecutionStart(toolCall, context);
        validateContext(context);

        try {
            // 1. 解析参数
            Map<String, Object> arguments = parseArguments(toolCall);

            String intentCode = getRequiredParam(arguments, "intentCode");
            String intentName = getRequiredParam(arguments, "intentName");
            String category = getRequiredParam(arguments, "category");
            @SuppressWarnings("unchecked")
            List<String> keywords = (List<String>) arguments.get("keywords");

            // 验证参数
            if (keywords == null || keywords.isEmpty()) {
                return buildErrorResult("keywords参数不能为空");
            }

            // 2. 构建 AIIntentConfig 实体
            AIIntentConfig intentConfig = new AIIntentConfig();
            intentConfig.setIntentCode(intentCode);
            intentConfig.setIntentName(intentName);
            intentConfig.setIntentCategory(category);

            // 关键词转为 JSON 数组字符串
            try {
                String keywordsJson = objectMapper.writeValueAsString(keywords);
                intentConfig.setKeywords(keywordsJson);
            } catch (Exception e) {
                return buildErrorResult("关键词序列化失败: " + e.getMessage());
            }

            // 可选字段
            if (arguments.containsKey("description")) {
                intentConfig.setDescription(getOptionalParam(arguments, "description", null));
            }
            if (arguments.containsKey("semanticDomain")) {
                intentConfig.setSemanticDomain(getOptionalParam(arguments, "semanticDomain", null));
            }
            if (arguments.containsKey("semanticAction")) {
                intentConfig.setSemanticAction(getOptionalParam(arguments, "semanticAction", null));
            }
            if (arguments.containsKey("semanticObject")) {
                intentConfig.setSemanticObject(getOptionalParam(arguments, "semanticObject", null));
            }
            if (arguments.containsKey("sensitivityLevel")) {
                intentConfig.setSensitivityLevel(getOptionalParam(arguments, "sensitivityLevel", "MEDIUM"));
            } else {
                intentConfig.setSensitivityLevel("MEDIUM");
            }

            // 设置工厂ID（多租户隔离）
            String factoryId = getFactoryId(context);
            intentConfig.setFactoryId(factoryId);

            // 设置为待审核状态（inactive）
            intentConfig.setIsActive(false);

            // 设置配额和缓存（默认值）
            intentConfig.setQuotaCost(1);
            intentConfig.setCacheTtlMinutes(30);
            intentConfig.setConfigVersion(1);

            // 3. 调用服务创建意图
            AIIntentConfig createdIntent = aiIntentService.createIntent(intentConfig);

            // 4. 构建返回结果
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("intentCode", createdIntent.getIntentCode());
            resultData.put("intentName", createdIntent.getIntentName());
            resultData.put("category", createdIntent.getIntentCategory());
            resultData.put("active", createdIntent.getIsActive());
            resultData.put("message", "意图配置已创建,状态为待审核。管理员激活后即可使用。");

            String result = buildSuccessResult(resultData);
            logExecutionSuccess(toolCall, result);

            return result;

        } catch (IllegalArgumentException e) {
            log.warn("⚠️  参数验证失败: {}", e.getMessage());
            return buildErrorResult("参数验证失败: " + e.getMessage());

        } catch (Exception e) {
            logExecutionFailure(toolCall, e);
            return buildErrorResult("创建意图失败: " + e.getMessage());
        }
    }

    /**
     * 此工具需要管理员权限
     */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    /**
     * 仅超级管理员和工厂管理员可使用
     */
    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "platform_admin".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
