package com.cretas.aims.ai.tool.impl.decoration;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.validator.LayoutValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AI首页布局生成工具
 *
 * 调用LLM根据用户需求生成一套首页布局方案。
 *
 * Intent Code: HOME_LAYOUT_GENERATE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class HomeLayoutGenerateTool extends AbstractBusinessTool {

    @Autowired
    private FactoryHomeLayoutToolStore layoutStore;

    @Autowired
    private DashScopeClient dashScopeClient;

    @Autowired
    private LayoutValidator layoutValidator;

    private static final List<Map<String, Object>> DEFAULT_MODULES = List.of(
            Map.of("id", "welcome", "name", "欢迎卡片", "type", "welcome", "x", 0, "y", 0, "w", 2, "h", 1, "visible", true),
            Map.of("id", "ai_insight", "name", "AI洞察", "type", "ai_insight", "x", 0, "y", 1, "w", 2, "h", 2, "visible", true),
            Map.of("id", "stats_grid", "name", "数据统计", "type", "stats_grid", "x", 0, "y", 3, "w", 2, "h", 2, "visible", true),
            Map.of("id", "quick_actions", "name", "快捷操作", "type", "quick_actions", "x", 0, "y", 5, "w", 2, "h", 1, "visible", true),
            Map.of("id", "dev_tools", "name", "开发工具", "type", "dev_tools", "x", 0, "y", 6, "w", 1, "h", 1, "visible", false)
    );

    @Override
    public String getToolName() {
        return "home_layout_generate";
    }

    @Override
    public String getDescription() {
        return "AI生成首页布局方案。根据用户需求描述，使用AI生成一套适合的首页模块布局配置。" +
                "适用场景：生成新的首页布局、重新设计首页、创建个性化布局方案。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> userInput = new HashMap<>();
        userInput.put("type", "string");
        userInput.put("description", "用户对布局的需求描述，例如：'生成一个简洁的管理首页' 或 '我需要重点看数据统计'。为空则使用默认需求。");
        properties.put("userInput", userInput);

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
        Long userId = getUserId(context);
        log.info("执行AI布局生成 - 工厂ID: {}, 用户输入: {}", factoryId, userInput);

        // 1. 构建生成Prompt
        String systemPrompt = buildLayoutGeneratePrompt();
        String prompt = (userInput != null && !userInput.isEmpty())
                ? userInput
                : "生成一个适合工厂管理的首页布局";
        String llmResponse = dashScopeClient.chatLowTemp(systemPrompt, prompt);

        // 2. 解析生成的布局
        List<Map<String, Object>> generatedLayout;
        try {
            generatedLayout = parseGeneratedLayout(llmResponse);
        } catch (Exception e) {
            log.error("解析生成布局失败: {}", e.getMessage());
            generatedLayout = new ArrayList<>(DEFAULT_MODULES);
        }

        // 3. 验证布局合法性
        LayoutValidator.ValidationResult validation = layoutValidator.validate(generatedLayout);
        if (!validation.isValid()) {
            log.warn("生成的布局验证失败，使用默认布局: {}", validation.getErrorMessage());
            generatedLayout = new ArrayList<>(DEFAULT_MODULES);
        }

        // 4. 保存布局
        saveLayout(factoryId, userId, generatedLayout, prompt);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "已为您生成新的首页布局方案");
        result.put("layout", generatedLayout);
        result.put("moduleCount", generatedLayout.size());
        result.put("visibleCount", generatedLayout.stream()
                .filter(m -> Boolean.TRUE.equals(m.get("visible")))
                .count());
        return result;
    }

    private String buildLayoutGeneratePrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个首页布局设计师。请根据用户需求生成首页模块布局。\n\n");
        sb.append("可用模块:\n");
        sb.append("- welcome: 欢迎卡片 (最大 2x1)\n");
        sb.append("- ai_insight: AI洞察 (最大 2x2)\n");
        sb.append("- stats_grid: 数据统计 (最大 2x2, 必须存在)\n");
        sb.append("- quick_actions: 快捷操作 (最大 2x1)\n");
        sb.append("- dev_tools: 开发工具 (最大 1x1)\n\n");
        sb.append("请输出JSON数组格式的布局配置:\n");
        sb.append("[\n");
        sb.append("  {\"id\": \"模块id\", \"x\": 0, \"y\": 位置, \"w\": 宽度, \"h\": 高度, \"visible\": true/false},\n");
        sb.append("  ...\n");
        sb.append("]\n\n");
        sb.append("要求:\n");
        sb.append("1. stats_grid必须存在\n");
        sb.append("2. y值从0开始递增\n");
        sb.append("3. 只输出JSON数组，不要其他内容");

        return sb.toString();
    }

    private List<Map<String, Object>> parseGeneratedLayout(String response) throws Exception {
        String cleaned = response.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        List<Map<String, Object>> parsed = objectMapper.readValue(cleaned,
                new TypeReference<List<Map<String, Object>>>() {});

        for (Map<String, Object> module : parsed) {
            String id = (String) module.get("id");
            DEFAULT_MODULES.stream()
                    .filter(m -> m.get("id").equals(id))
                    .findFirst()
                    .ifPresent(defaultModule -> {
                        module.putIfAbsent("name", defaultModule.get("name"));
                        module.putIfAbsent("type", defaultModule.get("type"));
                    });
            module.putIfAbsent("visible", true);
        }

        return parsed;
    }

    private void saveLayout(String factoryId, Long userId, List<Map<String, Object>> layout, String aiPrompt) {
        // Track A Project 3: 写 factory_home_layout (经 layoutStore 转 nested 形态),
        // 不再写 factory_settings.ai_settings.homeLayout — 让 Tool 输出能被
        // DecorationServiceImpl.getHomeLayout 读到并送达 FAHomeScreen 渲染器。
        layoutStore.saveFlatLayout(factoryId, userId, layout, aiPrompt);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("userInput".equals(paramName)) {
            return "请描述您希望生成什么样的首页布局？例如：'简洁的管理首页' 或 '重点展示数据统计'";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("userInput".equals(paramName)) {
            return "布局需求描述";
        }
        return super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
