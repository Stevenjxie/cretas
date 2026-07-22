package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.impl.restaurant.diagnostic.RestaurantCostRigidityAnalysisTool;
import com.cretas.aims.ai.tool.impl.restaurant.diagnostic.RestaurantShrinkageAnalysisTool;
import com.cretas.aims.ai.tool.impl.restaurant.diagnostic.RestaurantStorePnlOnePagerTool;
import com.cretas.aims.ai.dto.ToolCall;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprint 11 — Restaurant Economics Composite Tool (MealClaw Response).
 *
 * <p>"一句话查损益 + 找根因 + 给建议" 编排 Tool — 串联 3 个 sub-Tool:
 * <ol>
 *   <li>{@link RestaurantStorePnlOnePagerTool} — 单店 P&L 一页纸 (诊断 summary)</li>
 *   <li>{@link RestaurantShrinkageAnalysisTool} — 档口损溢找根因 (root causes)</li>
 *   <li>{@link RestaurantCostRigidityAnalysisTool} — 成本刚性诊断 (recommendations)</li>
 * </ol>
 *
 * <p><b>Steve 决策 1 (per brief §2.5)</b>: 任一 sub-Tool 失败 →
 * {@code result.dataAvailable = false} + {@code result.message = "X 数据获取失败"}.
 * 失败 Tool 的数据不进 narrative; 其他维度照说; LLM prompt 必须明确标注"X 数据不可用".
 *
 * <p>LLM 输出 wrapping 由 {@link com.cretas.aims.ai.tool.util.RestaurantEconomicsOutputFormatter}
 * 负责 (whitelist 字段 strip metadata, 防 leak).
 *
 * <p>Activated by intent {@code RESTAURANT_ECONOMICS_ANALYSIS} (per V20260522_50 migration).
 */
@Slf4j
@Component
public class RestaurantEconomicsAnalysisTool extends AbstractBusinessTool {

    /**
     * Absolute "YYYY年M月" / "YYYY-MM" / "YYYY/M月" found ANYWHERE in the NL query
     * (search, not full-match — the query carries trailing text like "哪个菜亏钱").
     * Month is constrained to 1-12 to avoid matching unrelated digit runs.
     */
    private static final Pattern NL_ABSOLUTE_MONTH = Pattern.compile(
            "(\\d{4})\\s*[年/\\-]\\s*(1[0-2]|0?[1-9])\\s*月?");

    private final RestaurantStorePnlOnePagerTool storePnlTool;
    private final RestaurantShrinkageAnalysisTool shrinkageTool;
    private final RestaurantCostRigidityAnalysisTool costRigidityTool;

    @Autowired
    private TieredIntentDelegate tieredDelegate;

    @Autowired
    public RestaurantEconomicsAnalysisTool(
            RestaurantStorePnlOnePagerTool storePnlTool,
            RestaurantShrinkageAnalysisTool shrinkageTool,
            RestaurantCostRigidityAnalysisTool costRigidityTool) {
        this.storePnlTool = storePnlTool;
        this.shrinkageTool = shrinkageTool;
        this.costRigidityTool = costRigidityTool;
    }

    @Override
    public String getToolName() {
        return "restaurant_economics_analysis";
    }

