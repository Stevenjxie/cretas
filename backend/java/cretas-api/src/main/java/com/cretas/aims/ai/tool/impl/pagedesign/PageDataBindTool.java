package com.cretas.aims.ai.tool.impl.pagedesign;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.LowcodePageConfig;
import com.cretas.aims.repository.LowcodePageConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 页面数据绑定配置工具
 *
 * 解析用户的数据绑定需求，调用LLM生成绑定配置并更新页面的dataBindings。
 *
 * Intent Code: PAGE_DATA_BIND
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class PageDataBindTool extends AbstractBusinessTool {

    @Autowired
    private LowcodePageConfigRepository pageConfigRepository;

    @Autowired
    private DashScopeClient dashScopeClient;

    private static final String DEFAULT_PAGE_ID = "home_page";

    @Override
    public String getToolName() {
        return "page_data_bind";
    }

    @Override
    public String getDescription() {
        return "配置页面组件的数据绑定。为页面组件配置数据源、API接口绑定和数据映射关系。" +
                "适用场景：将组件绑定到API接口、配置数据刷新策略、设置数据映射。" +
                "例如：'将统计卡片绑定到订单数据接口'、'设置图表每5秒刷新一次'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> userInput = new HashMap<>();
        userInput.put("type", "string");
        userInput.put("description", "用户的数据绑定描述，例如：'将统计卡片绑定到订单数据接口'");
        properties.put("userInput", userInput);

        Map<String, Object> pageId = new HashMap<>();
        pageId.put("type", "string");
        pageId.put("description", "目标页面ID，不传则使用默认值 home_page");
        properties.put("pageId", pageId);

        schema.put("properties", properties);
        schema.put("required", List.of("userInput"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("userInput");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String userInput = getString(params, "userInput");
        String pageId = getString(params, "pageId", DEFAULT_PAGE_ID);
        log.info("执行页面数据绑定 - 工厂ID: {}, 页面ID: {}, 用户输入: {}", factoryId, pageId, userInput);

        // 1. 获取当前页面配置
        Optional<LowcodePageConfig> existingConfig = pageConfigRepository.findByFactoryIdAndPageId(factoryId, pageId);
        if (existingConfig.isEmpty()) {
            throw new IllegalArgumentException("未找到页面配置: " + pageId + "，请先创建页面");
        }

        // 2. 调用LLM解析数据绑定指令
        String systemPrompt = buildDataBindPrompt();
        String llmResponse = dashScopeClient.chatLowTemp(systemPrompt, userInput);

        // 3. 解析LLM响应
        String cleaned = llmResponse.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        Map<String, Object> bindingConfig = objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});

        // 4. 更新数据绑定配置
        LowcodePageConfig config = existingConfig.get();
        Map<String, Object> dataBindings = parseJsonConfig(config.getDataBindings());
        mergeDataBindings(dataBindings, bindingConfig);
        config.setDataBindings(objectMapper.writeValueAsString(dataBindings));
        config.setVersion(config.getVersion() + 1);
        pageConfigRepository.save(config);

        String bindingSummary = (String) bindingConfig.getOrDefault("summary", "数据绑定已配置");

        Map<String, Object> result = new HashMap<>();
        result.put("message", bindingSummary);
        result.put("bindingConfig", bindingConfig);
        result.put("pageId", pageId);
        result.put("dataBindings", dataBindings);
        result.put("newVersion", config.getVersion());
        return result;
    }

    private String buildDataBindPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个数据绑定配置助手。用户会描述数据绑定需求，请解析并返回绑定配置。\n\n");
        sb.append("绑定配置格式:\n");
        sb.append("{\n");
        sb.append("  \"componentId\": \"目标组件ID\",\n");
        sb.append("  \"dataSource\": {\n");
        sb.append("    \"type\": \"api\" | \"static\" | \"store\",\n");
        sb.append("    \"endpoint\": \"API路径\",\n");
        sb.append("    \"method\": \"GET\" | \"POST\",\n");
        sb.append("    \"params\": { ... }\n");
        sb.append("  },\n");
        sb.append("  \"mapping\": {\n");
        sb.append("    \"propName\": \"dataPath\"\n");
        sb.append("  },\n");
        sb.append("  \"refreshInterval\": 刷新间隔秒数,\n");
        sb.append("  \"summary\": \"操作描述\"\n");
        sb.append("}\n\n");
        sb.append("只输出JSON，不要其他内容");

        return sb.toString();
    }

    private Map<String, Object> parseJsonConfig(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析配置失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeDataBindings(Map<String, Object> existing, Map<String, Object> newBindings) {
        String componentId = (String) newBindings.get("componentId");
        if (componentId != null) {
            Map<String, Object> bindings = (Map<String, Object>) existing.computeIfAbsent("bindings", k -> new HashMap<>());
            bindings.put(componentId, newBindings);
        }
        newBindings.remove("summary");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "userInput", "请描述您的数据绑定需求？例如：'将统计卡片绑定到订单数据接口' 或 '设置图表每5秒刷新'",
                "pageId", "请提供目标页面ID"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "userInput", "数据绑定描述",
                "pageId", "页面ID"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
