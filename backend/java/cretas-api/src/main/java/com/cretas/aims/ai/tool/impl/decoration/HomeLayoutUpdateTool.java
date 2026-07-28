package com.cretas.aims.ai.tool.impl.decoration;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.validator.LayoutValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页布局更新工具
 *
 * 解析用户自然语言指令，调用LLM转换为布局操作（移动/显示/隐藏模块）。
 *
 * Intent Code: HOME_LAYOUT_UPDATE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class HomeLayoutUpdateTool extends AbstractBusinessTool {

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
        return "home_layout_update";
    }

    @Override
    public String getDescription() {
        return "更新首页布局。根据用户自然语言指令调整首页模块的位置、显示/隐藏状态。" +
                "适用场景：移动模块位置、显示或隐藏某个模块、调整首页布局。" +
                "例如：'把AI洞察移到顶部'、'隐藏开发工具'、'显示快捷操作'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> userInput = new HashMap<>();
        userInput.put("type", "string");
        userInput.put("description", "用户的布局更新指令，例如：'把AI洞察移到顶部'、'隐藏开发工具'");
        properties.put("userInput", userInput);

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
        Long userId = getUserId(context);
        log.info("执行首页布局更新 - 工厂ID: {}, 用户输入: {}", factoryId, userInput);

        // 1. 获取当前布局
        List<Map<String, Object>> currentLayout = getCurrentLayout(factoryId);

        // 2. 调用LLM解析用户指令
        String systemPrompt = buildLayoutUpdatePrompt(currentLayout);
        String llmResponse = dashScopeClient.chatLowTemp(systemPrompt, userInput);

        // 3. 解析LLM响应并应用操作
        String cleaned = llmResponse.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        Map<String, Object> parsedResponse = objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});

        if (parsedResponse.containsKey("error")) {
            throw new IllegalArgumentException((String) parsedResponse.get("error"));
        }

        List<Map<String, Object>> updatedLayout = applyLayoutOperations(currentLayout, parsedResponse);
        String operationSummary = (String) parsedResponse.getOrDefault("summary", "布局已更新");

        // 4. 验证布局合法性
        LayoutValidator.ValidationResult validation = layoutValidator.validate(updatedLayout);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("布局更新失败: " + validation.getErrorMessage());
        }

        // 5. 保存布局
        saveLayout(factoryId, userId, updatedLayout, userInput);

        Map<String, Object> result = new HashMap<>();
        result.put("message", operationSummary);
        result.put("layout", updatedLayout);
        result.put("moduleCount", updatedLayout.size());
        result.put("visibleCount", updatedLayout.stream()
                .filter(m -> Boolean.TRUE.equals(m.get("visible")))
                .count());
        return result;
    }

    private List<Map<String, Object>> getCurrentLayout(String factoryId) {
        // Track A Project 3: 从 factory_home_layout 读 (layoutStore 转 flat 形态)
        return layoutStore.loadFlatLayout(factoryId, DEFAULT_MODULES);
    }

    private void saveLayout(String factoryId, Long userId, List<Map<String, Object>> layout, String userInput) {
        // Track A Project 3: 写 factory_home_layout
        layoutStore.saveFlatLayout(factoryId, userId, layout, userInput);
    }

    private String buildLayoutUpdatePrompt(List<Map<String, Object>> currentLayout) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个首页布局助手。用户会给出自然语言指令来调整首页模块布局。\n\n");
        sb.append("当前布局包含以下模块:\n");

        for (Map<String, Object> module : currentLayout) {
            sb.append(String.format("- %s (id: %s, 位置: y=%s, 可见: %s)\n",
                    module.get("name"), module.get("id"), module.get("y"), module.get("visible")));
        }

        sb.append("\n请分析用户指令，输出JSON格式的操作:\n");
        sb.append("{\n");
        sb.append("  \"operation\": \"move|show|hide|resize\",\n");
        sb.append("  \"targetId\": \"模块ID\",\n");
        sb.append("  \"params\": { \"position\": \"top|bottom|up|down\" 或 \"visible\": true|false },\n");
        sb.append("  \"summary\": \"操作描述\"\n");
        sb.append("}\n\n");
        sb.append("只输出JSON，不要其他内容。如果无法理解指令，输出 {\"error\": \"无法理解指令\"}");

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> applyLayoutOperations(List<Map<String, Object>> layout,
                                                            Map<String, Object> operation) {
        String op = (String) operation.get("operation");
        String targetId = (String) operation.get("targetId");
        Map<String, Object> params = (Map<String, Object>) operation.getOrDefault("params", Map.of());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> module : layout) {
            result.add(new HashMap<>(module));
        }

        switch (op) {
            case "show" -> {
                for (Map<String, Object> module : result) {
                    if (targetId.equals(module.get("id"))) {
                        module.put("visible", true);
                    }
                }
            }
            case "hide" -> {
                for (Map<String, Object> module : result) {
                    if (targetId.equals(module.get("id"))) {
                        module.put("visible", false);
                    }
                }
            }
            case "move" -> {
                String position = (String) params.get("position");
                Map<String, Object> target = null;
                for (Map<String, Object> m : result) {
                    if (targetId.equals(m.get("id"))) {
                        target = m;
                        break;
                    }
                }
                if (target != null) {
                    if ("top".equals(position)) {
                        result.remove(target);
                        result.add(0, target);
                    } else if ("bottom".equals(position)) {
                        result.remove(target);
                        result.add(target);
                    }
                    recalculatePositions(result);
                }
            }
        }

        return result;
    }

    private void recalculatePositions(List<Map<String, Object>> layout) {
        int y = 0;
        for (Map<String, Object> module : layout) {
            module.put("y", y);
            Object h = module.get("h");
            y += (h instanceof Integer) ? (Integer) h : 1;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("userInput".equals(paramName)) {
            return "请描述您要如何调整首页布局？例如：'把AI洞察移到顶部' 或 '隐藏开发工具'";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("userInput".equals(paramName)) {
            return "布局更新指令";
        }
        return super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
