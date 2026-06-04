package com.cretas.aims.ai.tool.impl.restaurant.diagnostic;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 店长经营 KPI 看板工具 (single-store 直营 MVP).
 *
 * <p>调用 Python section {@code store_kpi_dashboard} (self-query gold 路径), 返回店长
 * 6 KPI: 日营收 / 客单价 / 订单数 / 毛利率 / 食材成本率 / 目标完成率, 每项带健康 badge
 * (GOOD/WARNING/CRITICAL/INSUFFICIENT/NO_TARGET) + overall_health。单店 = 工厂级聚合,
 * 不做门店范围网关。
 *
 * <p>典型场景: 店长/老板问 "经营看板 / 我的看板 / 店长KPI / 门店经营情况"。
 *
 * <p>RBAC: 经 {@link #buildSectionParams} 注入当前请求用户角色 (X-User-Role 等价),
 * Python compute 据此对金额 KPI (日营收/客单价) 剥零 (非 PRICE_VIEW_ROLES → null,
 * 非 0); 比率/计数保留。诚实: 毛利率无配方成本 → "成本数据不足" (不编率);
 * 目标未配置 → NO_TARGET (前端走"去配置目标"防呆)。
 *
 * <p>注意: 该 tool 兼容 section 的两种返回形状 —— 新 6-KPI ({@code kpis}) 与历史
 * 3 维度 ({@code dimensions}, 当调用方喂入 financial/operational/external dict 时)。
 */
@Slf4j
@Component
public class StoreKpiDashboardTool extends AbstractRestaurantDiagnosticTool {

    @Override
    public String getToolName() {
        return "restaurant_store_kpi_dashboard";
    }

    @Override
    public String getDescription() {
        return "店长经营 KPI 看板 — 单店经营 6 项核心指标 (日营收、客单价、订单数、毛利率、"
             + "食材成本率、目标完成率) 一屏概览, 每项带健康灯 (绿/黄/红) + 整体经营健康度。"
             + "适用场景: 店长/老板问 '经营看板'/'我的看板'/'店长KPI'/'门店经营情况'/'最近经营怎么样'。";
    }

    @Override
    protected String getSectionName() {
        return "store_kpi_dashboard";
    }

    /**
     * 注入 RBAC 角色 (Python compute 据此剥金额) + 可选日期区间 + 店名。
     * 框架字段 (sub_sector/store_id/upload_id/store_name) 由基类剥离。
     */
    @Override
    protected Map<String, Object> buildSectionParams(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) {
        Map<String, Object> sectionParams = super.buildSectionParams(factoryId, params, context);
        String role = resolveUserRole();
        if (role != null && !role.isEmpty()) {
            sectionParams.put("role", role);
        }
        return sectionParams;
    }

    /**
     * 解析当前请求用户角色 (mirror GoldFinanceClient.currentUserRole — Cretas 认证
     * 基于 request attribute "role", 由 JwtAuthInterceptor 写入, NOT Spring SecurityContext)。
     * 无请求上下文 (如定时任务) → null → Python 剥金额 (fail-closed 安全默认)。
     * protected 以便单测覆盖 (避免依赖 servlet 上下文)。
     */
    protected String resolveUserRole() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttrs) {
                HttpServletRequest req = servletAttrs.getRequest();
                Object role = req.getAttribute("role");
                return role != null ? role.toString() : null;
            }
            return null;
        } catch (Exception e) {
            log.debug("resolveUserRole failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 自定义格式化: 把 6-KPI section data 渲染成人类可读 message + 透传卡片。
     * data 空 → 诚实空态 (UI 仍显示 "操作已完成" if message missing, 故必给 message)。
     */
    @Override
    protected Map<String, Object> formatResult(
            String sectionName,
            Map<String, Object> data,
            List<String> warnings) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("section", sectionName);

        if (data == null || data.isEmpty()) {
            result.put("data", Map.of());
            result.put("message", "暂无经营 KPI 数据 — 上传 POS 销售/财务数据后系统将自动汇总店长 6 项指标。");
            result.put("followUpChips", List.of("上传经营数据", "看经营体检表"));
            return result;
        }

        result.put("data", data);
        result.put("message", buildMessage(data));
        result.put("followUpChips", buildFollowUps(sectionName, data));
        return result;
    }

    /** 把 6-KPI 渲染成一句人类可读摘要 (金额剥零的卡显示 "—", 比率/计数照常)。 */
    @SuppressWarnings("unchecked")
    private String buildMessage(Map<String, Object> data) {
        Object kpisObj = data.get("kpis");
        Object overall = data.get("overallHealth");
        if (overall == null) {
            overall = data.get("overall_health");
        }

        StringBuilder sb = new StringBuilder("店长经营看板");
        Object storeName = data.get("storeName") != null ? data.get("storeName") : data.get("store_name");
        if (storeName != null && !String.valueOf(storeName).isEmpty()) {
            sb.append(" — ").append(storeName);
        }
        sb.append(": ");

        if (kpisObj instanceof List) {
            List<Object> kpis = (List<Object>) kpisObj;
            List<String> parts = new ArrayList<>();
            for (Object o : kpis) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> k = (Map<String, Object>) o;
                String label = strOr(k.get("label"), "");
                String value = strOr(k.get("value"), "—");
                String status = strOr(k.get("status"), "");
                String badge = statusBadge(status);
                if (!label.isEmpty()) {
                    parts.add(label + " " + value + badge);
                }
            }
            if (!parts.isEmpty()) {
                sb.append(String.join("，", parts)).append("。");
            }
        } else if (data.get("dimensions") instanceof List) {
            // Legacy 3-dimension shape (caller fed dicts) — concise summary.
            sb.append("三维度健康度评估 (财务/营运/外部评价)。");
        } else {
            sb.append("数据已汇总。");
        }

        if (overall != null) {
            sb.append(" 整体经营健康度: ").append(overallText(String.valueOf(overall))).append("。");
        }
        return sb.toString();
    }

    @Override
    protected List<String> buildFollowUps(String sectionName, Map<String, Object> data) {
        return Arrays.asList("毛利率为什么偏低", "目标完成率怎么提升", "看食材成本明细", "生成月度经营报告");
    }

    // ── helpers ─────────────────────────────────────────────

    private static String statusBadge(String status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case "GOOD":
                return "(良好)";
            case "WARNING":
                return "(预警)";
            case "CRITICAL":
                return "(严重)";
            case "INSUFFICIENT":
                return "(数据不足)";
            case "NO_TARGET":
                return "(未设目标)";
            default:
                return "";
        }
    }

    private static String overallText(String overall) {
        switch (overall) {
            case "GOOD":
                return "良好";
            case "WARNING":
                return "需关注";
            case "CRITICAL":
                return "严重 — 建议立即复盘";
            default:
                return overall;
        }
    }

    private static String strOr(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }
}