    @Override
    public String getDescription() {
        return "餐厅经营分析 - 一句话查损益 + 找根因 + 给建议. 编排单店 P&L 一页纸 + 档口损溢 + 成本刚性 3 项诊断, "
             + "失败 sub-Tool 标注 'X 数据不可用' 不编故事. 适用场景: 客户问"
             + "'帮我看上月损溢异常'/'损溢分析'/'成本分析'/'哪个菜亏钱'.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> subSector = new HashMap<>();
        subSector.put("type", "string");
        subSector.put("description", "餐饮子行业, 如火锅/川菜/烧烤/西餐/日料. 默认火锅");
        properties.put("sub_sector", subSector);

        Map<String, Object> storeId = new HashMap<>();
        storeId.put("type", "string");
        storeId.put("description", "门店 ID, 可选");
        properties.put("store_id", storeId);

        Map<String, Object> uploadId = new HashMap<>();
        uploadId.put("type", "string");
        uploadId.put("description", "数据上传 ID, 可选");
        properties.put("upload_id", uploadId);

        Map<String, Object> month = new HashMap<>();
        month.put("type", "string");
        month.put("description", "分析月份, 支持 '上月' / '本月' / 'YYYY-MM' / 'YYYY年M月'. "
                + "客户问历史月份 (如 '2025年12月哪个菜亏钱') 时必须传该月份, 不传默认 '上月'");
        properties.put("month", month);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) {
        Map<String, Object> delegated = tieredDelegate.tryDelegate(factoryId, params, context, getToolName());
        if (delegated != null) {
            return delegated;
        }

        log.info("RestaurantEconomicsAnalysisTool invoked — factory: {}, params keys: {}",
                 factoryId, params.keySet());

        // Build sub-Tool dispatch params (forward original params to each sub-Tool)
        Map<String, Object> subParams = new HashMap<>(params);

        // Sprint 12 (#302): resolve a single analysis period and forward it to ALL 3
        // sub-Tools (each reads the "month" param), so a historical query like
        // "2025年12月哪个菜亏钱" scopes to that month instead of silently defaulting to
        // 上月. Without this, the composite schema never exposed "month" and
        // TimeNormalizationRules has no absolute "YYYY年MM月" rule, so the owner asking
        // about a past month got near-empty current-ish data.
        String resolvedMonth = resolveCompositeMonth(params);
        if (resolvedMonth != null) {
            subParams.put("month", resolvedMonth);
            log.info("RestaurantEconomicsAnalysisTool: resolved analysis period month='{}'", resolvedMonth);
        }

        // Invoke 3 sub-Tools, collect status per Steve 决策 1 (failures isolated, no fabrication)
        SubToolOutcome pnlOutcome = invokeSubTool(
                "storePnl", storePnlTool, factoryId, subParams, context);
        SubToolOutcome shrinkageOutcome = invokeSubTool(
                "shrinkage", shrinkageTool, factoryId, subParams, context);
        SubToolOutcome costRigidityOutcome = invokeSubTool(
                "costRigidity", costRigidityTool, factoryId, subParams, context);

        // Compose final result (Map structure, LLM-friendly)
        Map<String, Object> result = new LinkedHashMap<>();

        // Sprint 13 (S13-001): top-level dataAvailable reflects PARTIAL availability per
        // Steve 决策 1 ("失败 sub-Tool 不进 narrative; 其他维度照说"). Previously this was
        // pnl && shrinkage && costRigidity, so a single scoped-out sub-Tool (e.g.
        // cost_rigidity has no data for the period) forced the WHOLE result to
        // dataAvailable=false — a consumer keying on the top-level flag would then hide the
        // ¥ P&L even though financial/shrinkage data existed. Now: ANY dimension with data
        // → dataAvailable=true; failed dimensions stay marked unavailable in their own
        // section + listed in the message, without dragging the whole result down.
        // (allDataAvailable retained for the message branch so the verified partial-
        // degradation template stays byte-identical.)
        boolean allDataAvailable = pnlOutcome.dataAvailable
                && shrinkageOutcome.dataAvailable
                && costRigidityOutcome.dataAvailable;
        boolean anyDataAvailable = pnlOutcome.dataAvailable
                || shrinkageOutcome.dataAvailable
                || costRigidityOutcome.dataAvailable;
        result.put("dataAvailable", anyDataAvailable);

        // summary — from store_pnl_one_pager (if available)
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", "store_pnl_one_pager");
        summary.put("dataAvailable", pnlOutcome.dataAvailable);
        if (pnlOutcome.dataAvailable) {
            summary.put("data", pnlOutcome.data);
        } else {
            summary.put("message", "P&L 一页纸数据获取失败: " + pnlOutcome.errorMessage);
        }
        result.put("summary", summary);

        // topItems — from shrinkage_analysis (most lossy items)
        Map<String, Object> topItems = new LinkedHashMap<>();
        topItems.put("source", "shrinkage_analysis");
        topItems.put("dataAvailable", shrinkageOutcome.dataAvailable);
        if (shrinkageOutcome.dataAvailable) {
            topItems.put("data", shrinkageOutcome.data);
        } else {
            topItems.put("message", "档口损溢数据获取失败: " + shrinkageOutcome.errorMessage);
        }
        result.put("topItems", topItems);

        // recommendations — from cost_rigidity (improvement suggestions)
        Map<String, Object> recommendations = new LinkedHashMap<>();
        recommendations.put("source", "cost_rigidity");
        recommendations.put("dataAvailable", costRigidityOutcome.dataAvailable);
        if (costRigidityOutcome.dataAvailable) {
            recommendations.put("data", costRigidityOutcome.data);
        } else {
            recommendations.put("message", "成本刚性数据获取失败: " + costRigidityOutcome.errorMessage);
        }
        result.put("recommendations", recommendations);

        // evidence — aggregated warnings + sub-Tool sections
        List<Map<String, Object>> evidence = new ArrayList<>();
        addEvidence(evidence, "store_pnl_one_pager", pnlOutcome);
        addEvidence(evidence, "shrinkage_analysis", shrinkageOutcome);
        addEvidence(evidence, "cost_rigidity", costRigidityOutcome);
        result.put("evidence", evidence);

        // Top-level message (LLM hint). Three cases:
        //  - all available  → 全维度可用
        //  - partial (some) → 部分数据不可用 (byte-identical to the pre-S13-001 template,
        //                     which the Sprint-12 verify report matched char-for-char) —
        //                     analysis IS produced from the available dimensions.
        //  - none available → 均无数据 (distinct from partial; dataAvailable is now false
        //                     only in this case).
        if (allDataAvailable) {
            result.put("message", "餐厅经营分析完成: 损益 + 找根因 + 建议 全维度可用.");
        } else if (anyDataAvailable) {
            List<String> failed = new ArrayList<>();
            if (!pnlOutcome.dataAvailable) failed.add("P&L 一页纸");
            if (!shrinkageOutcome.dataAvailable) failed.add("档口损溢");
            if (!costRigidityOutcome.dataAvailable) failed.add("成本刚性");
            result.put("message", "部分数据不可用: " + String.join(" / ", failed)
                    + ". 已基于可用数据生成分析, 不可用部分需明确标注.");
        } else {
            // 防呆 Rule 5: 不要只说"均无数据"(死胡同), 解释原因 + 给 next-action.
            // 经营分析需要财务/经营数据 (营业额/成本/损耗); 常见原因是当前数据源是
            // 评价/客户等非财务数据, 或本工厂尚未上传经营报表。
            result.put("message", "餐厅经营分析暂不可用: 本期未找到损益 / 档口损溢 / 成本刚性数据。"
                    + "该分析需要经营/财务数据 (营业额、成本、损耗) — 当前数据源可能是评价或非财务数据。"
                    + "请在「智能分析 - Excel上传」上传含营业额与成本的经营报表后重试。");
            result.put("actionHint", "前往「智能分析 - Excel上传」上传含营业额/成本的经营报表");
        }

        return result;
    }

