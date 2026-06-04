package com.cretas.aims.ai.tool.impl.restaurant.diagnostic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * #56 价值可视化回馈回路 — 价值回馈月报工具。
 *
 * <p>读取 Python {@code value_summary} section (restaurant_value_snapshots 最新快照),
 * 把诊断引擎已算出的省钱/改善金额按月度 + 年化两口径 (D3) 呈现给门店经理/老板。
 * 解决金毛范囊式"门店看不到价值 → 配合度崩塌"死因。
 *
 * <p>意图绑定: {@code RESTAURANT_VALUE_SUMMARY} (priority 110, business_type RESTAURANT)。
 * 客户问 "本月省了多少 / 价值回馈 / 可优化空间 / 降本空间" 时命中。
 *
 * <p>数据来源说明 (诚实): 快照由数据上传 (hooks.py) + 每月 cron 计算; 金额区分
 * "预估" (达标后可节省空间, 年化) 与 "实测" (本月损溢)。无快照 → Python section
 * 返回 SKIPPED → 基类 buildError 诚实提示 "未检测到价值快照, 请先上传月度经营数据"。
 * 禁伪造金额。
 */
@Slf4j
@Component
public class RestaurantValueSummaryTool extends AbstractRestaurantDiagnosticTool {

    @Override
    public String getToolName() {
        return "restaurant_value_summary";
    }

    @Override
    public String getDescription() {
        return "价值回馈月报: 把诊断引擎已算出的省钱/改善金额 (人工刚性节省、食材成本改善空间、"
             + "折扣率改善空间、档口损溢超标、处方预估收益) 按月度 + 年化两口径呈现给门店经理/老板, "
             + "区分预估与实测金额. 适用场景: 客户问'本月省了多少'/'配合系统能省多少钱'/"
             + "'有多少可优化空间'/'降本空间在哪'/'价值回馈'.";
    }

    @Override
    protected String getSectionName() {
        return "value_summary";
    }

    /**
     * 自定义格式化: 把 value_summary section 的 {month, annual} 两口径渲染成价值卡片
     * + 人类可读 message。data 为 null (理论上不会, SKIPPED 会被基类拦) → 诚实提示。
     */
    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> formatResult(
            String sectionName,
            Map<String, Object> data,
            List<String> warnings) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("section", sectionName);

        if (data == null || data.isEmpty()) {
            // section OK 但 data 空 (理论 SKIPPED 已被基类拦) — 诚实空态。
            result.put("data", Map.of());
            result.put("message", "暂无价值快照 — 上传月度经营数据后系统将自动算出可节省金额。");
            result.put("followUpChips", List.of("上传经营数据", "看经营体检表"));
            return result;
        }

        result.put("data", data);
        result.put("message", buildMessage(data));
        result.put("followUpChips", buildFollowUps(sectionName, data));
        return result;
    }

    /** 把快照渲染成一句人类可读摘要 (金额带"预估/实测"标签, null → 暂无数据)。 */
    @SuppressWarnings("unchecked")
    private String buildMessage(Map<String, Object> data) {
        String period = strOr(data.get("periodMonth"), "本月");
        int critical = intOr(data.get("criticalCount"), 0);
        int rxCount = intOr(data.get("rxActionCount"), 0);

        Object monthObj = data.get("month");
        Object annualObj = data.get("annual");
        Double monthTotal = monthObj instanceof Map ? toDouble(((Map<String, Object>) monthObj).get("total")) : null;
        Double annualTotal = annualObj instanceof Map ? toDouble(((Map<String, Object>) annualObj).get("total")) : null;

        StringBuilder sb = new StringBuilder();
        sb.append(period).append(" 价值回馈月报: ");
        if (monthTotal != null) {
            sb.append("本月可优化/节省空间约 ¥").append(fmtAmount(monthTotal));
            if (annualTotal != null) {
                sb.append(" (年化约 ¥").append(fmtAmount(annualTotal)).append(")");
            }
            sb.append("。");
        } else {
            sb.append("金额暂无数据 (需财务/POS 数据才能算出可节省金额)。");
        }
        sb.append("含 ").append(critical).append(" 项严重指标 + ")
          .append(rxCount).append(" 条改善处方。");

        // 明细分项 (有金额的才列)
        List<String> parts = new ArrayList<>();
        if (annualObj instanceof Map) {
            Double labor = toDouble(((Map<String, Object>) annualObj).get("laborRigidity"));
            if (labor != null) {
                parts.add("人工刚性节省 ¥" + fmtAmount(labor) + "/年 (预估)");
            }
        }
        if (monthObj instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) monthObj;
            Double food = toDouble(m.get("foodCostSavings"));
            if (food != null) {
                parts.add("食材成本改善空间 ¥" + fmtAmount(food) + "/月 (预估)");
            }
            Double discount = toDouble(m.get("discountSavings"));
            if (discount != null) {
                parts.add("折扣率改善空间 ¥" + fmtAmount(discount) + "/月 (预估)");
            }
            Double shrink = toDouble(m.get("shrinkageVariance"));
            if (shrink != null) {
                parts.add("档口损溢超标 ¥" + fmtAmount(shrink) + "/月 (本月实测)");
            }
        }
        if (!parts.isEmpty()) {
            sb.append(" 分项: ").append(String.join("; ", parts)).append("。");
        }
        return sb.toString();
    }

    @Override
    protected List<String> buildFollowUps(String sectionName, Map<String, Object> data) {
        List<String> chips = new ArrayList<>();
        chips.add("给我看具体处方");
        chips.add("上月对比");
        chips.add("哪个档口损溢最高");
        if (intOr(data.get("criticalCount"), 0) > 0) {
            chips.add("为什么亏损这么多");
        }
        return chips;
    }

    // ── helpers ─────────────────────────────────────────────

    private static String strOr(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }

    private static int intOr(Object v, int fallback) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return fallback;
    }

    private static Double toDouble(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return null;  // null 保留 (禁降级填 0)
    }

    private static String fmtAmount(double v) {
        return String.format("%,.0f", v);
    }
}
