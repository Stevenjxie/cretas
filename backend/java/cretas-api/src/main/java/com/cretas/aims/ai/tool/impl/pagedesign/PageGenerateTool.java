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
 * AI页面布局生成工具
 *
 * 获取可用组件列表，调用LLM根据用户需求生成页面布局配置。
 *
 * Intent Code: PAGE_GENERATE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class PageGenerateTool extends AbstractBusinessTool {

    @Autowired
    private LowcodePageConfigRepository pageConfigRepository;

    @Autowired
    private LowcodeComponentDefinitionRepository componentDefinitionRepository;

    @Autowired
    private DashScopeClient dashScopeClient;

    private static final String DEFAULT_PAGE_TYPE = "home";
    private static final String DEFAULT_PAGE_ID = "home_page";

    @Override
    public String getToolName() {
        return "page_generate";
    }

    @Override
    public String getDescription() {
        return "AI生成页面布局配置。根据用户需求描述，使用AI生成低代码页面的组件布局。" +
                "适用场景：创建新页面、生成页面布局、设计页面结构。" +
                "例如：'生成一个数据看板页面'、'创建一个包含图表和统计卡片的页面'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> userInput = new HashMap<>();
        userInput.put("type", "string");
        userInput.put("description", "用户对页面的需求描述，例如：'生成一个数据看板页面'");
        properties.put("userInput", userInput);

        Map<String, Object> pageId = new HashMap<>();
        pageId.put("type", "string");
        pageId.put("description", "页面ID，不传则使用默认值 home_page");
        properties.put("pageId", pageId);

        Map<String, Object> pageType = new HashMap<>();
        pageType.put("type", "string");
        pageType.put("description", "页面类型，不传则使用默认值 home");
        properties.put("pageType", pageType);

        Map<String, Object> pageName = new HashMap<>();
        pageName.put("type", "string");
        pageName.put("description", "页面名称，不传则使用默认值 'AI生成页面'");
        properties.put("pageName", pageName);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String userInput = getString(params, "userInput");
        String pageId = getString(params, "pageId", DEFAULT_PAGE_ID);
        String pageType = getString(params, "pageType", DEFAULT_PAGE_TYPE);
        String pageName = getString(params, "pageName", "AI生成页面");
        Long userId = getUserId(context);
        log.info("执行AI页面生成 - 工厂ID: {}, 页面ID: {}, 用户输入: {}", factoryId, pageId, userInput);

        // 1. 获取可用组件列表
        List<LowcodeComponentDefinition> availableComponents = getAvailableComponents(factoryId);
        if (availableComponents.isEmpty()) {
            throw new IllegalArgumentException("没有可用的组件定义，请先配置组件库");
        }

        // 2. 构建LLM Prompt
        String systemPrompt = buildPageGeneratePrompt(availableComponents);
        String prompt = (userInput != null && !userInput.isEmpty())
                ? userInput
                : "生成一个适合工厂管理的首页布局，包含数据统计、快捷操作和图表展示";
        String llmResponse = dashScopeClient.chatLowTemp(systemPrompt, prompt);

        // 3. 解析生成的布局
        Map<String, Object> generatedConfig;
        try {
            generatedConfig = parseGeneratedPageConfig(llmResponse, availableComponents);
        } catch (Exception e) {
            log.error("解析生成配置失败: {}", e.getMessage());
            generatedConfig = buildDefaultPageConfig(availableComponents);
        }

        // 4. 保存页面配置
        LowcodePageConfig savedConfig = savePageConfig(factoryId, pageId, pageType, pageName,
                generatedConfig, userInput, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "已成功生成页面布局配置");
        result.put("pageConfig", generatedConfig);
        result.put("pageId", savedConfig.getPageId());
        result.put("configId", savedConfig.getId());
        result.put("componentCount", countComponents(generatedConfig));
        return result;
    }

    private List<LowcodeComponentDefinition> getAvailableComponents(String factoryId) {
        List<LowcodeComponentDefinition> components = componentDefinitionRepository.findByIsSystemOrFactoryId(1, factoryId);
        return components.stream()
                .filter(c -> c.getStatus() == 1)
                .collect(Collectors.toList());
    }

    private String buildPageGeneratePrompt(List<LowcodeComponentDefinition> components) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个低代码页面设计师。请根据用户需求生成页面布局配置。\n\n");
        sb.append("可用组件:\n");

        for (LowcodeComponentDefinition comp : components) {
            sb.append(String.format("- %s (%s): %s\n",
                    comp.getName(), comp.getComponentType(),
                    comp.getAiDescription() != null ? comp.getAiDescription() : ""));
        }

        sb.append("\n请输出JSON格式的页面配置:\n");
        sb.append("{\n");
        sb.append("  \"layout\": {\n");
        sb.append("    \"type\": \"grid\",\n");
        sb.append("    \"columns\": 12,\n");
        sb.append("    \"gap\": 16\n");
        sb.append("  },\n");
        sb.append("  \"components\": [\n");
        sb.append("    {\n");
        sb.append("      \"id\": \"唯一ID\",\n");
        sb.append("      \"type\": \"组件type\",\n");
        sb.append("      \"name\": \"组件显示名\",\n");
        sb.append("      \"position\": { \"x\": 0, \"y\": 0, \"w\": 6, \"h\": 2 },\n");
        sb.append("      \"props\": { ... }\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");
        sb.append("要求:\n");
        sb.append("1. 合理安排组件位置，避免重叠\n");
        sb.append("2. 重要信息放在页面上方\n");
        sb.append("3. 只输出JSON，不要其他内容");

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseGeneratedPageConfig(String response, List<LowcodeComponentDefinition> components) throws Exception {
        String cleaned = response.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        Map<String, Object> config = objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});

        if (config.containsKey("components")) {
            List<Map<String, Object>> comps = (List<Map<String, Object>>) config.get("components");
            for (Map<String, Object> comp : comps) {
                String type = (String) comp.get("type");
                components.stream()
                        .filter(c -> c.getComponentType().equals(type))
                        .findFirst()
                        .ifPresent(def -> {
                            comp.putIfAbsent("name", def.getName());
                            comp.putIfAbsent("category", def.getCategory());
                        });
                comp.putIfAbsent("id", UUID.randomUUID().toString().substring(0, 8));
            }
        }

        return config;
    }

    private Map<String, Object> buildDefaultPageConfig(List<LowcodeComponentDefinition> components) {
        Map<String, Object> config = new HashMap<>();
        config.put("layout", Map.of("type", "grid", "columns", 12, "gap", 16));

        List<Map<String, Object>> defaultComponents = new ArrayList<>();
        int y = 0;

        for (LowcodeComponentDefinition comp : components) {
            if ("display".equals(comp.getCategory()) && defaultComponents.size() < 4) {
                Map<String, Object> compConfig = new HashMap<>();
                compConfig.put("id", UUID.randomUUID().toString().substring(0, 8));
                compConfig.put("type", comp.getComponentType());
                compConfig.put("name", comp.getName());
                compConfig.put("position", Map.of("x", 0, "y", y, "w", 6, "h", 2));
                compConfig.put("props", new HashMap<>());
                defaultComponents.add(compConfig);
                y += 2;
            }
        }

        config.put("components", defaultComponents);
        return config;
    }

    private LowcodePageConfig savePageConfig(String factoryId, String pageId, String pageType,
                                              String pageName, Map<String, Object> config,
                                              String aiPrompt, Long userId) throws Exception {
        Optional<LowcodePageConfig> existing = pageConfigRepository.findByFactoryIdAndPageId(factoryId, pageId);

        LowcodePageConfig pageConfig;
        if (existing.isPresent()) {
            pageConfig = existing.get();
            pageConfig.setVersion(pageConfig.getVersion() + 1);
        } else {
            pageConfig = LowcodePageConfig.builder()
                    .pageId(pageId)
                    .factoryId(factoryId)
                    .pageType(pageType)
                    .pageName(pageName)
                    .status(0)
                    .version(1)
                    .createdBy(userId)
                    .build();
        }

        pageConfig.setLayoutConfig(objectMapper.writeValueAsString(config));
        pageConfig.setAiGenerated(1);
        pageConfig.setAiPrompt(aiPrompt);

        return pageConfigRepository.save(pageConfig);
    }

    @SuppressWarnings("unchecked")
    private int countComponents(Map<String, Object> config) {
        List<Map<String, Object>> components = (List<Map<String, Object>>) config.get("components");
        return components != null ? components.size() : 0;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "userInput", "请描述您想要生成什么样的页面？例如：'数据看板页面' 或 '包含图表和统计卡片的页面'",
                "pageId", "请提供页面ID",
                "pageType", "请提供页面类型",
                "pageName", "请提供页面名称"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "userInput", "页面需求描述",
                "pageId", "页面ID",
                "pageType", "页面类型",
                "pageName", "页面名称"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