    /**
     * Resolve the analysis period (as a "month" label the sub-Tool fetchers understand)
     * from the available signals, so all 3 sub-Tools scope to the SAME period.
     * Precedence:
     * <ol>
     *   <li>explicit {@code month} param (LLM-extracted from the schema / caller-supplied)</li>
     *   <li>preprocessed {@code startDate} (ISO {@code yyyy-MM-dd} from TimeNormalization) → {@code yyyy-MM}</li>
     *   <li>absolute "YYYY年M月" / "YYYY-MM" parsed from the raw NL query ({@code userInput})</li>
     *   <li>relative "本月" / "上月" mentioned in the NL query</li>
     * </ol>
     * Returns {@code null} when no period signal exists — the sub-Tools then apply their
     * own default ("上月"). The downstream fetchers
     * ({@code RestaurantFinancialMetricsFetcher} / {@code RestaurantShrinkageDataFetcher})
     * accept both {@code yyyy-MM} and the "上月"/"本月" labels.
     */
    String resolveCompositeMonth(Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        // 1) Explicit month param wins (LLM extraction via schema, or caller-supplied).
        Object explicit = params.get("month");
        if (explicit instanceof String && !((String) explicit).trim().isEmpty()) {
            return ((String) explicit).trim();
        }
        // 2) Preprocessed startDate (ISO yyyy-MM-dd) → yyyy-MM. Set by ToolDispatchService
        //    step 2.6 when TimeNormalizationRules matched a RELATIVE phrase.
        Object startDate = params.get("startDate");
        if (startDate instanceof String && ((String) startDate).length() >= 7) {
            return ((String) startDate).substring(0, 7);
        }
        // 3) Parse the raw NL query for an absolute / relative month reference.
        Object userInput = params.get("userInput");
        if (userInput instanceof String) {
            String q = (String) userInput;
            Matcher m = NL_ABSOLUTE_MONTH.matcher(q);
            if (m.find()) {
                int year = Integer.parseInt(m.group(1));
                int monthNum = Integer.parseInt(m.group(2));
                return String.format(Locale.ROOT, "%04d-%02d", year, monthNum);
            }
            if (q.contains("本月") || q.contains("这个月") || q.contains("当月")) {
                return "本月";
            }
            if (q.contains("上月") || q.contains("上个月")) {
                return "上月";
            }
        }
        return null;
    }

