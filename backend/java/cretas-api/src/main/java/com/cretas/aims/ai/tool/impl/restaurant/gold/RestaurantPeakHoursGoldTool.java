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
 * 按小时的营业强度分布（Gold 层 fact_pos_transaction.time）—— 回答「几点最忙」。
 *
 * <p>适用意图：几点最忙 / 高峰时段 / 客流时段分布 / 什么时候人最多。
 *
 * <p><b>为什么不是读主库</b>：原 {@code restaurant_peak_hours_analysis} 读的是
 * {@code sales_orders}——那是工厂给客户开的销售单（带收货地址、运费、合同附件），
 * 不是餐厅 POS。餐饮租户在那张表里恒为 0 行，所以它每次调用都 SUCCESS 且返回空。
 *
 * <p><b>缺开单时刻 ≠ 没生意</b>：{@code time} 列并非所有租户/时段都有
 * （DEMO_REST 1–7 月为空、8 月起才写入；MOCK_REST 全期都有）。Python 端在缺失时
 * 返回 {@code hours_available=false} + {@code unavailable_reason}，本工具据此
 * 给出「没有记录开单时刻」的说明，<b>绝不渲染成 24 小时全 0</b>——那会被读成
 * 「这家店整天没生意」，把采集缺失说成了经营事实。
 *
 * @since 2026-08-06
 */
@Slf4j
@Component
/**
 * factoryId 隔离豁免说明 (@FactoryIsolationExempt): 本类经 GoldBackedRestaurantTool
 * 的 final doExecute(factoryId) 模板方法把 factoryId 传入 queryGold(factoryId, ...)
 * → GoldFinanceClient.fetchPeakHours(factoryId, ...), 按 factory_id 租户隔离
 * (Python gold 层 _resolve_tenant + RLS)。审计正则无法追踪模板方法 + final 修饰,
 * 故显式豁免; 隔离实际由 factoryId 全程传递保证。
 */
public class RestaurantPeakHoursGoldTool extends GoldBackedRestaurantTool {

    @Override
    public String getToolName() {
        return "restaurant_peak_hours_gold";
    }

    @Override
    public String getDescription() {
        return "按小时统计营业强度，回答「几点最忙」「高峰时段是什么时候」。"
                + "返回各小时的单量、占比与营收，并指出单量最高的那个小时。"
                + "数据源为 POS 开单时刻；若该区间没有记录开单时刻，会明确说明"
                + "「缺少开单时间数据」而不是返回全零。";
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
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchPeakHours(factoryId, start, end);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> format(Map<String, Object> goldResult) {
        Map<String, Object> out = new LinkedHashMap<>();

        // 缺开单时刻：把 Python 给的原因原样透出，不要自己编一句更好听的。
        if (!Boolean.TRUE.equals(goldResult.get("hours_available"))) {
            Object reason = goldResult.get("unavailable_reason");
            out.put("可用", false);
            out.put("说明", reason != null ? reason.toString() : "该区间没有可用的开单时刻数据。");
            out.put("message", out.get("说明"));
            return out;
        }

        Object rawHours = goldResult.get("hours");
        List<Map<String, Object>> hours =
                rawHours instanceof List ? (List<Map<String, Object>>) rawHours : List.of();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> h : hours) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("时段", h.get("hour") + "时");
            row.put("单量", h.get("bill_count"));
            row.put("占比", h.get("bill_pct") + "%");
            row.put("营收", h.get("revenue"));
            rows.add(row);
        }

        out.put("可用", true);
        out.put("高峰时段", goldResult.get("peak_hour") + "时");
        out.put("高峰单量", goldResult.get("peak_bill_count"));
        out.put("总单量", goldResult.get("total_bills"));
        out.put("分布", rows);

        StringBuilder msg = new StringBuilder();
        msg.append("最忙的是 ").append(goldResult.get("peak_hour")).append(" 时，")
                .append(goldResult.get("peak_bill_count")).append(" 单。");
        // 部分缺失时把口径说清楚：否则用户会以为百分比是按全部交易算的。
        Object partial = goldResult.get("partial_coverage_note");
        if (partial != null) {
            out.put("口径说明", partial);
            msg.append(' ').append(partial);
        }
        out.put("message", msg.toString());
        return out;
    }

    @Override
    protected boolean isEmpty(Map<String, Object> goldResult) {
        // ⚠️ 只有「本期真的没有交易」才算空。
        // 缺开单时刻(hours_available=false 但 total_bills>0)**不算空** —— 那种情况
        // 要走 format() 把原因讲出来。若在这里一并判空, 用户会收到基类那句通用的
        // "本期暂无数据", 从而以为是没生意, 而不是没记录时间。
        Object total = goldResult.get("total_bills");
        return !(total instanceof Number) || ((Number) total).intValue() == 0;
    }

    @Override
    protected String emptyMessage() {
        return "本期没有任何交易记录，因此无法分析高峰时段。";
    }
}
