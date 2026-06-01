package com.cretas.aims.ai.tool.impl.restaurant.gold;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店开单操作员排行工具（Gold 层 staff-ranking）。
 *
 * <p>Python Gold 的 {@code staff_ranking} 查询返回：
 * <ul>
 *   <li>{@code staff} — list of {name, net_amount, bill_count}</li>
 *   <li>{@code caveat} — 诚实免责说明（POS 数据按开单操作员记账，非服务员归因）</li>
 * </ul>
 * 说明字段 {@code caveat} 必须原文透传给 LLM（防呆原则 — 禁止将开单排行伪造为"最佳服务员"）。
 *
 * <p>适用意图：员工里谁最厉害 / 开单排行 / 哪个员工开单最多。
 *
 * @since 2026-06-01
 */
@Slf4j
@Component
/**
 * factoryId 隔离豁免说明 (@FactoryIsolationExempt): 本类经 GoldBackedRestaurantTool
 * 的 final doExecute(factoryId) 模板方法把 factoryId 传入 queryGold(factoryId, ...)
 * → GoldFinanceClient.fetchX(factoryId, ...), 每个 gold 查询都按 factory_id 租户隔离
 * (Python gold 层 _resolve_tenant)。审计正则无法追踪模板方法 + final 修饰, 故显式豁免;
 * 隔离实际由 factoryId 全程传递保证。
 */
public class RestaurantStaffRankingGoldTool extends GoldBackedRestaurantTool {

    @Override
    public String getToolName() {
        return "restaurant_staff_ranking_gold";
    }

    @Override
    public String getDescription() {
        return "门店开单操作员排行(gold 层)。注意: POS staff_id 是收银/点菜操作员, 非服务员业绩。适用: 员工里谁最厉害/开单排行。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> monthProp = new HashMap<>();
        monthProp.put("type", "string");
        monthProp.put("description", "分析月份, 如 '2026年3月' / '上月', 不传默认全部数据");

        Map<String, Object> properties = new HashMap<>();
        properties.put("month", monthProp);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    // getRequiredParameters() inherited — returns empty list.

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchStaffRanking(factoryId, start, end, 5);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> format(Map<String, Object> goldResult) {
        List<Map<String, Object>> rawStaff =
                goldResult.get("staff") instanceof List
                        ? (List<Map<String, Object>>) goldResult.get("staff")
                        : Collections.emptyList();

        List<Map<String, Object>> ranking = new ArrayList<>();
        for (Map<String, Object> row : rawStaff) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("操作员", row.get("name"));
            entry.put("开单净额", row.get("net_amount"));
            entry.put("单数", row.get("bill_count"));
            ranking.add(entry);
        }

        // caveat MUST be surfaced verbatim — no fabricating "best server" claims.
        Object caveat = goldResult.get("caveat");
        String caveatStr = caveat != null ? caveat.toString() : "POS 数据按开单操作员记账, 非服务员业绩归因。";

        StringBuilder sb = new StringBuilder();
        sb.append("开单操作员排行：\n");
        for (int i = 0; i < ranking.size(); i++) {
            Map<String, Object> entry = ranking.get(i);
            Object amt = entry.get("开单净额");
            double amtD = amt instanceof Number ? ((Number) amt).doubleValue() : 0.0;
            Object bills = entry.get("单数");
            sb.append(i + 1).append(". ").append(entry.get("操作员"))
                    .append(" — 开单净额").append(fmtAmt(amtD))
                    .append("，").append(bills != null ? bills : 0).append("单");
            if (i < ranking.size() - 1) sb.append("\n");
        }
        sb.append("\n\n说明：").append(caveatStr);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("开单操作员排行", ranking);
        result.put("说明", caveatStr);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        return result;
    }

    private static String fmtAmt(double v) {
        return v >= 10_000 ? String.format("%.1f万", v / 10_000) : String.format("%.0f", v);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected boolean isEmpty(Map<String, Object> goldResult) {
        Object raw = goldResult.get("staff");
        if (!(raw instanceof List)) return true;
        return ((List<?>) raw).isEmpty();
    }

    @Override
    protected String emptyMessage() {
        return "本期暂无员工开单数据。";
    }
}