    /**
     * Invoke one sub-Tool and collect outcome. Failure NEVER throws — isolated to outcome flag.
     * Steve 决策 1: 失败 sub-Tool 数据不进 narrative, 但其他维度继续.
     */
    private SubToolOutcome invokeSubTool(
            String label,
            AbstractBusinessTool subTool,
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) {
        SubToolOutcome outcome = new SubToolOutcome();
        try {
            // Build synthetic ToolCall to invoke sub-Tool via standard pipeline
            ToolCall syntheticCall = buildSyntheticToolCall(subTool.getToolName(), params);

            // Ensure context has minimum required keys (defensive — caller may pass partial)
            Map<String, Object> safeContext = new HashMap<>(context);
            safeContext.putIfAbsent("factoryId", factoryId);
            safeContext.putIfAbsent("userId", context.get("userId"));

            String responseJson = subTool.execute(syntheticCall, safeContext);
            Map<String, Object> parsed = objectMapper.readValue(
                    responseJson, new TypeReference<Map<String, Object>>() {});

            Boolean success = (Boolean) parsed.get("success");
            if (Boolean.TRUE.equals(success)) {
                // sub-Tool wrapper puts data under "data" key (per buildSuccessResult)
                Object dataNode = parsed.get("data");
                if (dataNode instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dataMap = (Map<String, Object>) dataNode;
                    // Inner Tool format: { success, section, data, warnings, followUpChips }
                    // Check inner success (AbstractRestaurantDiagnosticTool returns success=false on Python skip)
                    Boolean innerSuccess = (Boolean) dataMap.get("success");
                    if (Boolean.FALSE.equals(innerSuccess)) {
                        outcome.dataAvailable = false;
                        outcome.errorMessage = Objects.toString(dataMap.get("message"), label + " 数据不足");
                        outcome.data = Collections.emptyMap();
                        return outcome;
                    }
                    outcome.dataAvailable = true;
                    outcome.data = dataMap;
                    Object warnings = dataMap.get("warnings");
                    if (warnings instanceof List<?>) {
                        for (Object w : (List<?>) warnings) {
                            outcome.warnings.add(Objects.toString(w, ""));
                        }
                    }
                    return outcome;
                }
                outcome.dataAvailable = true;
                outcome.data = dataNode != null ? dataNode : Collections.emptyMap();
                return outcome;
            }
            // success=false at outer wrapper
            outcome.dataAvailable = false;
            outcome.errorMessage = Objects.toString(
                    parsed.getOrDefault("error", parsed.get("message")),
                    label + " 调用失败");
            outcome.data = Collections.emptyMap();
            return outcome;

        } catch (Exception ex) {
            log.warn("Composite sub-Tool {} 调用异常: {}", label, ex.getMessage(), ex);
            outcome.dataAvailable = false;
            outcome.errorMessage = label + " 调用异常: " + ex.getMessage();
            outcome.data = Collections.emptyMap();
            return outcome;
        }
    }

    /**
     * Build a synthetic ToolCall to drive sub-Tool execute() pipeline.
     * The composite Tool itself was invoked with its own ToolCall, but we
     * need to re-shape parameters for each sub-Tool. ToolCall accepts
     * arguments as JSON string (per ToolCall.Function.arguments contract).
     */
    private ToolCall buildSyntheticToolCall(String toolName, Map<String, Object> params) throws Exception {
        String argumentsJson = objectMapper.writeValueAsString(params);
        return ToolCall.of(
                "composite-" + toolName + "-" + System.nanoTime(),
                toolName,
                argumentsJson);
    }

    private void addEvidence(List<Map<String, Object>> evidence, String section, SubToolOutcome outcome) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("section", section);
        entry.put("dataAvailable", outcome.dataAvailable);
        if (!outcome.warnings.isEmpty()) {
            entry.put("warnings", outcome.warnings);
        }
        if (!outcome.dataAvailable && outcome.errorMessage != null) {
            entry.put("error", outcome.errorMessage);
        }
        evidence.add(entry);
    }

    /** Internal struct capturing one sub-Tool's result/failure state. */
    static class SubToolOutcome {
        boolean dataAvailable = false;
        Object data = Collections.emptyMap();
        String errorMessage = null;
        final List<String> warnings = new ArrayList<>();
    }
}
