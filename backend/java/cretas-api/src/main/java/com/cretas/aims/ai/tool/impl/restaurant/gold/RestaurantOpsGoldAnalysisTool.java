package com.cretas.aims.ai.tool.impl.restaurant.gold;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.client.GoldFinanceClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only bridge from Java AI intents to Python restaurant-ops Gold analysis.
 */
@Component
public class RestaurantOpsGoldAnalysisTool extends AbstractBusinessTool {

    private final GoldFinanceClient gold;

    @Autowired
    public RestaurantOpsGoldAnalysisTool(GoldFinanceClient gold) {
        this.gold = gold;
    }

    @Override
    public String getToolName() {
        return "restaurant_ops_gold_analysis";
    }

    @Override
    public String getDescription() {
        return "餐饮经营 ops gold 分析工具。适用于领料趋势、损耗排名、盘亏热点、菜品成本、毛利、门店毛利、营收趋势等问题，返回真实聚合数据、白话分析和图表。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("userInput", Map.of(
                "type", "string",
                "description", "用户原始问题"));
        properties.put("intentCode", Map.of(
                "type", "string",
                "description", "当前命中的意图代码"));

        for (String parameter : List.of(
                "store_id", "store_name", "startDate", "endDate",
                "comparisonStartDate", "comparisonEndDate", "timeAnchorDate")) {
            properties.put(parameter, Map.of("type", "string"));
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    /**
     * A readable data-gap answer is still an unsuccessful query result. The shared business-tool
     * wrapper normally marks every returned map as successful, so this tool narrows that contract:
     * only an explicit {@code dataAvailable=false} result is surfaced as outer success=false while
     * preserving its customer-safe message and structured data.
     */
    @Override
    @SuppressWarnings("unchecked")
    public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
        String responseJson = super.execute(toolCall, context);
        Map<String, Object> envelope = objectMapper.readValue(responseJson, Map.class);
        if (!Boolean.TRUE.equals(envelope.get("success"))) {
            return responseJson;
        }
        Object dataObject = envelope.get("data");
        if (!(dataObject instanceof Map<?, ?> data)
                || !Boolean.FALSE.equals(data.get("dataAvailable"))) {
            return responseJson;
        }

        String message = firstNonBlank(
                asString(data.get("message")),
                asString(data.get("answer")),
                "本次查询没有获得可展示的经营结果。");
        Map<String, Object> unavailableEnvelope = new LinkedHashMap<>();
        unavailableEnvelope.put("success", false);
        unavailableEnvelope.put("message", message);
        unavailableEnvelope.put("data", dataObject);
        return objectMapper.writeValueAsString(unavailableEnvelope);
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        String question = firstNonBlank(
                asString(params.get("userInput")),
                asString(params.get("query")),
                asString(params.get("message")));
        if (question == null) {
            question = "分析餐饮经营情况";
        }

        Object requestObj = context != null ? context.get("request") : null;
        String sessionId = null;
        if (requestObj instanceof com.cretas.aims.dto.ai.IntentExecuteRequest req) {
            sessionId = req.getSessionId();
        }

        String intentCode = asString(params.get("intentCode"));
        Map<String, Object> analysisContext = buildAnalysisContext(params);
        Map<String, Object> response;
        try {
            response = gold.fetchRestaurantOpsAnalysis(
                    factoryId, question, sessionId, intentCode, analysisContext);
        } catch (IOException transportFailure) {
            String unavailableAnswer = unavailableAnswer(question, analysisContext);
            if (unavailableAnswer == null) {
                throw transportFailure;
            }
            return unavailableResult(question, intentCode, unavailableAnswer);
        }
        boolean success = !Boolean.FALSE.equals(response.get("success"));
        String answer = firstNonBlank(
                asString(response.get("answer")),
                asString(response.get("aiAnalysis")),
                asString(response.get("message")),
                asString(response.get("error")));

        if (answer == null || answer.isBlank()) {
            success = false;
            answer = "这次没有获得可展示的经营结果，因此没有生成结论。请换一个更具体的问题，例如“损耗金额排名和原因占比”或“最近哪些食材盘亏最严重”。";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataAvailable", success);
        result.put("message", answer);
        result.put("answer", answer);
        result.put("source", "restaurant_ops_gold");
        result.put("intentCode", params.get("intentCode"));
        result.put("charts", response.getOrDefault("charts", Collections.emptyList()));
        result.put("insights", response.getOrDefault("insights", Collections.emptyList()));
        result.put("processingTimeMs", response.getOrDefault("processing_time_ms", 0));
        String scenario = ownerActionScenario(question, asString(params.get("intentCode")));
        result.put("decisionBridge", decisionBridge(scenario));
        result.put("suggestedFollowups", decisionFollowups(scenario));
        return result;
    }

    private static Map<String, Object> unavailableResult(
            String question,
            String intentCode,
            String answer) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataAvailable", false);
        result.put("message", answer);
        result.put("answer", answer);
        result.put("source", "restaurant_ops_gold");
        result.put("intentCode", intentCode);
        result.put("charts", Collections.emptyList());
        result.put("insights", Collections.emptyList());
        result.put("processingTimeMs", 0);
        String scenario = ownerActionScenario(question, intentCode);
        result.put("decisionBridge", decisionBridge(scenario));
        result.put("suggestedFollowups", decisionFollowups(scenario));
        return result;
    }

    /**
     * Returns a truthful, question-specific answer only when the missing downstream result can be
     * described without inventing a metric. Unknown questions deliberately return {@code null} so
     * the original exception remains visible to the normal tool failure path.
     */
    private static String unavailableAnswer(String question, Map<String, Object> analysisContext) {
        String text = question == null ? "" : question.toLowerCase();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        DateTimeFormatter date = DateTimeFormatter.ISO_LOCAL_DATE;

        String storeName = asString(analysisContext.get("store_name"));
        if (storeName != null && containsAny(text, "毛利", "毛利率")) {
            return "已识别到您问的是" + storeName
                    + "的毛利率，但目前无法可靠取得该店的成本覆盖和毛利排名数据，"
                    + "因此不能给出结论，也不会用营业额或其他门店榜单替代。请稍后重试。";
        }

        String primaryStart = asString(analysisContext.get("start_date"));
        String primaryEnd = asString(analysisContext.get("end_date"));
        String baselineStart = asString(analysisContext.get("comparison_start_date"));
        String baselineEnd = asString(analysisContext.get("comparison_end_date"));
        if (primaryStart != null && primaryEnd != null
                && baselineStart != null && baselineEnd != null
                && containsAny(text, "毛利", "毛利率")) {
            return primaryStart + " 和 " + baselineStart
                    + " 的毛利数据目前无法可靠读取，因此不能判断哪天更高，"
                    + "也不会用其他日期、营业额或其他指标替代。请稍后重试。";
        }

        if (containsAny(text, "今天", "今日")
                && containsAny(text, "营收", "营业额", "销售额")
                && containsAny(text, "毛利", "毛利率")) {
            return "今天（" + today.format(date)
                    + "）的营收、毛利和毛利率目前无法可靠读取，因此不能给出结论，也不会拿其他日期的数据替代。请稍后重试。";
        }

        if (text.contains("昨天") && text.contains("前天")
                && containsAny(text, "营收", "营业额", "销售额")) {
            return "昨天（" + today.minusDays(1).format(date) + "）和前天（"
                    + today.minusDays(2).format(date)
                    + "）的营业额目前无法可靠读取，因此不能判断哪天更高，也不会用其他日期替代。请稍后重试。";
        }

        if (containsAny(text, "服务", "出餐", "上菜")
                && containsAny(text, "速度", "慢", "原因", "根因", "瓶颈")) {
            return "目前可用的经营数据只能支持营业额、订单量、客单价、成本和毛利分析；"
                    + "缺少点单、备餐、出餐、上菜各环节的时间记录，以及桌台、员工排班和顾客反馈数据，"
                    + "因此不能可靠判断服务、出餐或上菜慢的根因，也不会用营业额代替这些过程数据。";
        }

        if (containsAny(text, "菜单", "菜品", "菜")
                && containsAny(text, "优化", "调整", "淘汰", "下架")
                && containsAny(text, "销量", "销售额", "毛利", "退菜", "差评", "制作时长", "损耗")) {
            return "当前无法可靠读取菜品经营数据，因此不能给出应优化、淘汰或下架的菜品名单。"
                    + "服务恢复后可以分析菜品销量、销售额、已有成本覆盖范围内的毛利和食材损耗；"
                    + "目前仍缺少逐笔退菜记录（时间、菜品、数量、原因、门店）、顾客评价（评分、内容、时间、菜品、门店）"
                    + "以及制作过程起止时间。缺少的退菜、差评和制作时长不会用销量、销售额或损耗替代，"
                    + "也不会把部分维度包装成完整的菜单优化结论。";
        }

        if (containsAny(text, "门店", "该店", "这家店", "这家门店")
                && containsAny(text, "毛利", "毛利率")) {
            return "已识别到您问的是该店的毛利率，但目前无法可靠取得该店的成本覆盖和毛利排名数据，"
                    + "因此不能给出结论，也不会用营业额替代毛利率。请稍后重试。";
        }

        return null;
    }

    private static Map<String, Object> buildAnalysisContext(Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        copyNonBlank(params, result, "store_id", "store_id");
        copyNonBlank(params, result, "store_name", "store_name");
        copyNonBlank(params, result, "startDate", "start_date");
        copyNonBlank(params, result, "endDate", "end_date");
        copyNonBlank(params, result, "comparisonStartDate", "comparison_start_date");
        copyNonBlank(params, result, "comparisonEndDate", "comparison_end_date");
        copyNonBlank(params, result, "timeAnchorDate", "time_anchor_date");
        return result;
    }

    private static void copyNonBlank(
            Map<String, Object> source, Map<String, Object> target,
            String sourceKey, String targetKey) {
        String value = asString(source.get(sourceKey));
        if (value != null && !value.isBlank() && value.length() <= 160) {
            target.put(targetKey, value.trim());
        }
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String ownerActionScenario(String question, String intentCode) {
        String text = ((question == null ? "" : question) + " " + (intentCode == null ? "" : intentCode)).toLowerCase();
        if (text.contains("损耗") || text.contains("盘亏") || text.contains("领料") || text.contains("成本")
                || text.contains("毛利") || text.contains("采购") || text.contains("bom")) {
            return "cost_margin";
        }
        if (text.contains("门店") || text.contains("哪家店") || text.contains("排行") || text.contains("对比")) {
            return "store_compare";
        }
        if (text.contains("趋势") || text.contains("营收") || text.contains("销售") || text.contains("同比")
                || text.contains("环比")) {
            return "external_event_response";
        }
        return "store_compare";
    }

    private static Map<String, Object> decisionBridge(String scenario) {
        Map<String, Object> bridge = new LinkedHashMap<>();
        bridge.put("answerMode", "report_with_owner_action");
        bridge.put("ownerActionScenario", scenario);
        bridge.put("plainDecision", "继续追问时会沿用本会话最近的经营主题和时间范围；如果要换一个问题，请使用“新话题”。");
        return bridge;
    }

    private static List<Map<String, Object>> decisionFollowups(String scenario) {
        return List.of(
                followup("老板今天怎么做？", "老板今天怎么用这张报表做决定？", scenario),
                followup("先别做什么？", "哪些动作今天先不要做？", scenario),
                followup("明天看什么数？", "明天看哪三个数判断有没有效果？", scenario)
        );
    }

    private static Map<String, Object> followup(String label, String question, String scenario) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("question", question);
        f.put("ownerActionScenario", scenario);
        return f;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
