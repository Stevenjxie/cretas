package com.cretas.aims.service.restaurant;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Single semantic policy for restaurant comprehensive-analysis routing.
 *
 * <p>The HTTP controller owns a narrow deterministic report shortcut while
 * the execution orchestrator owns the full Tool/Skill route. Both layers must
 * use the same rule; otherwise the controller can pin a single report intent
 * before the orchestrator gets a chance to select comprehensive synthesis.
 */
public final class RestaurantComprehensiveQuestionPolicy {

    private static final Pattern EXPLICIT_PATTERN = Pattern.compile(
            "综合分析|全面分析|多维(?:度)?分析|综合诊断|综合评估|"
                    + "整体经营分析|运营分析|经营分析|"
                    + "内部(?:经营|数据|指标).{0,20}外部(?:环境|数据|因素|维度)|"
                    + "外部(?:环境|数据|因素|维度).{0,20}内部(?:经营|数据|指标)|"
                    + "(?:明确|逐项说明|告诉我)?缺失维度");

    private static final Pattern ANALYSIS_HINT =
            Pattern.compile("分析|诊断|评估|原因|建议|决策|方案|优化");

    private static final Pattern SUPPLIER_PRICE_STABILITY = Pattern.compile(
            "(?:采购|进货|食材|原料|供应商).{0,10}(?:价格|进价|成本)"
                    + ".{0,10}(?:稳定|波动|异常|上涨|涨价|下降|降价|趋势)"
                    + "|(?:价格|进价|成本).{0,10}(?:稳定|波动|异常|上涨|涨价|下降|降价|趋势)"
                    + ".{0,10}(?:采购|进货|食材|原料|供应商)");

    private static final List<Pattern> DIMENSIONS = List.of(
            Pattern.compile("客流|客流量|到店|进店|顾客数|人数"),
            Pattern.compile("菜品|菜式|单品|销量|点单|爆品"),
            Pattern.compile("毛利|成本|利润|营收|营业额|客单价|订单量|单量"),
            Pattern.compile("周边|商圈|竞品|竞争"),
            Pattern.compile("天气|气温|降雨|下雨|高温|低温"),
            Pattern.compile("活动|促销|折扣|优惠|团购|核销"),
            Pattern.compile("评价|差评|好评|口碑|评分"),
            Pattern.compile("排班|人效|员工|前厅|后厨|工时"));

    private RestaurantComprehensiveQuestionPolicy() {
    }

    public static boolean matches(String userInput) {
        if (userInput == null) {
            return false;
        }
        String normalized = userInput.replaceAll("\\s+", "");
        if (EXPLICIT_PATTERN.matcher(normalized).find()
                || SUPPLIER_PRICE_STABILITY.matcher(normalized).find()) {
            return true;
        }
        if (!ANALYSIS_HINT.matcher(normalized).find()) {
            return false;
        }
        int matchedDimensions = 0;
        for (Pattern dimension : DIMENSIONS) {
            if (dimension.matcher(normalized).find() && ++matchedDimensions >= 3) {
                return true;
            }
        }
        return false;
    }
}
