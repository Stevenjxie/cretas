package com.cretas.aims.controller.dto.indicator;

import io.swagger.v3.oas.annotations.media.Schema;

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
) {}
