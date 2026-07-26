package com.cretas.aims.ai.tool.impl.restaurant.gold;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.client.GoldFinanceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abstract base for Gold-backed restaurant analytics Tools.
 *
 * <p>Eliminates 8× boilerplate across the concrete Gold restaurant Tools
 * (finance-summary, daily-trend, top-products, slow-sellers, order-type-mix,
 * staff-ranking, discount-breakdown, channel-breakdown) by centralising:
 * <ol>
 *   <li><b>Time-window resolution</b> — parses NL month from the user query, then
 *       falls back to the factory's actual data range from Gold, then falls back to a
 *       12-month trailing window. NEVER uses a "last 7 days from today" window.</li>
 *   <li><b>Gold client call dispatch</b> — delegates to the abstract
 *       {@link #queryGold(String, LocalDate, LocalDate, Map)} method.</li>
 *   <li><b>Fool-proof empty / error degradation</b> — per
 *       {@code fool-proof-design.md} Rule 5: errors and empty results produce an
 *       actionable message, never a dead-end "暂无数据".</li>
 * </ol>
 *
 * <p><b>Not a Spring bean</b> — abstract, so Spring will not register it.
 * Only the concrete {@code @Component} subclasses in this package are beans.
 *
 * <p><b>NL month parsing</b>: copied verbatim from
 * {@link com.cretas.aims.ai.tool.impl.restaurant.RestaurantEconomicsAnalysisTool}
 * (single source of truth). Accepts:
 * <ul>
 *   <li>{@code YYYY年M月} / {@code YYYY-MM} / {@code YYYY/M月}</li>
 *   <li>Relative: {@code 本月}, {@code 这个月}, {@code 当月}, {@code 上月}, {@code 上个月}</li>
 * </ul>
 *
 * <p><b>Data-range response keys</b> (from Python Gold {@code data_range}):
 * {@code min_date}, {@code max_date} (ISO {@code yyyy-MM-dd} strings, nullable),
 * {@code day_count} (int).
 *
 * @since 2026-06-01
 */
@Slf4j
/**
 * factoryId 隔离豁免说明 (@FactoryIsolationExempt): 本类经 GoldBackedRestaurantTool
 * 的 final doExecute(factoryId) 模板方法把 factoryId 传入 queryGold(factoryId, ...)
 * → GoldFinanceClient.fetchX(factoryId, ...), 每个 gold 查询都按 factory_id 租户隔离
 * (Python gold 层 _resolve_tenant)。审计正则无法追踪模板方法 + final 修饰, 故显式豁免;
 * 隔离实际由 factoryId 全程传递保证。
 */
public abstract class GoldBackedRestaurantTool extends AbstractBusinessTool {

    /**
     * Absolute "YYYY年M月" / "YYYY-MM" / "YYYY/M月" found ANYWHERE in the NL query.
     * Copied from RestaurantEconomicsAnalysisTool (NL_ABSOLUTE_MONTH).
     * Month is constrained to 1-12 to avoid matching unrelated digit runs.
     */
    private static final Pattern NL_ABSOLUTE_MONTH = Pattern.compile(
            "(\\d{4})\\s*[年/\\-]\\s*(1[0-2]|0?[1-9])\\s*月?");

    /** Shared Gold client — injected by Spring into each concrete subclass. */
    @Autowired
    protected GoldFinanceClient gold;

    // =========================================================================
    // Abstract contract for subclasses
    // =========================================================================

    /**
     * Perform the actual Gold data fetch for the resolved window.
     *
     * @param factoryId tenant id
     * @param start     inclusive start date (never null — resolveWindow guarantees this)
     * @param end       inclusive end date (never null)
     * @param params    original Tool params (subclasses may read additional fields
     *                  such as {@code top_n}, {@code order})
     * @return raw Gold response map; may be empty but never null
     * @throws Exception on transport failure or parse error; template method catches and
     *                   returns a "服务暂时不可用" result
     */
    protected abstract Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception;

    /**
     * Shape the Gold response into an LLM-friendly answer map.
     *
     * @param goldResult raw map returned by {@link #queryGold}; already checked non-null + non-empty
     * @return the structured result to return from {@code doExecute}
     */
    protected abstract Map<String, Object> format(Map<String, Object> goldResult);

    /**
     * Decide whether the Gold result contains no usable rows.
     *
     * @param goldResult raw map; already checked non-null
     * @return {@code true} when the result carries no meaningful data (e.g. empty lists,
     *         zero total_revenue with zero bill_count)
     */
    protected abstract boolean isEmpty(Map<String, Object> goldResult);

    /**
     * Fool-proof empty message per {@code fool-proof-design.md} Rule 5.
     *
     * <p>Must explain WHY data might be absent AND give a concrete next action.
     * NEVER return a bare "暂无数据" dead-end.
     *
     * @return human-readable message; shown to the LLM as {@code result.message}
     */
    protected abstract String emptyMessage();

    // =========================================================================
    // Template method — doExecute
    // =========================================================================

    /**
     * Template method that wires resolveWindow → queryGold → isEmpty → format.
     * Subclasses implement the four abstract hooks above; they do NOT override this method.
     */
    @Override
    protected final Map<String, Object> doExecute(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        // 0. Phase 2 delegate gate (2026-07-07 design:
        // docs/superpowers/specs/2026-07-07-restaurant-intent-phase2-java-entry-design.md).
        // Ask Python's tiered (T1/T2/T3) restaurant-intent router + Answer
        // Contract whether THIS query needs that path (profitability/margin
        // verdicts, relative-window ops summaries) instead of this Tool's own
        // resolveWindow -> queryGold -> format flow below. Uses the RAW
        // factoryId (not resolveGoldFactoryId'd — see tryDelegateToTieredIntent
        // javadoc). Any miss/exception here falls straight through to the
        // unchanged original flow.
        String userInput = getString(params, "userInput");
        if (userInput == null || userInput.isBlank()) {
            // LLM tool-calls often omit userInput from params (they only fill
            // schema slots such as days/month), which silently skipped the
            // delegate gate and served the all-history default for windowed
            // questions like "过去一个月营业额". The orchestrator always puts
            // the original request into the tool context — recover it there.
            Object requestObj = context != null ? context.get("request") : null;
            if (requestObj instanceof com.cretas.aims.dto.ai.IntentExecuteRequest req) {
                userInput = req.getUserInput();
            }
        }
        Map<String, Object> effectiveParams = params;
        if (userInput != null && !userInput.isBlank()
                && (getString(params, "userInput") == null
                || getString(params, "userInput").isBlank())) {
            // The raw query recovered from context must also participate in
            // deterministic time parsing and output formatting. Previously it
            // was used only by the delegate gate, so a locally owned Gold tool
            // could still fall back to the tenant's entire data range.
            effectiveParams = new HashMap<>(params);
            effectiveParams.put("userInput", userInput);
        }

        Map<String, Object> preQueryResult = beforeGoldQuery(
                userInput, effectiveParams, context);
        if (preQueryResult != null) {
            return preQueryResult;
        }

        if (userInput != null && !userInput.isBlank() && shouldDelegateToTieredIntent(userInput)) {
            Map<String, Object> delegated = tryDelegateToTieredIntent(
                    factoryId, userInput, extractSessionId(context));
            if (delegated != null) {
                return delegated;
            }
        }

        String goldFactoryId = resolveGoldFactoryId(factoryId);

        // 1. Resolve the analysis window
        LocalDate[] win = resolveWindow(goldFactoryId, effectiveParams);

        // 2. Call Gold — isolate failures
        Map<String, Object> g;
        try {
            g = queryGold(goldFactoryId, win[0], win[1], effectiveParams);
        } catch (Exception ex) {
            log.warn("[{}] Gold call failed factory={} range={}..{}: {}",
                    getToolName(), goldFactoryId, win[0], win[1], ex.getMessage());
            Map<String, Object> errResult = new HashMap<>();
            errResult.put("dataAvailable", false);
            errResult.put("message", "数据服务暂时不可用，请稍后重试。");
            return errResult;
        }

        // 3. Empty guard (fool-proof Rule 5 — no dead-end)
        if (g == null || isEmpty(g)) {
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("dataAvailable", false);
            emptyResult.put("message", emptyMessage());
            emptyResult.put("actionHint",
                    "前往「智能分析 - Excel上传」上传含营业额/菜品销量的经营报表");
            return emptyResult;
        }

        // 4. Format and return. The restaurant demo is used by operators who need
        // next actions, not just rankings, so keep every Gold answer actionable.
        return ensureActionableMessage(format(g));
    }

    /**
     * Lets a concrete Gold tool retain deterministic ownership of queries that
     * already have an exact local answer contract. Most tools should continue
     * to use the Python tiered router, so the default remains enabled.
     */
    protected boolean shouldDelegateToTieredIntent(String userInput) {
        return true;
    }

    /**
     * Optional deterministic guard that runs after the raw query has been
     * recovered from the request context but before any Python/LLM delegation
     * or Gold query.
     *
     * <p>Return a complete Tool result to short-circuit execution, or
     * {@code null} to continue. Concrete tools should use this only for
     * unambiguous contract checks such as a missing time window; fuzzy intent
     * decisions still belong to the tiered router.
     */
    protected Map<String, Object> beforeGoldQuery(
            String userInput,
            Map<String, Object> params,
            Map<String, Object> context) {
        return null;
    }

    /**
     * Phase 2 delegate gate (2026-07-07 design section 2/4): ask
     * {@link GoldFinanceClient#fetchTieredIntentAnswer} whether {@code
     * userInput} should be answered by Python's tiered restaurant-intent
     * router (T1/T2/T3 + Answer Contract) instead of this Tool's own Gold
     * flow, and if so, map the Python response into a Tool result.
     *
     * <p>Uses the <b>raw</b> {@code factoryId} — deliberately NOT passed
     * through {@link #resolveGoldFactoryId} (the DEMO_REST → RES_3101_009
     * alias). The Python restaurant-intent path already handles DEMO_REST
     * directly against its own seeded Gold data (V20260706_01 migration),
     * matching chat.py's existing {@code factory_id_hdr} usage at its 3 SSE
     * call sites — aliasing here would look up the wrong tenant's
     * restaurant-ops rows.
     *
     * <p>Never throws: {@link GoldFinanceClient#fetchTieredIntentAnswer}
     * itself never throws (returns {@code null} on any HTTP failure), but
     * this method still wraps the whole decision in a defensive try/catch
     * per design section 4 ("catch Exception 必须 log.warn 后 fall
     * through，绝不向上抛") in case a caller bug throws something unrelated
     * to the HTTP call (e.g. malformed response map access).
     *
     * @param factoryId raw tenant id (not gold-resolved)
     * @param userInput the user's free-form question (already checked
     *                  non-blank by the caller)
     * @param sessionId caller's chat session id, or {@code null} when
     *                  unavailable (see {@link #extractSessionId}) —
     *                  forwarded so a multi-turn answer to a PREVIOUS
     *                  clarification question is matched back to it
     *                  (2026-07-08 clarification-loop v1). {@code null} is
     *                  a no-op: the 3-arg {@link GoldFinanceClient#fetchTieredIntentAnswer}
     *                  overload runs exactly as it did before this
     *                  parameter existed.
     * @return a Tool result map when the Python side delegated (either a
     *         full answer or a clarification), or {@code null} when Java
     *         should proceed with its own {@code resolveWindow -> queryGold
     *         -> format} flow (no delegation, or any failure along the way)
     */
    private Map<String, Object> tryDelegateToTieredIntent(
            String factoryId, String userInput, String sessionId) {
        try {
            Map<String, Object> response = (sessionId != null && !sessionId.isBlank())
                    ? gold.fetchTieredIntentAnswer(factoryId, userInput, getToolName(), sessionId)
                    : gold.fetchTieredIntentAnswer(factoryId, userInput, getToolName());
            if (response == null || !Boolean.TRUE.equals(response.get("delegate"))) {
                return null;
            }
            Object answerObj = response.get("answer_text");
            String answerText = answerObj != null ? answerObj.toString() : null;
            if (answerText == null || answerText.isBlank()) {
                log.warn("[{}] tiered-intent delegate response missing answer_text, falling through factory={}",
                        getToolName(), factoryId);
                return null;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dataAvailable", true);
            result.put("message", answerText);
            result.put("tieredDelegate", true);
            if (response.get("suggested_followups") != null) {
                result.put("suggestedFollowups", response.get("suggested_followups"));
            }
            if (response.get("structured_context") != null) {
                result.put("conversationContext", response.get("structured_context"));
            }
            if (response.get("warning") != null) {
                result.put("warning", response.get("warning"));
            }

            boolean isClarification = "clarification".equals(response.get("kind"));
            if (isClarification) {
                // A clarifying question is not an "actionable report" moment —
                // ensureActionableMessage's owner-action framing (forced
                // "建议：..." advice text, decisionBridge, suggestedFollowups)
                // would bolt unrelated tool-specific advice onto a question
                // that is itself asking the user for more detail. Return the
                // clarification message as-is (fool-proof-design.md: context
                // must make sense to the reader).
                return result;
            }

            result.put("charts", response.getOrDefault("charts", Collections.emptyList()));
            result.put("kpis", response.getOrDefault("kpis", Collections.emptyList()));
            if (response.get("code") != null) {
                result.put("code", response.get("code"));
            }
            if (response.get("contract_pass") != null) {
                result.put("contractPass", response.get("contract_pass"));
            }
            if (response.get("query_plan_hash") != null) {
                result.put("queryPlanHash", response.get("query_plan_hash"));
            }
            if (response.get("executed_resolvers") != null) {
                result.put("executedResolvers", response.get("executed_resolvers"));
            }
            return ensureActionableMessage(result);
        } catch (Exception e) {
            log.warn("[{}] tiered-intent delegate gate failed factory={}: {}",
                    getToolName(), factoryId, e.getMessage());
            return null;
        }
    }

    /**
     * Best-effort session id lookup for the tiered-intent delegate gate's
     * clarification continuation (2026-07-08 design).
     *
     * <p>Only {@code ToolDispatchService.executeWithTool} — the main
     * intent-execution path — stashes the raw {@code IntentExecuteRequest}
     * under {@code context.get("request")} (see
     * {@code ToolDispatchService.java} around line 323). Other Tool-dispatch
     * paths that build a leaner context (e.g. {@code
     * DynamicToolSelectionService}, {@code LlmIntentFallbackClientImpl}, the
     * preview flow) do NOT carry it, so this returns {@code null} for those
     * — a safe, purely additive best-effort lookup, NOT a contract that
     * every call site guarantees a session id.
     *
     * <p>{@code null} is a complete no-op downstream: {@link
     * GoldFinanceClient#fetchTieredIntentAnswer(String, String, String)}
     * (the 3-arg overload) runs exactly as it did before this feature
     * existed — continuation is simply never attempted for that call.
     *
     * @param context the Tool execution context passed into {@code doExecute}
     * @return the session id, or {@code null} when not available through
     *         this path
     */
    private static String extractSessionId(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object request = context.get("request");
        if (request instanceof com.cretas.aims.dto.ai.IntentExecuteRequest req) {
            return req.getSessionId();
        }
        return null;
    }

    private String resolveGoldFactoryId(String factoryId) {
        if ("DEMO_REST".equalsIgnoreCase(factoryId)) {
            // Public no-login restaurant demo account: use the complete QHJ-style
            // Gold dataset so AI demo questions have dish/revenue/review depth.
            return "RES_3101_009";
        }
        return factoryId;
    }

    private Map<String, Object> ensureActionableMessage(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        if (Boolean.TRUE.equals(result.remove("suppressActionAdvice"))) {
            return result;
        }
        Object msgObj = result.get("message");
        if (!(msgObj instanceof String message) || message.isBlank()) {
            return result;
        }
        String advice = defaultAdviceForTool();
        String scenario = defaultOwnerActionScenarioForTool();
        result.putIfAbsent("actionAdvice", advice);
        result.putIfAbsent("decisionBridge", decisionBridge(advice, scenario));
        result.putIfAbsent("suggestedFollowups", decisionFollowups(scenario));
        if (containsActionAdvice(message)) {
            return result;
        }
        result.put("message", message + "\n\n建议：" + advice);
        return result;
    }

    private boolean containsActionAdvice(String message) {
        return message.contains("建议") || message.contains("优先") || message.contains("复盘")
                || message.contains("关注") || message.contains("排查") || message.contains("试点")
                || message.contains("控制") || message.contains("调整");
    }

    private String defaultAdviceForTool() {
        String tool = getToolName();
        if ("restaurant_dish_bestseller_gold".equals(tool)) {
            return "把第一名菜品作为主推款保留曝光，同时复盘 Top5 是否都有完整配方成本；对高销量但低毛利的菜先查份量、售价和套餐折扣。";
        }
        if ("restaurant_store_revenue_rank_gold".equals(tool)) {
            return "先对比第一名和末位门店的客单价、订单数、折扣和菜品结构，把可复制动作从第一名门店做小范围试点。";
        }
        if ("restaurant_discount_usage_gold".equals(tool)) {
            return "优先核查优惠金额最高的活动是否带来新增订单和毛利；对高补贴低客单的券先限量或改门槛，再观察一周。";
        }
        if ("restaurant_revenue_trend_gold".equals(tool)) {
            return "先别只看涨跌百分比。先确认最新月份是否已经完整结账；如果是完整月份，再把下滑拆到门店、渠道、折扣和时段，优先处理连续两个月下滑的门店。周末和周中差距不大时，不要整天打折，先做午市/晚市分时段动作。";
        }
        if ("restaurant_dish_slowseller_gold".equals(tool)) {
            return "先排除测试商品、无需餐具、临时规格这类非正式菜品；真正慢销的菜先看是否高损耗、高备货或占厨房工位。处理顺序是：能并入套餐的先并入，只拖库存的限量或下架，毛利好的保留小范围曝光再观察一周。";
        }
        if ("restaurant_weekday_weekend_gold".equals(tool)) {
            return "直接分开看周中和周末：如果周末明显高，就把备货、排班和等位动线压到周五晚到周日；如果周中弱，就用午市双人套餐、工作日会员券和附近办公/商场入口补低峰。差距不大时不要为了周末单独做大促，先查客单价、翻台和差评是否有差别。";
        }
        if ("restaurant_order_type_mix_gold".equals(tool)) {
            return "分别看堂食和外卖的客单价、毛利和差评标签；外卖占比高时先查包装、出餐时长和平台抽佣，堂食占比高时优化翻台与套餐引导。";
        }
        if (tool != null && tool.contains("review")) {
            return "先把低星评价和高频差评标签按门店拆开，优先处理服务、环境、口味里分数最低的一项，并在一周后复查评分变化。";
        }
        return "先定位排名最高和最低的项目差异，再按门店、菜品、时段拆分验证原因，做一轮小范围调整后复盘指标变化。";
    }

    private Map<String, Object> decisionBridge(String advice, String scenario) {
        Map<String, Object> bridge = new LinkedHashMap<>();
        bridge.put("answerMode", "report_with_owner_action");
        bridge.put("ownerActionScenario", scenario);
        bridge.put("plainDecision", advice);
        bridge.put("why", "这条仍然是普通报表回答，但后续追问会继续走同一个 /ai-intents/execute，并带上老板决策场景。");
        return bridge;
    }

    private List<Map<String, Object>> decisionFollowups(String scenario) {
        return List.of(
                followup("老板今天怎么用这张报表做决定？", "老板今天怎么用这张报表做决定？", scenario),
                followup("哪些动作今天先不要做？", "哪些动作今天先不要做？", scenario),
                followup("明天看哪三个数判断有没有效果？", "明天看哪三个数判断有没有效果？", scenario)
        );
    }

    private Map<String, Object> followup(String label, String question, String scenario) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("question", question);
        f.put("ownerActionScenario", scenario);
        return f;
    }

    private String defaultOwnerActionScenarioForTool() {
        String tool = getToolName();
        if ("restaurant_dish_bestseller_gold".equals(tool)) {
            return "single_item_push";
        }
        if ("restaurant_store_revenue_rank_gold".equals(tool)) {
            return "store_compare";
        }
        if ("restaurant_discount_usage_gold".equals(tool)) {
            return "traffic_conversion";
        }
        if ("restaurant_revenue_trend_gold".equals(tool)) {
            return "external_event_response";
        }
        if ("restaurant_dish_slowseller_gold".equals(tool)) {
            return "cost_margin";
        }
        if ("restaurant_weekday_weekend_gold".equals(tool)) {
            return "package";
        }
        if ("restaurant_order_type_mix_gold".equals(tool)) {
            return "traffic_conversion";
        }
        if (tool != null && tool.contains("review")) {
            return "staff_training";
        }
        return "store_compare";
    }

    // =========================================================================
    // Time-window resolution
    // =========================================================================

    /**
     * Resolve the [start, end] analysis window for a Gold query.
     *
     * <p>Precedence:
     * <ol>
     *   <li>Explicit {@code month} param (LLM-extracted "上月" / "本月" / "YYYY-MM").</li>
     *   <li>Preprocessed {@code startDate} / {@code endDate} ISO params set by
     *       {@code ToolDispatchService} TimeNormalizationRules.</li>
     *   <li>NL absolute month parsed from {@code params.get("userInput")}
     *       (e.g. "2025年12月哪个菜亏钱").</li>
     *   <li>Relative "本月" / "上月" mentioned in the NL query.</li>
     *   <li>Factory actual data range via {@code gold.fetchDataRange(factoryId)} →
     *       returns [min_date, max_date].</li>
     *   <li>Last resort: trailing 12-month window ending today.</li>
     * </ol>
     *
     * @param factoryId tenant id; used only if falling through to fetchDataRange
     * @param params    Tool params; may contain {@code month}, {@code startDate},
     *                  {@code endDate}, {@code userInput}
     * @return {@code [start, end]} — both elements non-null; start ≤ end
     */
    protected LocalDate[] resolveWindow(String factoryId, Map<String, Object> params) {

        // --- Step 1: explicit "month" param ---
        String monthParam = getString(params, "month");
        if (monthParam != null && !monthParam.trim().isEmpty()) {
            LocalDate[] win = parseMonthLabel(monthParam.trim());
            if (win != null) {
                log.debug("[{}] resolveWindow: explicit month param → {}..{}",
                        getToolName(), win[0], win[1]);
                return win;
            }
        }

        // --- Step 2: preprocessed ISO startDate / endDate ---
        String startIso = getString(params, "startDate");
        String endIso = getString(params, "endDate");
        if (startIso != null && endIso != null) {
            LocalDate s = parseIsoDate(startIso);
            LocalDate e = parseIsoDate(endIso);
            if (s != null && e != null && !s.isAfter(e)) {
                log.debug("[{}] resolveWindow: preprocessed ISO dates → {}..{}",
                        getToolName(), s, e);
                return new LocalDate[]{s, e};
            }
        }

        // --- Step 3 & 4: parse raw NL query ---
        // The Gold data range is the anchor for RELATIVE expressions (本月/今年/本季度/近N天):
        // the demo / customer data typically ends in the past (e.g. 2026-04), so anchoring
        // "本月" to today (2026-06) would yield an empty window. We use the data's latest day
        // as "now" when available, else fall back to LocalDate.now().
        String userInput = getString(params, "userInput");
        LocalDate[] dataRange = fetchDataRangeQuiet(factoryId);
        LocalDate anchor = (dataRange != null && dataRange[1] != null) ? dataRange[1] : LocalDate.now();
        if (userInput != null) {
            LocalDate[] win = parseNlTimeWindow(userInput, anchor, dataRange);
            if (win != null) {
                log.debug("[{}] resolveWindow: NL time window (anchor={}) → {}..{}",
                        getToolName(), anchor, win[0], win[1]);
                return win;
            }
        }

        // --- Step 5: factory actual data range ---
        if (dataRange != null && dataRange[0] != null && dataRange[1] != null
                && !dataRange[0].isAfter(dataRange[1])) {
            log.debug("[{}] resolveWindow: Gold data-range → {}..{}",
                    getToolName(), dataRange[0], dataRange[1]);
            return new LocalDate[]{dataRange[0], dataRange[1]};
        }
        log.debug("[{}] resolveWindow: Gold data-range null/empty for factory={}; using fallback",
                getToolName(), factoryId);

        // --- Step 6: last-resort 12-month trailing window ---
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusMonths(12);
        log.debug("[{}] resolveWindow: last-resort fallback → {}..{}", getToolName(), start, end);
        return new LocalDate[]{start, end};
    }

    /**
     * Fetch the factory's Gold data range as {@code [minDate, maxDate]}, swallowing errors.
     *
     * @param factoryId tenant id
     * @return {@code [min, max]} (either element may be {@code null}); or {@code null} on
     *         transport failure / empty data
     */
    private LocalDate[] fetchDataRangeQuiet(String factoryId) {
        try {
            Map<String, Object> range = gold.fetchDataRange(factoryId);
            if (range == null) return null;
            LocalDate minDate = parseIsoDate(range.get("min_date") != null ? range.get("min_date").toString() : null);
            LocalDate maxDate = parseIsoDate(range.get("max_date") != null ? range.get("max_date").toString() : null);
            if (minDate == null && maxDate == null) return null;
            return new LocalDate[]{minDate, maxDate};
        } catch (Exception ex) {
            log.warn("[{}] fetchDataRange failed factory={}: {}", getToolName(), factoryId, ex.getMessage());
            return null;
        }
    }

    /**
     * Parse a natural-language time window from the user query.
     *
     * <p>Coverage (matched in priority order — most-specific first):
     * <ol>
     *   <li>Absolute month: {@code 2025年12月}, {@code 2025-12}, {@code 2025/12}</li>
     *   <li>Quarter: {@code 本季度}, {@code 上季度}, {@code 第N季度} / {@code QN}</li>
     *   <li>Year: {@code 今年}, {@code 去年}, {@code 前年}, {@code 本年}, {@code YYYY年} (whole year)</li>
     *   <li>Rolling N days: {@code 近30天}, {@code 最近7天} (trailing from anchor)</li>
     *   <li>Relative month: {@code 本月}/{@code 这个月}/{@code 当月}, {@code 上月}/{@code 上个月}</li>
     *   <li>Relative week: {@code 本周}, {@code 上周}</li>
     * </ol>
     *
     * <p>All RELATIVE expressions resolve against {@code anchor} (the Gold data's latest day,
     * or today if unknown). Whole-year / quarter windows are clamped to the data range so a
     * "今年" query on data ending 2026-04 yields {@code 2026-01-01..2026-04-30} rather than a
     * window that runs past the data and looks empty downstream.
     *
     * @param userInput the raw NL query
     * @param anchor    the reference "now" (data max date, or today)
     * @param dataRange {@code [min, max]} for clamping wide windows; may be {@code null}
     * @return {@code [start, end]} window, or {@code null} if no time expression is present
     */
    LocalDate[] parseNlTimeWindow(String userInput, LocalDate anchor, LocalDate[] dataRange) {
        if (userInput == null) return null;

        // 1. Absolute month (YYYY年M月 / YYYY-MM / YYYY/M月)
        Matcher m = NL_ABSOLUTE_MONTH.matcher(userInput);
        if (m.find()) {
            int year = Integer.parseInt(m.group(1));
            int monthNum = Integer.parseInt(m.group(2));
            YearMonth ym = YearMonth.of(year, monthNum);
            return new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
        }

        // 2. Quarter — 第N季度 / QN first (explicit), then 本季度 / 上季度 (relative)
        Matcher q = NL_QUARTER.matcher(userInput);
        if (q.find()) {
            // group(1) = 第N季度 form, group(2) = QN form
            String token = q.group(1) != null ? q.group(1) : q.group(2);
            int quarter = cnQuarterToInt(token);
            if (quarter >= 1 && quarter <= 4) {
                return clamp(quarterWindow(anchor.getYear(), quarter), dataRange);
            }
        }
        if (userInput.contains("本季度") || userInput.contains("这个季度")
                || userInput.contains("当季") || userInput.contains("当前季度")) {
            int quarter = (anchor.getMonthValue() - 1) / 3 + 1;
            return clamp(quarterWindow(anchor.getYear(), quarter), dataRange);
        }
        if (userInput.contains("上季度") || userInput.contains("上个季度")) {
            int curQuarter = (anchor.getMonthValue() - 1) / 3 + 1;
            int prevQuarter = curQuarter - 1;
            int year = anchor.getYear();
            if (prevQuarter == 0) { prevQuarter = 4; year -= 1; }
            return clamp(quarterWindow(year, prevQuarter), dataRange);
        }

        // 3. Year — 今年/去年/前年/本年, and bare "YYYY年" (whole year, no month after it)
        if (userInput.contains("今年") || userInput.contains("本年")) {
            return clamp(yearWindow(anchor.getYear()), dataRange);
        }
        if (userInput.contains("去年")) {
            return clamp(yearWindow(anchor.getYear() - 1), dataRange);
        }
        if (userInput.contains("前年")) {
            return clamp(yearWindow(anchor.getYear() - 2), dataRange);
        }
        Matcher yr = NL_BARE_YEAR.matcher(userInput);
        if (yr.find()) {
            return clamp(yearWindow(Integer.parseInt(yr.group(1))), dataRange);
        }

        // 4. Rolling N days — 近N天 / 最近N天
        Matcher nd = NL_LAST_N_DAYS.matcher(userInput);
        if (nd.find()) {
            int n = Integer.parseInt(nd.group(1));
            if (n > 0) {
                LocalDate start = anchor.minusDays((long) n - 1);
                return new LocalDate[]{start, anchor};
            }
        }

        // 4b. Rolling N months — 近N个月 / 最近N个月 (trailing calendar months, inclusive of the
        // anchor's month). Checked AFTER N-days so "近30天" still wins; the pattern requires 月
        // (not 天/日) so the two never collide. Window = first day of (anchor month - (n-1)) .. anchor.
        Matcher nmo = NL_LAST_N_MONTHS.matcher(userInput);
        if (nmo.find()) {
            int n = Integer.parseInt(nmo.group(1));
            if (n > 0) {
                LocalDate start = YearMonth.from(anchor).minusMonths((long) n - 1).atDay(1);
                return new LocalDate[]{start, anchor};
            }
        }

        // 5. Relative month — anchored to data's latest month, NOT today
        if (userInput.contains("本月") || userInput.contains("这个月") || userInput.contains("当月")) {
            YearMonth ym = YearMonth.from(anchor);
            return new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
        }
        if (userInput.contains("上月") || userInput.contains("上个月")) {
            YearMonth ym = YearMonth.from(anchor).minusMonths(1);
            return new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
        }

        // 6. Relative week — anchored to data's latest day (Mon..Sun of anchor's week)
        if (userInput.contains("本周") || userInput.contains("这周") || userInput.contains("本星期")) {
            LocalDate start = anchor.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            LocalDate end = anchor.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY));
            return new LocalDate[]{start, end};
        }
        if (userInput.contains("上周") || userInput.contains("上个星期") || userInput.contains("上星期")) {
            LocalDate ref = anchor.minusWeeks(1);
            LocalDate start = ref.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            LocalDate end = ref.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY));
            return new LocalDate[]{start, end};
        }

        return null;
    }

    /** Quarter [firstDay, lastDay] for a given year (q ∈ 1..4). */
    private static LocalDate[] quarterWindow(int year, int q) {
        int startMonth = (q - 1) * 3 + 1;
        YearMonth startYm = YearMonth.of(year, startMonth);
        YearMonth endYm = YearMonth.of(year, startMonth + 2);
        return new LocalDate[]{startYm.atDay(1), endYm.atEndOfMonth()};
    }

    /** Whole-year [Jan 1, Dec 31] window. */
    private static LocalDate[] yearWindow(int year) {
        return new LocalDate[]{LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)};
    }

    /**
     * Clamp a wide window (year / quarter) to the data range so it does not extend past the
     * available data. If the window falls entirely outside the data range, it is returned
     * unchanged (the empty-guard downstream then produces an honest "no data" message).
     */
    private static LocalDate[] clamp(LocalDate[] win, LocalDate[] dataRange) {
        if (dataRange == null || win == null) return win;
        LocalDate min = dataRange[0];
        LocalDate max = dataRange[1];
        LocalDate start = win[0];
        LocalDate end = win[1];
        if (min != null && start.isBefore(min)) start = min;
        if (max != null && end.isAfter(max)) end = max;
        if (start.isAfter(end)) {
            // Window is disjoint from data range — keep original so downstream returns
            // an honest empty result rather than an inverted window.
            return win;
        }
        return new LocalDate[]{start, end};
    }

    /** Map a Chinese/Arabic quarter token (一/二/三/四 or 1-4) to an int. */
    private static int cnQuarterToInt(String token) {
        if (token == null) return -1;
        switch (token) {
            case "一": case "1": return 1;
            case "二": case "2": return 2;
            case "三": case "3": return 3;
            case "四": case "4": return 4;
            default: return -1;
        }
    }

    /** {@code 第N季度} / {@code QN} — captures the quarter token (一二三四 or 1-4). */
    private static final Pattern NL_QUARTER = Pattern.compile(
            "第\\s*([一二三四1-4])\\s*季度|[Qq]([1-4])");

    /** Bare {@code YYYY年} NOT immediately followed by a month digit (whole-year intent). */
    private static final Pattern NL_BARE_YEAR = Pattern.compile(
            "(\\d{4})\\s*年(?!\\s*(?:1[0-2]|0?[1-9])\\s*月)");

    /** {@code 近N天} / {@code 最近N天} — rolling N-day window. */
    private static final Pattern NL_LAST_N_DAYS = Pattern.compile(
            "(?:最近|近)\\s*(\\d{1,3})\\s*[天日]");

    /** {@code 近N个月} / {@code 最近N个月} — rolling N-month window (optional 个). */
    private static final Pattern NL_LAST_N_MONTHS = Pattern.compile(
            "(?:最近|近)\\s*(\\d{1,3})\\s*个?\\s*月");

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Parse a month label into a [firstDay, lastDay] window.
     *
     * @param label one of: {@code "上月"}, {@code "本月"}, {@code "YYYY-MM"}
     * @return window or {@code null} if the label is unrecognised
     */
    LocalDate[] parseMonthLabel(String label) {
        if (label == null) return null;
        switch (label) {
            case "本月":
            case "这个月":
            case "当月": {
                YearMonth ym = YearMonth.now();
                return new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
            }
            case "上月":
            case "上个月": {
                YearMonth ym = YearMonth.now().minusMonths(1);
                return new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
            }
            default: {
                // Try ISO "YYYY-MM"
                if (label.length() >= 7) {
                    try {
                        YearMonth ym = YearMonth.parse(label.substring(0, 7));
                        return new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
                    } catch (DateTimeParseException ignored) {
                        // fall through
                    }
                }
                return null;
            }
        }
    }

    /**
     * Safely parse an ISO {@code yyyy-MM-dd} date string.
     *
     * @param s date string; may be null or malformed
     * @return parsed date, or {@code null} on any failure
     */
    private LocalDate parseIsoDate(String s) {
        if (s == null || s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    // =========================================================================
    // Subclass contract overrides that must pass through to AbstractBusinessTool
    // =========================================================================

    /**
     * Gold Tools read-only by default; no required parameters (window is self-resolving).
     * Concrete subclasses may override if they need additional required params.
     */
    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    // =========================================================================
    // ECharts chart config helpers — used by format() in each concrete tool
    // =========================================================================

    /**
     * Build a horizontal bar chart config for ECharts.
     *
     * <p>The first entry in {@code categories}/{@code values} is rendered at the
     * <em>bottom</em> of a horizontal bar chart by default; we reverse both lists
     * so that the highest-ranked item (index 0) appears at the <em>top</em>.
     *
     * @param title      chart title (shown in the UI header above the chart)
     * @param categories labels for each bar (e.g. dish names, store names)
     * @param values     numeric values parallel to {@code categories}
     * @param unitName   axis unit label (e.g. "万元", "份", "单")
     * @return chartConfig map shaped {@code {type, title, option}}
     */
    protected static Map<String, Object> barChartConfig(
            String title,
            List<String> categories,
            List<? extends Number> values,
            String unitName) {

        // Reverse so highest value (index 0) appears at the top of the bar chart.
        List<String> cat = new ArrayList<>(categories);
        Collections.reverse(cat);
        List<Object> val = new ArrayList<>(values);
        Collections.reverse(val);

        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("tooltip", Map.of("trigger", "axis"));
        opt.put("grid", Map.of("left", "3%", "right", "6%",
                "bottom", "3%", "top", "8%", "containLabel", true));
        opt.put("xAxis", Map.of("type", "value", "name", unitName));
        opt.put("yAxis", Map.of("type", "category", "data", cat,
                "axisLabel", Map.of("fontSize", 11)));

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("type", "bar");
        series.put("data", val);
        series.put("itemStyle", Map.of("color", "#5470c6"));
        series.put("label", Map.of("show", true, "position", "right"));
        opt.put("series", List.of(series));

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "bar");
        cfg.put("title", title);
        cfg.put("option", opt);
        return cfg;
    }

    /**
     * Build a line chart config for ECharts (time series — e.g. monthly revenue trend).
     *
     * <p>Unlike {@link #barChartConfig}, categories are NOT reversed — a line chart
     * reads left→right chronologically, so the caller passes them already ascending
     * (earliest first). The AIQuery renderer uses the emitted {@code option} directly.
     *
     * @param title      chart title (shown in the UI header above the chart)
     * @param categories x-axis labels in chronological order (e.g. "2025-01", "2025-02")
     * @param values     numeric values parallel to {@code categories}
     * @param unitName   y-axis unit label (e.g. "万元")
     * @param seriesName legend / series name (e.g. "营收")
     * @return chartConfig map shaped {@code {type, title, option}}
     */
    protected static Map<String, Object> lineChartConfig(
            String title,
            List<String> categories,
            List<? extends Number> values,
            String unitName,
            String seriesName) {

        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("tooltip", Map.of("trigger", "axis"));
        opt.put("grid", Map.of("left", "3%", "right", "4%",
                "bottom", "3%", "top", "10%", "containLabel", true));
        opt.put("xAxis", Map.of("type", "category", "data", new ArrayList<>(categories),
                "axisLabel", Map.of("fontSize", 11, "rotate", categories.size() > 6 ? 40 : 0)));
        opt.put("yAxis", Map.of("type", "value", "name", unitName));

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", seriesName);
        series.put("type", "line");
        series.put("smooth", true);
        series.put("data", new ArrayList<>(values));
        series.put("itemStyle", Map.of("color", "#2D8B57"));
        series.put("label", Map.of("show", false));
        opt.put("series", List.of(series));

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "line");
        cfg.put("title", title);
        cfg.put("option", opt);
        return cfg;
    }

    /**
     * Build a pie chart config for ECharts.
     *
     * @param title  chart title
     * @param names  slice labels
     * @param values slice values parallel to {@code names}
     * @return chartConfig map shaped {@code {type, title, option}}
     */
    protected static Map<String, Object> pieChartConfig(
            String title,
            List<String> names,
            List<? extends Number> values) {

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            data.add(Map.of("name", names.get(i), "value", values.get(i)));
        }

        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("tooltip", Map.of("trigger", "item", "formatter", "{b}: {c} ({d}%)"));
        opt.put("legend", Map.of("top", "bottom"));

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("type", "pie");
        series.put("radius", "60%");
        series.put("data", data);
        series.put("label", Map.of("formatter", "{b} {d}%"));
        opt.put("series", List.of(series));

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "pie");
        cfg.put("title", title);
        cfg.put("option", opt);
        return cfg;
    }

    /**
     * Convert a raw amount (in yuan) to 万元 rounded to 1 decimal place.
     *
     * @param yuan raw value in yuan
     * @return value in 万元 (1 decimal)
     */
    protected static double toWan(double yuan) {
        return Math.round(yuan / 1000.0) / 10.0;
    }
}
