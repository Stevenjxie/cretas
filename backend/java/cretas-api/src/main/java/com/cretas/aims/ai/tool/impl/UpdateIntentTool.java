package com.cretas.aims.ai.tool.impl;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractTool;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.service.AIIntentService;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 更新意图配置工具
 *
 * 当LLM需要更新现有意图配置时调用此工具。
 * 支持部分字段更新，不会覆盖未指定的字段。
 * 更新操作需要管理员权限。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Slf4j
@Component
public class UpdateIntentTool extends AbstractTool {

    @Autowired
    @Lazy
    private AIIntentService aiIntentService;

    @Autowired
    private AIIntentConfigRepository intentRepository;

    @Override
    public String getToolName() {
        return "update_intent";
    }

    @Override
    public String getDescription() {
        return "更新现有的意图配置。支持部分字段更新，包括：意图名称、关键词列表、类别、描述、语义分类、敏感级别、启用状态等。" +
                "适用场景：关键词优化、意图重分类、敏感度调整、启用/禁用意图。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // intentCode: 意图代码（必需，用于定位要更新的意图）
        Map<String, Object> intentCode = new HashMap<>();
        intentCode.put("type", "string");
        intentCode.put("description", "要更新的意图代码，必须是已存在的意图");
        properties.put("intentCode", intentCode);

        // intentName: 意图名称（可选）
        Map<String, Object> intentName = new HashMap<>();
        intentName.put("type", "string");
        intentName.put("description", "更新后的意图名称");
        intentName.put("maxLength", 50);
        properties.put("intentName", intentName);

        // category: 意图类别（可选）
        Map<String, Object> category = new HashMap<>();
        category.put("type", "string");
        category.put("description", "更新后的意图类别");
        category.put("enum", Arrays.asList(
                "QUERY", "DATA_OP", "FORM", "REPORT", "MATERIAL", "PRODUCTION",
                "QUALITY", "SHIPMENT", "EQUIPMENT", "ATTENDANCE", "SYSTEM"
        ));
        properties.put("category", category);

        // keywords: 关键词列表（可选）
        Map<String, Object> keywords = new HashMap<>();
        keywords.put("type", "array");
        keywords.put("description", "更新后的关键词列表，会完全替换现有关键词");
        Map<String, Object> keywordItems = new HashMap<>();
        keywordItems.put("type", "string");
        keywords.put("items", keywordItems);
        keywords.put("minItems", 2);
        keywords.put("maxItems", 10);
        properties.put("keywords", keywords);

        // addKeywords: 添加关键词（可选，追加模式）
        Map<String, Object> addKeywords = new HashMap<>();
        addKeywords.put("type", "array");
        addKeywords.put("description", "追加的关键词列表，会与现有关键词合并");
        Map<String, Object> addKeywordItems = new HashMap<>();
        addKeywordItems.put("type", "string");
        addKeywords.put("items", addKeywordItems);
        properties.put("addKeywords", addKeywords);

        // removeKeywords: 移除关键词（可选，删除模式）
        Map<String, Object> removeKeywords = new HashMap<>();
        removeKeywords.put("type", "array");
        removeKeywords.put("description", "要移除的关键词列表");
        Map<String, Object> removeKeywordItems = new HashMap<>();
        removeKeywordItems.put("type", "string");
        removeKeywords.put("items", removeKeywordItems);
        properties.put("removeKeywords", removeKeywords);

        // description: 意图描述（可选）
        Map<String, Object> description = new HashMap<>();
        description.put("type", "string");
        description.put("description", "更新后的意图描述");
        description.put("maxLength", 500);
        properties.put("description", description);

        // semanticDomain: 语义域（可选）
        Map<String, Object> semanticDomain = new HashMap<>();
        semanticDomain.put("type", "string");
        semanticDomain.put("description", "更新后的语义域");
        properties.put("semanticDomain", semanticDomain);

        // semanticAction: 语义动作（可选）
        Map<String, Object> semanticAction = new HashMap<>();
        semanticAction.put("type", "string");
        semanticAction.put("description", "更新后的语义动作");
        properties.put("semanticAction", semanticAction);

        // semanticObject: 语义对象（可选）
        Map<String, Object> semanticObject = new HashMap<>();
        semanticObject.put("type", "string");
        semanticObject.put("description", "更新后的语义对象");
        properties.put("semanticObject", semanticObject);

        // sensitivityLevel: 敏感级别（可选）
        Map<String, Object> sensitivityLevel = new HashMap<>();
        sensitivityLevel.put("type", "string");
        sensitivityLevel.put("description", "更新后的敏感级别");
        sensitivityLevel.put("enum", Arrays.asList("LOW", "MEDIUM", "HIGH", "CRITICAL"));
        properties.put("sensitivityLevel", sensitivityLevel);

        // active: 启用状态（可选）
        Map<String, Object> active = new HashMap<>();
        active.put("type", "boolean");
        active.put("description", "是否启用此意图");
        properties.put("active", active);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("intentCode"));

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
            String factoryId = getFactoryId(context);

            // 2. 查找现有意图配置
            Optional<AIIntentConfig> existingIntentOpt = aiIntentService.getIntentByCode(factoryId, intentCode);
            if (!existingIntentOpt.isPresent()) {
                return buildErrorResult("意图配置不存在: " + intentCode);
            }

            AIIntentConfig intent = existingIntentOpt.get();

            // 记录更新前的状态
            List<String> changes = new ArrayList<>();

            // 3. 更新字段
            if (arguments.containsKey("intentName")) {
                String newName = getOptionalParam(arguments, "intentName", null);
                if (newName != null && !newName.equals(intent.getIntentName())) {
                    changes.add("意图名称: " + intent.getIntentName() + " -> " + newName);
                    intent.setIntentName(newName);
                }
            }

