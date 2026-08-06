package com.cretas.aims.ai.tool.impl.restaurant.gold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RestaurantPeakHoursGoldTool} 的渲染测试。
 *
 * <p>核心断言是一条区分：<b>「缺开单时刻」和「没生意」必须给出不同的话</b>。
 * 这两者在一张全零的时段表上长得一模一样，但含义相反。
 */
class RestaurantPeakHoursGoldToolTest {

    private final RestaurantPeakHoursGoldTool tool = new RestaurantPeakHoursGoldTool();

    @SuppressWarnings("unchecked")
    private Map<String, Object> format(Map<String, Object> goldResult) throws Exception {
        Method m = RestaurantPeakHoursGoldTool.class.getDeclaredMethod("format", Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(tool, goldResult);
    }

    private boolean isEmpty(Map<String, Object> goldResult) throws Exception {
        Method m = RestaurantPeakHoursGoldTool.class.getDeclaredMethod("isEmpty", Map.class);
        m.setAccessible(true);
        return (boolean) m.invoke(tool, goldResult);
    }

    private static Map<String, Object> hour(int h, int bills, double pct, double revenue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hour", h);
        m.put("bill_count", bills);
        m.put("bill_pct", pct);
        m.put("revenue", revenue);
        return m;
    }

    /** 仿真实 MOCK_REST 响应：20 万笔全带时刻，19 时最忙。 */
    private static Map<String, Object> okResult() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("hours_available", true);
        g.put("unavailable_reason", null);
        g.put("total_bills", 202484);
        g.put("hours", List.of(hour(12, 40468, 20.0, 12345.0), hour(19, 42528, 21.0, 23456.0)));
        g.put("peak_hour", 19);
        g.put("peak_bill_count", 42528);
        return g;
    }

    @Test
    @DisplayName("UT-PHG-01: metadata")
    void metadata() {
        assertEquals("restaurant_peak_hours_gold", tool.getToolName());
        assertTrue(tool.getDescription().contains("几点最忙"), tool.getDescription());
    }

    @Test
    @DisplayName("UT-PHG-02: 正常情况报出高峰时段与分布")
    @SuppressWarnings("unchecked")
    void formatsPeakHours() throws Exception {
        Map<String, Object> out = format(okResult());

        assertEquals(true, out.get("可用"));
        assertEquals("19时", out.get("高峰时段"));
        assertEquals(42528, out.get("高峰单量"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("分布");
        assertEquals(2, rows.size());
        assertEquals("12时", rows.get(0).get("时段"));
        assertEquals("20.0%", rows.get(0).get("占比"));
        assertTrue(((String) out.get("message")).contains("最忙的是 19 时"), (String) out.get("message"));
    }

    @Test
    @DisplayName("UT-PHG-03: 🔴 缺开单时刻要说原因, 不得渲染成全零分布")
    void missingTimeExplainsItself() throws Exception {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("hours_available", false);
        g.put("unavailable_reason", "该区间共 18957 笔交易，但都没有记录开单时刻(time 字段为空)，"
                + "因此无法给出按小时的分布。这是数据采集缺失，不代表这些时段没有营业。");
        g.put("total_bills", 18957);
        g.put("hours", List.of());
        g.put("peak_hour", null);

        Map<String, Object> out = format(g);

        assertEquals(false, out.get("可用"));
        String said = (String) out.get("说明");
        assertTrue(said.contains("没有记录开单时刻"), said);
        assertTrue(said.contains("不代表这些时段没有营业"),
                "必须把「不是没生意」说出来, 否则用户会把采集缺失当成经营事实: " + said);
        assertNull(out.get("分布"), "缺时刻时不得给出任何分布, 哪怕是空列表 —— 空列表会被渲染成图表");
    }

    @Test
    @DisplayName("UT-PHG-04: 🔴 缺开单时刻不算「空」 —— 不能走基类的通用无数据话术")
    void missingTimeIsNotEmpty() throws Exception {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("hours_available", false);
        g.put("unavailable_reason", "……没有记录开单时刻……");
        g.put("total_bills", 18957);
        g.put("hours", List.of());

        assertFalse(isEmpty(g),
                "有 18957 笔交易只是没记时刻, 判成空会让用户收到「本期暂无数据」, "
                        + "从而以为是没生意");
    }

    @Test
    @DisplayName("UT-PHG-05: 真的没有交易才算空")
    void noTransactionsIsEmpty() throws Exception {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("hours_available", false);
        g.put("total_bills", 0);
        g.put("hours", List.of());

        assertTrue(isEmpty(g));
        assertTrue(tool.emptyMessage().contains("没有任何交易记录"), tool.emptyMessage());
    }

    @Test
    @DisplayName("UT-PHG-06: 部分缺失时把口径说清楚")
    void partialCoverageIsDisclosed() throws Exception {
        Map<String, Object> g = okResult();
        g.put("partial_coverage_note", "共 202484 笔交易，其中 180000 笔有开单时刻，"
                + "以下分布与百分比均基于这 180000 笔计算。");

        Map<String, Object> out = format(g);

        assertNotNull(out.get("口径说明"));
        assertTrue(((String) out.get("message")).contains("180000"),
                "口径说明必须进到给人看的那句话里, 否则用户以为百分比是按全部交易算的");
    }
}
