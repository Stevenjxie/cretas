package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import com.cretas.aims.ai.tool.impl.restaurant.gold.GoldBackedRestaurantTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared base for the Gold-backed review-analytics Tools (大众点评 评价下载 data).
 *
 * <p>Not a Spring bean (abstract). Provides the no-arg parameter schema common to
 * every review question plus small JSON-number coercion helpers used by each
 * {@code format()}.
 *
 * <p><b>factoryId 隔离豁免说明 (@FactoryIsolationExempt)</b>: 经
 * {@link GoldBackedRestaurantTool} 的 final {@code doExecute(factoryId)} 模板方法把
 * factoryId 传入 {@code queryGold(factoryId, ...)} → {@code GoldFinanceClient.fetchReviewX},
 * 每个查询都按 factory_id 租户隔离 (Python 端 smart_bi_dynamic_data RLS app.factory_id)。
 * 审计正则无法追踪模板方法 + final 修饰, 故显式豁免; 隔离实际由 factoryId 全程传递保证。
 */
public abstract class AbstractReviewGoldTool extends GoldBackedRestaurantTool {

    /** All review questions are self-resolving — no caller params. */
    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    /** JSON number → int (0 when absent / non-numeric). */
    protected static int intOf(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    /** JSON number → double (0.0 when absent / non-numeric). */
    protected static double dbl(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    /** Format a score/ratio to 2 decimals. */
    protected static String fmt2(double v) {
        return String.format("%.2f", v);
    }

    /** Safe cast of a Gold response field to a list of row maps. */
    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> listOfMaps(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // P1 conversational-depth helpers — suggestedFollowups / glossary / chartGuide
    // -------------------------------------------------------------------------

    /** Build one follow-up chip: {label (短，显示在按钮), question (点击后发送的查询)}. */
    protected static Map<String, Object> followup(String label, String question) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("question", question);
        return m;
    }

    /** Collect follow-up chips into a list (varargs convenience). */
    @SafeVarargs
    protected static List<Map<String, Object>> followups(Map<String, Object>... entries) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            if (e != null) {
                list.add(e);
            }
        }
        return list;
    }

    /** Build an ordered glossary map (term → 通俗定义). Pairs flattened: k1,v1,k2,v2,... */
    protected static Map<String, String> glossary(String... kv) {
        Map<String, String> g = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            g.put(kv[i], kv[i + 1]);
        }
        return g;
    }

    /**
     * Attach the three conversational-depth fields onto a tool result map.
     * Skips null / empty so empty-state results stay clean. Called at the end
     * of each concrete {@code format()}.
     */
    protected void attachDepth(
            Map<String, Object> result,
            List<Map<String, Object>> followups,
            Map<String, String> glossary,
            String chartGuide) {
        if (followups != null && !followups.isEmpty()) {
            result.put("suggestedFollowups", followups);
        }
        if (glossary != null && !glossary.isEmpty()) {
            result.put("glossary", glossary);
        }
        if (chartGuide != null && !chartGuide.isEmpty()) {
            result.put("chartGuide", chartGuide);
        }
    }
}
