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
 * 页面样式更新工具
 *
 * 解析用户的样式更新指令，调用LLM转换为主题/样式配置并更新页面。
 *
 * Intent Code: PAGE_STYLE_UPDATE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class PageStyleUpdateTool extends AbstractBusinessTool {

    @Autowired
    private LowcodePageConfigRepository pageConfigRepository;

    @Autowired
    private DashScopeClient dashScopeClient;

    private static final String DEFAULT_PAGE_ID = "home_page";

    @Override
    public String getToolName() {
        return "page_style_update";
    }

    @Override
    public String getDescription() {
        return "更新页面样式和主题。根据用户描述调整页面的主题模式、主色调、字体大小等样式配置。" +
                "适用场景：切换深色/浅色主题、修改主色调、调整字体大小、修改间距。" +
                "例如：'使用深色主题'、'主色调改为蓝色'、'字体改大一点'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> userInput = new HashMap<>();
        userInput.put("type", "string");
        userInput.put("description", "用户的样式更新描述，例如：'使用深色主题'、'主色调改为蓝色'");
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
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String userInput = getString(params, "userInput");
        String pageId = getString(params, "pageId", DEFAULT_PAGE_ID);
        log.info("执行页面样式更新 - 工厂ID: {}, 页面ID: {}, 用户输入: {}", factoryId, pageId, userInput);

        // 1. 获取当前页面配置
        Optional<LowcodePageConfig> existingConfig = pageConfigRepository.findByFactoryIdAndPageId(factoryId, pageId);
        if (existingConfig.isEmpty()) {
            throw new IllegalArgumentException("未找到页面配置: " + pageId + "，请先创建页面");
        }

        // 2. 调用LLM解析样式更新指令
        String systemPrompt = buildStyleUpdatePrompt();
        String llmResponse = dashScopeClient.chatLowTemp(systemPrompt, userInput);

        // 3. 解析LLM响应
        String cleaned = llmResponse.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        Map<String, Object> styleUpdates = objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});

        // 4. 更新主题配置
        LowcodePageConfig config = existingConfig.get();
        Map<String, Object> themeConfig = parseJsonConfig(config.getThemeConfig());
        themeConfig.putAll(styleUpdates);
        config.setThemeConfig(objectMapper.writeValueAsString(themeConfig));
        config.setVersion(config.getVersion() + 1);
        pageConfigRepository.save(config);

        String styleSummary = (String) styleUpdates.getOrDefault("summary", "样式已更新");

        Map<String, Object> result = new HashMap<>();
        result.put("message", styleSummary);
        result.put("styleUpdates", styleUpdates);
        result.put("pageId", pageId);
        result.put("newThemeConfig", themeConfig);
        result.put("newVersion", config.getVersion());
        return result;
    }

    private String buildStyleUpdatePrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个页面样式助手。用户会描述样式需求，请解析并返回样式配置。\n\n");
        sb.append("可配置的样式选项:\n");
        sb.append("- theme: 'light' | 'dark' (主题模式)\n");
        sb.append("- primaryColor: 主色调 (如 #1890ff)\n");
        sb.append("- fontSize: 'small' | 'medium' | 'large'\n");
        sb.append("- borderRadius: 边框圆角 (如 8)\n");
        sb.append("- spacing: 间距大小 (如 16)\n");
        sb.append("- fontFamily: 字体\n\n");
        sb.append("请输出JSON格式:\n");
        sb.append("{\n");
        sb.append("  \"theme\": \"light\",\n");
        sb.append("  \"primaryColor\": \"#1890ff\",\n");
        sb.append("  \"summary\": \"操作描述\"\n");
        sb.append("}\n\n");
        sb.append("只输出需要更新的字段和summary，不要其他内容");

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

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "userInput", "请描述您想要的样式变更？例如：'使用深色主题' 或 '主色调改为蓝色'",
                "pageId", "请提供目标页面ID"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "userInput", "样式描述",
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
