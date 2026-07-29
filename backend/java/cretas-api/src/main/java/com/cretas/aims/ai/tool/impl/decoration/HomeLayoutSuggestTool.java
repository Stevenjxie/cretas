package com.cretas.aims.ai.tool.impl.decoration;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 智能布局建议工具
 *
 * 分析当前首页布局并提供优化建议和推荐布局方案。
 *
 * Intent Code: HOME_LAYOUT_SUGGEST
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class HomeLayoutSuggestTool extends AbstractBusinessTool {

    @Autowired
    private FactoryHomeLayoutToolStore layoutStore;

    private static final List<Map<String, Object>> DEFAULT_MODULES = List.of(
            Map.of("id", "welcome", "name", "欢迎卡片", "type", "welcome", "x", 0, "y", 0, "w", 2, "h", 1, "visible", true),
            Map.of("id", "ai_insight", "name", "AI洞察", "type", "ai_insight", "x", 0, "y", 1, "w", 2, "h", 2, "visible", true),
            Map.of("id", "stats_grid", "name", "数据统计", "type", "stats_grid", "x", 0, "y", 3, "w", 2, "h", 2, "visible", true),
            Map.of("id", "quick_actions", "name", "快捷操作", "type", "quick_actions", "x", 0, "y", 5, "w", 2, "h", 1, "visible", true),
            Map.of("id", "dev_tools", "name", "开发工具", "type", "dev_tools", "x", 0, "y", 6, "w", 1, "h", 1, "visible", false)
    );

    @Override
    public String getToolName() {
        return "home_layout_suggest";
    }

    @Override
    public String getDescription() {
        return "获取智能布局建议。分析当前首页布局的使用习惯，提供优化建议和推荐布局方案。" +
                "适用场景：想要优化首页布局、获取布局改进建议、查看推荐方案。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        Long userId = getUserId(context);
        log.info("执行智能布局建议 - 工厂ID: {}, 用户ID: {}", factoryId, userId);

        // 1. 获取当前布局
        List<Map<String, Object>> currentLayout = getCurrentLayout(factoryId);

        // 2. 分析并生成建议
        List<String> suggestions = generateLayoutSuggestions(currentLayout);

        // 3. 生成推荐布局
        List<Map<String, Object>> suggestedLayout = generateSuggestedLayout(currentLayout);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "已分析您的使用习惯，以下是布局优化建议");
        result.put("suggestions", suggestions);
        result.put("currentLayout", currentLayout);
        result.put("suggestedLayout", suggestedLayout);
        return result;
    }

    private List<Map<String, Object>> getCurrentLayout(String factoryId) {
        // Track A Project 3: 从 factory_home_layout 读 (layoutStore 转 flat 形态)
        return layoutStore.loadFlatLayout(factoryId, DEFAULT_MODULES);
    }

    private List<String> generateLayoutSuggestions(List<Map<String, Object>> currentLayout) {
        List<String> suggestions = new ArrayList<>();

        boolean statsAtTop = currentLayout.stream()
                .filter(m -> "stats_grid".equals(m.get("id")))
                .anyMatch(m -> {
                    Object y = m.get("y");
                    return y instanceof Integer && (Integer) y <= 2;
                });

        if (!statsAtTop) {
            suggestions.add("建议将「数据统计」模块移到更靠前的位置，便于快速查看关键指标");
        }

        boolean aiVisible = currentLayout.stream()
                .filter(m -> "ai_insight".equals(m.get("id")))
                .anyMatch(m -> Boolean.TRUE.equals(m.get("visible")));

        if (!aiVisible) {
            suggestions.add("建议开启「AI洞察」模块，获取智能分析和建议");
        }

        boolean quickActionsVisible = currentLayout.stream()
                .filter(m -> "quick_actions".equals(m.get("id")))
                .anyMatch(m -> Boolean.TRUE.equals(m.get("visible")));

        if (!quickActionsVisible) {
            suggestions.add("建议开启「快捷操作」模块，提高常用功能的访问效率");
        }

        if (suggestions.isEmpty()) {
            suggestions.add("当前布局已经很合理，无需调整");
        }

        return suggestions;
    }

    private List<Map<String, Object>> generateSuggestedLayout(List<Map<String, Object>> currentLayout) {
        List<Map<String, Object>> suggested = new ArrayList<>();

        List<String> preferredOrder = List.of("welcome", "stats_grid", "ai_insight", "quick_actions", "dev_tools");

        for (String id : preferredOrder) {
            currentLayout.stream()
                    .filter(m -> id.equals(m.get("id")))
                    .findFirst()
                    .ifPresent(m -> {
                        Map<String, Object> copy = new HashMap<>(m);
                        if (!"dev_tools".equals(id)) {
                            copy.put("visible", true);
                        }
                        suggested.add(copy);
                    });
        }

        recalculatePositions(suggested);
        return suggested;
    }

    private void recalculatePositions(List<Map<String, Object>> layout) {
        int y = 0;
        for (Map<String, Object> module : layout) {
            module.put("y", y);
            Object h = module.get("h");
            y += (h instanceof Integer) ? (Integer) h : 1;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
