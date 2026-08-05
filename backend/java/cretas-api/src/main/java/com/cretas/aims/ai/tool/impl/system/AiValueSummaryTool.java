package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.aivalue.AiValueSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 价值汇总（AI 对话出口）—— 回答「这个 AI 这段时间做了什么、花了多少」。
 *
 * <p>口径<b>不在本类</b>，在 {@link AiValueSummaryService}。本类只负责把汇总结果
 * 渲染成对话里的一段话。web-admin 的 HTTP 端点走同一个 service —— 两个出口一个
 * 定义，不会出现「对话说 12 条、页面说 8 条」。
 *
 * <p>为什么不报金额、不报「省了多少钱」：见 {@link AiValueSummaryService} 的类注释。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiValueSummaryTool extends AbstractBusinessTool {

    private final AiValueSummaryService aiValueSummaryService;

    @Override
    public String getToolName() {
        return "system_ai_value_summary";
    }

    @Override
    public String getDescription() {
        return "汇总一段时间内 AI 做了什么：调用次数、消耗 token、触发了多少条预警"
                + "以及其中多少条被确认/被解决（每条可点开看到对应的批次或单据）。"
                + "适用场景：老板问'这个 AI 到底有没有用'、'这个月 AI 干了什么'、"
                + "'AI 花了多少'。默认统计最近 30 天。"
                + "注意：本系统未配置 token 单价，因此只报 token 与次数，不折算金额。";
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    /** 只读两张日志/事件表，不产生任何持久化副作用。 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> days = new HashMap<>();
        days.put("type", "integer");
        days.put("description", "统计最近多少天，默认 " + AiValueSummaryService.DEFAULT_DAYS);
        days.put("default", AiValueSummaryService.DEFAULT_DAYS);
        days.put("minimum", 1);
        days.put("maximum", AiValueSummaryService.MAX_DAYS);

        Map<String, Object> properties = new HashMap<>();
        properties.put("days", days);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        AiValueSummaryService.Summary s =
                aiValueSummaryService.summarize(factoryId, getInteger(params, "days", null));

        List<Map<String, Object>> details = new ArrayList<>();
        for (AiValueSummaryService.AlertDetail d : s.alertDetails()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("businessEntityType", d.businessEntityType());
            item.put("businessEntityId", d.businessEntityId());
            item.put("severity", d.severity());
            item.put("status", d.status());
            item.put("message", d.message());
            details.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowDays", s.windowDays());
        result.put("aiCalls", s.aiCalls());
        result.put("inputTokens", s.inputTokens());
        result.put("outputTokens", s.outputTokens());
        result.put("totalTokens", s.totalTokens());
        result.put("alertsTotal", s.alertsTotal());
        result.put("alertsByStatus", s.alertsByStatus());
        result.put("alertDetails", details);
        result.put("costInYuan", s.costInYuan());
        result.put("costUnavailableReason", s.costUnavailableReason());
        result.put("message", buildMessage(s));
        return result;
    }

    private String buildMessage(AiValueSummaryService.Summary s) {
        StringBuilder sb = new StringBuilder("最近 ").append(s.windowDays()).append(" 天：");

        if (s.aiCalls() == 0 && s.alertsTotal() == 0) {
            // 真的什么都没发生 —— 明说, 不要伪装成「运行良好」。
            return sb.append("AI 没有被调用过，也没有触发任何预警。").toString();
        }

        sb.append("AI 被调用 ").append(s.aiCalls()).append(" 次，消耗 ")
                .append(s.totalTokens()).append(" token（未配置单价，不折算金额）；");
        sb.append("触发预警 ").append(s.alertsTotal()).append(" 条");
        if (s.alertsTotal() > 0) {
            Map<String, Integer> by = s.alertsByStatus();
            sb.append("，其中已确认 ").append(by.getOrDefault("ACKNOWLEDGED", 0))
                    .append(" 条、已解决 ").append(by.getOrDefault("RESOLVED", 0))
                    .append(" 条、仍敞开 ").append(by.getOrDefault("OPEN", 0)).append(" 条");
        }
        return sb.append("。").toString();
    }
}
