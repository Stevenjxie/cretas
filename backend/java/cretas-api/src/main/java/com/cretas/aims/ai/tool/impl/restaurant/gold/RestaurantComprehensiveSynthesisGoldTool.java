package com.cretas.aims.ai.tool.impl.restaurant.gold;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2 综合分析工具 (multi-dim synthesis) — 把自由的"综合/多维"问题路由进 Python 合成引擎。
 *
 * <p>不同于其它 Gold 工具 (单一确定性查询 → format)，本工具调用 Python
 * {@code POST /api/smartbi/synthesis/comprehensive}：引擎汇总 评价(P1) + 财务/销售(gold)
 * 成 FactBook → InsightDimensionAnalyzer 结构化相关 → call_chain LLM grounded 叙述
 * (出境脱敏在 Python 共享客户端层) → FactReconciler 硬对账 → 多张图。
 *
 * <p>适用意图：综合分析 / 整体分析 / 经营和评价 / 多维分析 / VIP和菜品 / 综合诊断…
 * (避开"怎么样"模糊问 — 已知歧义查询局限)。
 *
 * <p>窗口：经 {@code GoldBackedRestaurantTool.resolveWindow} 解析 (NL 月份 / Gold 数据范围),
 * 把 [start,end] 传给 Python；Python 端 review 聚合忽略窗口 (评价无日期维度)，finance/sales
 * 用窗口。问题原文从 {@code params.userInput} 取 (LLM 抽取的 query)。
 *
 * @since 2026-06-02
 */
@Slf4j
@Component
/**
 * factoryId 隔离豁免说明 (@FactoryIsolationExempt): 经 GoldBackedRestaurantTool 的 final
 * doExecute(factoryId) 模板方法把 factoryId 传入 queryGold(factoryId, ...) →
 * GoldFinanceClient.fetchComprehensiveSynthesis(factoryId, ...)，Python 合成引擎按 factory_id
 * 租户隔离 (gold _resolve_tenant + 评价 RLS app.factory_id)。审计正则无法追踪模板方法 + final
 * 修饰，故显式豁免；隔离实际由 factoryId 全程传递保证。
 */
public class RestaurantComprehensiveSynthesisGoldTool extends GoldBackedRestaurantTool {

    @Override
    public String getToolName() {
        return "restaurant_comprehensive_synthesis_gold";
    }

    @Override
    public String getDescription() {
        return "综合多维分析(评价+经营+销售合成, gold/P1)。适用: 综合分析/整体分析/经营和评价/"
                + "多维分析/VIP和菜品门店关系/综合诊断/全面分析。一次返回多维度结论+交叉关系+建议+多张图。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> monthProp = new LinkedHashMap<>();
        monthProp.put("type", "string");
        monthProp.put("description", "分析月份, 如 '2026年3月' / '上月', 不传默认全部数据");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("month", monthProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    /**
     * This tool is itself the multi-dimension synthesis owner. Delegating it
     * back to the single-intent restaurant planner creates a loop that reduces
     * comprehensive questions to one metric or a generic clarification.
     */
    @Override
    protected boolean shouldDelegateToTieredIntent(String userInput) {
        return false;
    }

    /**
     * Keep the caller tenant for synthesis so Python can select the correct
     * fact tenant per dimension. The public demo uses the full POS alias for
     * sales and reviews, but its seeded procurement and external-dimension
     * facts remain under {@code DEMO_REST}.
     */
    @Override
    protected String resolveGoldFactoryId(String factoryId) {
        return factoryId;
    }

    /**
     * Resolve the time window from the complete POS tenant while preserving
     * the original demo tenant for the synthesis request itself.
     */
    @Override
    protected LocalDate[] resolveWindow(String factoryId, Map<String, Object> params) {
        return super.resolveWindow(super.resolveGoldFactoryId(factoryId), params);
    }

    // getRequiredParameters() inherited — returns empty list.

    /**
     * Resolve the user's free-form question for the synthesis engine.
     * Prefers the raw NL query (userInput) so the engine plans dimensions from
     * the original phrasing; falls back to a sensible default.
     */
    private String resolveQuestion(Map<String, Object> params) {
        String userInput = getString(params, "userInput");
        if (userInput != null && !userInput.trim().isEmpty()) {
            return userInput.trim();
        }
        String query = getString(params, "query");
        if (query != null && !query.trim().isEmpty()) {
            return query.trim();
        }
        return "综合分析本店的评价与经营情况";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        String question = resolveQuestion(params);
        return gold.fetchComprehensiveSynthesis(factoryId, question, start, end);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> format(Map<String, Object> goldResult) {
        // The Python synthesis engine already returns a grounded, fact-checked,
        // redaction-restored answer + chart configs. We pass them through to the
        // AIChat result envelope: "message" is the readable answer; "charts"
        // carries the ECharts-option list (FE AIQuery renderer handles it).
        Object answerObj = goldResult.get("answer");
        String answer = answerObj != null ? answerObj.toString() : "";

        List<Map<String, Object>> charts =
                goldResult.get("charts") instanceof List
                        ? (List<Map<String, Object>>) goldResult.get("charts")
                        : Collections.emptyList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataAvailable", true);
        // AIChat renders "message" as the answer body — must be non-empty + readable.
        result.put("message", answer.isEmpty()
                ? "已综合分析评价与经营数据。" : answer);
        if (!charts.isEmpty()) {
            result.put("charts", new ArrayList<>(charts));
        }
        // Pass through useful synthesis metadata for the FE / observability.
        if (goldResult.get("plan") != null) {
            result.put("plan", goldResult.get("plan"));
        }
        if (goldResult.get("fact_check") != null) {
            result.put("factCheck", goldResult.get("fact_check"));
        }
        if (goldResult.get("source") != null) {
            result.put("source", goldResult.get("source"));
        }
        return result;
    }

    @Override
    protected boolean isEmpty(Map<String, Object> goldResult) {
        // Synthesis degrades to a (non-empty) answer even when data is sparse
        // (it appends a next-action note rather than returning blank). Treat the
        // result as empty only when there is no answer text at all AND no charts.
        Object answer = goldResult.get("answer");
        boolean noAnswer = answer == null || answer.toString().trim().isEmpty();
        Object charts = goldResult.get("charts");
        boolean noCharts = !(charts instanceof List) || ((List<?>) charts).isEmpty();
        return noAnswer && noCharts;
    }

    @Override
    protected String emptyMessage() {
        return "暂无足够的评价或经营数据做综合分析。请确认已上传含营业额/菜品销量的经营报表，"
                + "或上传大众点评/美团评价导出以补充口碑维度。";
    }
}
