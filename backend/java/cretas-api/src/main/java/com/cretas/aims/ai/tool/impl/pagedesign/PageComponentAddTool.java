package com.cretas.aims.ai.tool.impl.pagedesign;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.LowcodeComponentDefinition;
import com.cretas.aims.entity.LowcodePageConfig;
import com.cretas.aims.repository.LowcodeComponentDefinitionRepository;
import com.cretas.aims.repository.LowcodePageConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 页面组件添加工具
 *
 * 解析用户指令，确定要添加的组件并更新页面配置。
 *
 * Intent Code: PAGE_COMPONENT_ADD
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class PageComponentAddTool extends AbstractBusinessTool {

    @Autowired
    private LowcodePageConfigRepository pageConfigRepository;

    @Autowired
    private LowcodeComponentDefinitionRepository componentDefinitionRepository;

    @Autowired
    private DashScopeClient dashScopeClient;

    private static final String DEFAULT_PAGE_ID = "home_page";

    @Override
    public String getToolName() {
        return "page_component_add";
    }

    @Override
    public String getDescription() {
        return "向页面添加组件。解析用户描述的组件需求，自动匹配可用组件并添加到指定页面。" +
                "适用场景：添加图表组件、添加统计卡片、在页面上增加新模块。" +
                "例如：'添加一个折线图组件'、'添加统计卡片'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> userInput = new HashMap<>();
        userInput.put("type", "string");
        userInput.put("description", "用户描述想要添加的组件，例如：'添加一个折线图组件'、'添加统计卡片'");
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
        log.info("执行页面组件添加 - 工厂ID: {}, 页面ID: {}, 用户输入: {}", factoryId, pageId, userInput);

        // 1. 获取当前页面配置
        Optional<LowcodePageConfig> existingConfig = pageConfigRepository.findByFactoryIdAndPageId(factoryId, pageId);
        if (existingConfig.isEmpty()) {
            throw new IllegalArgumentException("未找到页面配置: " + pageId + "，请先创建页面");
        }

        // 2. 获取可用组件
        List<LowcodeComponentDefinition> availableComponents = getAvailableComponents(factoryId);

        // 3. 调用LLM解析用户指令
        String systemPrompt = buildComponentAddPrompt(availableComponents);
        String llmResponse = dashScopeClient.chatLowTemp(systemPrompt, userInput);

        // 4. 解析LLM响应
        String cleaned = llmResponse.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        Map<String, Object> componentToAdd = objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});

        if (componentToAdd.containsKey("error")) {
            throw new IllegalArgumentException((String) componentToAdd.get("error"));
        }

        // 验证组件类型存在
        String type = (String) componentToAdd.get("type");
        boolean validType = availableComponents.stream().anyMatch(c -> c.getComponentType().equals(type));
        if (!validType) {
            throw new IllegalArgumentException("无效的组件类型: " + type);
        }

        componentToAdd.putIfAbsent("id", UUID.randomUUID().toString().substring(0, 8));

        // 5. 更新页面配置
        LowcodePageConfig config = existingConfig.get();
        Map<String, Object> layoutConfig = parseJsonConfig(config.getLayoutConfig());
        addComponentToLayout(layoutConfig, componentToAdd);
        config.setLayoutConfig(objectMapper.writeValueAsString(layoutConfig));
        config.setVersion(config.getVersion() + 1);
        pageConfigRepository.save(config);

        String componentName = (String) componentToAdd.getOrDefault("name", "组件");

        Map<String, Object> result = new HashMap<>();
        result.put("message", "已成功添加组件: " + componentName);
        result.put("addedComponent", componentToAdd);
        result.put("pageId", pageId);
        result.put("newVersion", config.getVersion());
        return result;
    }

    private List<LowcodeComponentDefinition> getAvailableComponents(String factoryId) {
        List<LowcodeComponentDefinition> components = componentDefinitionRepository.findByIsSystemOrFactoryId(1, factoryId);
        return components.stream()
                .filter(c -> c.getStatus() == 1)
                .collect(Collectors.toList());
    }

    private String buildComponentAddPrompt(List<LowcodeComponentDefinition> components) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个页面组件助手。用户会描述想要添加的组件，请解析并返回组件配置。\n\n");
        sb.append("可用组件:\n");

        for (LowcodeComponentDefinition comp : components) {
            sb.append(String.format("- %s (type: %s, category: %s)\n",
                    comp.getName(), comp.getComponentType(), comp.getCategory()));
        }

        sb.append("\n请分析用户描述，输出JSON格式:\n");
        sb.append("{\n");
        sb.append("  \"type\": \"匹配的组件type\",\n");
        sb.append("  \"name\": \"组件名称\",\n");
        sb.append("  \"position\": { \"x\": 0, \"y\": 0, \"w\": 6, \"h\": 2 },\n");
        sb.append("  \"props\": { ... 用户指定的属性 }\n");
        sb.append("}\n\n");
        sb.append("只输出JSON，不要其他内容。如果无法匹配组件，输出 {\"error\": \"无法匹配组件\"}");

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
    private void addComponentToLayout(Map<String, Object> layoutConfig, Map<String, Object> component) {
        List<Map<String, Object>> components = (List<Map<String, Object>>) layoutConfig.get("components");
        if (components == null) {
            components = new ArrayList<>();
            layoutConfig.put("components", components);
        }

        if (!component.containsKey("position")) {
            int maxY = components.stream()
                    .map(c -> {
                        Map<String, Object> pos = (Map<String, Object>) c.get("position");
                        if (pos != null) {
                            Object y = pos.get("y");
                            Object h = pos.get("h");
                            int yVal = y instanceof Integer ? (Integer) y : 0;
                            int hVal = h instanceof Integer ? (Integer) h : 2;
                            return yVal + hVal;
                        }
                        return 0;
                    })
                    .max(Integer::compareTo)
                    .orElse(0);
            component.put("position", Map.of("x", 0, "y", maxY, "w", 6, "h", 2));
        }

        components.add(component);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "userInput", "请描述您要添加什么组件？例如：'添加一个折线图' 或 '添加统计卡片'",
                "pageId", "请提供目标页面ID"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "userInput", "组件描述",
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
