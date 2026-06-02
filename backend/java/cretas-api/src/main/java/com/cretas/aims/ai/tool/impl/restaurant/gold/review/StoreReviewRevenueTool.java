package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import com.cretas.aims.ai.tool.impl.restaurant.gold.GoldBackedRestaurantTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P3 门店评分 × 营收 跨数据集关联(大众点评评分 × POS gold 营收, 经 dim_store_review_alias 桥)。
 * 适用意图: 评分高的店是不是更赚钱 / 门店评分与营收关系 / 口碑与营收相关性。
 *
 * <p>诚实标注内建: 仅已确认/高置信映射进 join; 必返未关联门店名单 + honest_note;
 * 关联门店 &lt;4 家时不给相关性结论(仅供参考)。营收字段经 Python RBAC 对非 price-view
 * 角色剥零(X-User-Role 转发), 评分保留。
 *
 * <p>不直接继承 AbstractReviewGoldTool —— 评价类工具忽略时间窗, 但本工具的营收半边需要
 * 时间窗(走 GoldBackedRestaurantTool.resolveWindow 默认取 factory 数据全区间)。
 */
@Slf4j
@Component
public class StoreReviewRevenueTool extends GoldBackedRestaurantTool {

    /** 默认 alias 进 join 的最低置信(admin 行不受限)。与 Python DEFAULT_MIN_CONFIDENCE 对齐。 */
    private static final double DEFAULT_MIN_CONFIDENCE = 0.90;

    @Override
    public String getToolName() {
        return "store_review_revenue";
    }

    @Override
    public String getDescription() {
        return "门店评分×营收关联(大众点评评分 × POS营收, 经门店别名桥)。适用: 评分高的店是不是更赚钱/"
                + "门店口碑与营收关系/评分营收相关性。仅含已确认门店映射, 诚实标注未关联门店, 不编造。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchStoreReviewRevenue(factoryId, start, end, DEFAULT_MIN_CONFIDENCE);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        // linked_count==0 仍是有价值的诚实回答(告诉老板需先确认映射), 不当空态 dead-end。
        // 仅当连评价门店都没有(total_review_stores==0)才算真空态。
        return intOf(g.get("total_review_stores")) == 0;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> linked = listOfMaps(g.get("linked_stores"));
        int linkedCount = intOf(g.get("linked_count"));
        int totalReview = intOf(g.get("total_review_stores"));
        int totalGold = intOf(g.get("total_gold_stores"));
        int unlinkedCount = intOf(g.get("unlinked_count"));
        Object unlinkedNames = g.get("unlinked_review_stores");
        Map<String, Object> correlation = g.get("correlation") instanceof Map
                ? (Map<String, Object>) g.get("correlation") : null;
        String honestNote = g.get("honest_note") != null ? g.get("honest_note").toString() : "";

        StringBuilder sb = new StringBuilder();
        if (linkedCount == 0) {
            sb.append("暂无可关联的门店评分×营收数据。\n")
                    .append("评价门店 ").append(totalReview).append(" 家, POS 营收门店 ")
                    .append(totalGold).append(" 家, 但 0 家已确认映射。\n");
            if (g.get("next_action") != null) {
                sb.append(g.get("next_action"));
            }
        } else {
            sb.append("门店评分 × 营收关联(已关联 ").append(linkedCount).append("/")
                    .append(totalReview).append(" 家评价门店, 营收由高到低)：\n");
            for (int i = 0; i < linked.size(); i++) {
                Map<String, Object> s = linked.get(i);
                sb.append(i + 1).append(". ").append(s.get("gold_store_name"))
                        .append(" — 评分 ").append(fmt2(dbl(s.get("avg_rating")))).append(" 星");
                Object rev = s.get("revenue");
                if (rev instanceof Number) {
                    sb.append("，营收 ").append(fmt2(toWan(dbl(rev)))).append(" 万元");
                } else {
                    sb.append("，营收(无权限查看)");
                }
                sb.append("（").append(intOf(s.get("review_count"))).append(" 条评价）");
                if (i < linked.size() - 1) {
                    sb.append("\n");
                }
            }
            if (correlation != null && correlation.get("value") != null) {
                sb.append("\n相关性: ").append(correlation.get("note"))
                        .append(" (r=").append(correlation.get("value"))
                        .append(", n=").append(correlation.get("n")).append(")");
            } else {
                sb.append("\n相关性: 关联门店不足 ").append(4).append(" 家, 暂不计算(样本无统计意义)。");
            }
        }
        sb.append("\n\n").append(honestNote);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataAvailable", true);
        result.put("linkedCount", linkedCount);
        result.put("unlinkedCount", unlinkedCount);
        result.put("unlinkedReviewStores", unlinkedNames);
        result.put("correlation", correlation);
        result.put("honestNote", honestNote);
        result.put("message", sb.toString());

        // 散点图: x=评分, y=营收(万元)。仅 price-view 可见营收时画。
        if (linkedCount > 0) {
            List<String> names = new ArrayList<>();
            List<Double> ratings = new ArrayList<>();
            List<Double> revenues = new ArrayList<>();
            boolean haveRevenue = false;
            for (Map<String, Object> s : linked) {
                names.add(String.valueOf(s.get("gold_store_name")));
                ratings.add(dbl(s.get("avg_rating")));
                Object rev = s.get("revenue");
                if (rev instanceof Number) {
                    revenues.add(toWan(((Number) rev).doubleValue()));
                    haveRevenue = true;
                } else {
                    revenues.add(0.0);
                }
            }
            if (haveRevenue) {
                result.put("chartConfig",
                        scatterRatingRevenue("门店评分 × 营收", names, ratings, revenues));
            }
        }
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无大众点评评价数据, 无法做评分×营收关联。请确认已上传大众点评'评价下载'报表。";
    }

    // ---- JSON 数值/列表 coercion 辅助 (本工具直接继承 GoldBackedRestaurantTool, 不含这些) ----

    /** JSON number → int (0 when absent / non-numeric). */
    private static int intOf(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    /** JSON number → double (0.0 when absent / non-numeric). */
    private static double dbl(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    /** Format to 2 decimals. */
    private static String fmt2(double v) {
        return String.format("%.2f", v);
    }

    /** Safe cast of a Gold response field to a list of row maps. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : Collections.emptyList();
    }

    /**
     * 评分(x) × 营收(y) 散点图 config。每个点带门店名 tooltip。
     */
    private static Map<String, Object> scatterRatingRevenue(
            String title, List<String> names, List<Double> ratings, List<Double> revenues) {
        List<Object> points = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            // [x=评分, y=营收(万元), 门店名]
            points.add(List.of(ratings.get(i), revenues.get(i), names.get(i)));
        }
        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("tooltip", Map.of("trigger", "item",
                "formatter", "{c}"));
        opt.put("grid", Map.of("left", "3%", "right", "6%",
                "bottom", "3%", "top", "8%", "containLabel", true));
        opt.put("xAxis", Map.of("type", "value", "name", "评分(星)", "scale", true));
        opt.put("yAxis", Map.of("type", "value", "name", "营收(万元)", "scale", true));

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("type", "scatter");
        series.put("symbolSize", 14);
        series.put("data", points);
        series.put("itemStyle", Map.of("color", "#5470c6"));
        opt.put("series", List.of(series));

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "scatter");
        cfg.put("title", title);
        cfg.put("option", opt);
        return cfg;
    }
}