            if (arguments.containsKey("category")) {
                String newCategory = getOptionalParam(arguments, "category", null);
                if (newCategory != null && !newCategory.equals(intent.getIntentCategory())) {
                    changes.add("类别: " + intent.getIntentCategory() + " -> " + newCategory);
                    intent.setIntentCategory(newCategory);
                }
            }

            if (arguments.containsKey("description")) {
                String newDescription = getOptionalParam(arguments, "description", null);
                if (newDescription != null) {
                    changes.add("描述已更新");
                    intent.setDescription(newDescription);
                }
            }

            if (arguments.containsKey("semanticDomain")) {
                String newDomain = getOptionalParam(arguments, "semanticDomain", null);
                if (newDomain != null) {
                    changes.add("语义域: " + intent.getSemanticDomain() + " -> " + newDomain);
                    intent.setSemanticDomain(newDomain);
                }
            }

            if (arguments.containsKey("semanticAction")) {
                String newAction = getOptionalParam(arguments, "semanticAction", null);
                if (newAction != null) {
                    changes.add("语义动作: " + intent.getSemanticAction() + " -> " + newAction);
                    intent.setSemanticAction(newAction);
                }
            }

            if (arguments.containsKey("semanticObject")) {
                String newObject = getOptionalParam(arguments, "semanticObject", null);
                if (newObject != null) {
                    changes.add("语义对象: " + intent.getSemanticObject() + " -> " + newObject);
                    intent.setSemanticObject(newObject);
                }
            }

            if (arguments.containsKey("sensitivityLevel")) {
                String newLevel = getOptionalParam(arguments, "sensitivityLevel", null);
                if (newLevel != null && !newLevel.equals(intent.getSensitivityLevel())) {
                    changes.add("敏感级别: " + intent.getSensitivityLevel() + " -> " + newLevel);
                    intent.setSensitivityLevel(newLevel);
                }
            }

            if (arguments.containsKey("active")) {
                Boolean newActive = (Boolean) arguments.get("active");
                if (newActive != null && !newActive.equals(intent.getIsActive())) {
                    changes.add("启用状态: " + intent.getIsActive() + " -> " + newActive);
                    intent.setIsActive(newActive);
                }
            }

            // 4. 更新关键词（三种模式：替换、追加、删除）
            List<String> currentKeywords = parseKeywords(intent.getKeywords());

            if (arguments.containsKey("keywords")) {
                // 完全替换模式
                @SuppressWarnings("unchecked")
                List<String> newKeywords = (List<String>) arguments.get("keywords");
                if (newKeywords != null && !newKeywords.isEmpty()) {
                    changes.add("关键词已完全替换: " + currentKeywords.size() + " -> " + newKeywords.size() + "个");
                    intent.setKeywords(objectMapper.writeValueAsString(newKeywords));
                }
            } else {
                // 追加/删除模式
                boolean keywordsModified = false;

                if (arguments.containsKey("addKeywords")) {
                    @SuppressWarnings("unchecked")
                    List<String> addKeywords = (List<String>) arguments.get("addKeywords");
                    if (addKeywords != null && !addKeywords.isEmpty()) {
                        Set<String> mergedKeywords = new LinkedHashSet<>(currentKeywords);
                        int beforeSize = mergedKeywords.size();
                        mergedKeywords.addAll(addKeywords);
                        int added = mergedKeywords.size() - beforeSize;
                        if (added > 0) {
                            changes.add("新增关键词: " + added + "个");
                            currentKeywords = new ArrayList<>(mergedKeywords);
                            keywordsModified = true;
                        }
                    }
                }

                if (arguments.containsKey("removeKeywords")) {
                    @SuppressWarnings("unchecked")
                    List<String> removeKeywords = (List<String>) arguments.get("removeKeywords");
                    if (removeKeywords != null && !removeKeywords.isEmpty()) {
                        int beforeSize = currentKeywords.size();
                        currentKeywords.removeAll(removeKeywords);
                        int removed = beforeSize - currentKeywords.size();
                        if (removed > 0) {
                            changes.add("移除关键词: " + removed + "个");
                            keywordsModified = true;
                        }
                    }
                }

                if (keywordsModified) {
                    intent.setKeywords(objectMapper.writeValueAsString(currentKeywords));
                }
            }

            // 5. 检查是否有实际修改
            if (changes.isEmpty()) {
                return buildErrorResult("未提供任何需要更新的字段");
            }

            // 6. 保存更新
            AIIntentConfig updatedIntent = aiIntentService.updateIntent(intent);

            // 7. 构建返回结果
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("intentCode", updatedIntent.getIntentCode());
            resultData.put("intentName", updatedIntent.getIntentName());
            resultData.put("category", updatedIntent.getIntentCategory());
            resultData.put("active", updatedIntent.getIsActive());
            resultData.put("changes", changes);
            resultData.put("message", "意图配置已成功更新，共" + changes.size() + "处变更");

            String result = buildSuccessResult(resultData);
            logExecutionSuccess(toolCall, result);

            return result;

        } catch (IllegalArgumentException e) {
            log.warn("⚠️  参数验证失败: {}", e.getMessage());
            return buildErrorResult("参数验证失败: " + e.getMessage());

        } catch (Exception e) {
            logExecutionFailure(toolCall, e);
            return buildErrorResult("更新意图失败: " + e.getMessage());
        }
    }

    /**
     * 解析关键词JSON字符串为列表
     */
    private List<String> parseKeywords(String keywordsJson) {
        if (keywordsJson == null || keywordsJson.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(keywordsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("⚠️  解析关键词失败: {}", keywordsJson, e);
            return new ArrayList<>();
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
