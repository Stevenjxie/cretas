package com.cretas.aims.controller.dto.indicator;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 指标卡片 next-action 提示 — Sprint 12 Phase B step 4 (Issue #265).
 *
 * <p>Per {@code .claude/rules/fool-proof-design.md} Rule 5: dead-end → next-action.
 * 老板看到 dashboard KPI 卡片应该能 1 次 click 跳到 actionable 页面 (而不是被一个静态
 * 数字 dead-end).
 *
 * <p>存储位置: {@code indicators.config} jsonb 字段 nested 对象 {@code actionHint:
 * {label, route}}. Seeded by {@code V20260825_04__seed_real_business_indicator_action_hints.sql}.
 *
 * <p>Response 读取: {@link IndicatorValueResponse#fromWithIndicator(...)} extract from config.
 *
 * @param label 短文案 (≤8 字), 显示在 KPI 卡片底部的 button. e.g. "查看销售单"
 * @param route 前端 router push 目标. e.g. "/sales/orders?status=ACTIVE"
 *
 * @author Cretas Team
 * @since 2026-05-29 (Sprint 12 Phase B step 4)
 */
@Schema(description = "KPI 卡片 next-action 提示 (fool-proof-design Rule 5)")
public record ActionHint(
        @Schema(description = "按钮文案 (≤8 字)", example = "查看销售单")
        String label,
        @Schema(description = "前端 router 目标", example = "/sales/orders?status=ACTIVE")
        String route
) {

    /**
     * 从 indicator.config jsonb 取 actionHint nested 对象 — 列表 + 详情 DTO 共用 (Sprint 12 #265).
     * 期望 shape: {@code {"actionHint": {"label": "...", "route": "..."}}}.
     * shape 不符 / 解析异常 / label 空 → 返 null (不抛, 老板不应因 config 错配看不到 KPI).
     */
    @SuppressWarnings("unchecked")
    public static ActionHint fromConfig(Map<String, Object> config) {
        if (config == null) return null;
        Object raw = config.get("actionHint");
        if (!(raw instanceof Map)) return null;
        Map<String, Object> map = (Map<String, Object>) raw;
        Object labelObj = map.get("label");
        Object routeObj = map.get("route");
        String label = labelObj instanceof String s && !s.isBlank() ? s : null;
        String route = routeObj instanceof String s && !s.isBlank() ? s : null;
        if (label == null) return null;   // route 可空 (按钮可只显示)
        return new ActionHint(label, route);
    }
}
