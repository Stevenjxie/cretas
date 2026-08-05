package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.repository.alerts.AlertEventRepository;
import com.cretas.aims.repository.calibration.ToolCallRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 价值汇总 —— 回答「这个 AI 这段时间做了什么、花了多少」。
 *
 * <h2>为什么不报金额</h2>
 *
 * 本仓<b>没有 token 单价配置</b>（全仓搜 {@code cost_per_*} 只命中 BOM 人工成本和
 * 部门人均成本，与 token 无关）。要报「花了 ¥X」就得自己编一个费率，那会得到一个
 * <b>看起来精确的假数字</b>。所以这里只报 token 与次数，并在
 * {@code costUnavailableReason} 里显式说明原因 —— 让"没有"是可解释的，而不是
 * 一个悄悄消失的字段。
 *
 * <h2>为什么不报「省了多少钱」</h2>
 *
 * 「省了 ¥X」需要两样本仓拿不出来的东西：一个反事实（不提醒的话会损失多少）和
 * 一条因果（人是因为看到提醒才行动的）。这里改用告警状态机
 * {@code OPEN → ACKNOWLEDGED → RESOLVED} 的三段计数替代：<b>触发了几条、有几条
 * 被人确认、有几条被解决</b>。每个数字都能在 {@code alert_events} 里逐行核对，
 * 每条都带 {@code businessEntityId} 可以点开看到具体对象（批次号等）。
 *
 * <p>零 LLM：全部来自两张表的计数，话术是模板。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiValueSummaryTool extends AbstractBusinessTool {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 365;
    /** 明细里最多带回几条，避免把整个窗口的告警塞进一次回答。 */
    private static final int MAX_DETAIL = 5;

    private final ToolCallRecordRepository toolCallRecordRepository;
    private final AlertEventRepository alertEventRepository;

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
        days.put("description", "统计最近多少天，默认 30");
        days.put("default", DEFAULT_DAYS);
        days.put("minimum", 1);
        days.put("maximum", MAX_DAYS);

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
        int days = normalizeDays(getInteger(params, "days", DEFAULT_DAYS));
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(days);

        // 两次查询都不 catch —— 查询失败必须让整个回答失败。把失败吞掉再渲染
        // 「调用 0 次 / 预警 0 条」, 等于把"查不到"说成"什么都没发生", 那是两件
        // 完全不同的事 (禁止降级处理)。
        List<ToolCallRecord> calls =
                toolCallRecordRepository.findByFactoryIdAndCreatedAtBetween(factoryId, start, end);
        List<AlertEvent> alerts =
                alertEventRepository.findByFactoryIdAndCreatedAtBetween(factoryId, start, end);

        long inputTokens = 0L;
        long outputTokens = 0L;
        for (ToolCallRecord call : calls) {
            // null token 记成 0 而不是跳过该行: 跳过会让"这次调用没记 token"变成
            // "这次调用不存在", 次数与 token 的口径就对不上了。
            inputTokens += call.getInputTokens() == null ? 0 : call.getInputTokens();
            outputTokens += call.getOutputTokens() == null ? 0 : call.getOutputTokens();
        }

        Map<String, Integer> alertsByStatus = new LinkedHashMap<>();
        for (AlertEvent alert : alerts) {
            String key = alert.getStatus() == null ? "UNKNOWN" : alert.getStatus().name();
            alertsByStatus.merge(key, 1, Integer::sum);
        }

        List<Map<String, Object>> details = new ArrayList<>();
        for (AlertEvent alert : alerts.stream().limit(MAX_DETAIL).toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("businessEntityType", alert.getBusinessEntityType());
            item.put("businessEntityId", alert.getBusinessEntityId());
            item.put("severity", alert.getSeverity() == null ? null : alert.getSeverity().name());
            item.put("status", alert.getStatus() == null ? null : alert.getStatus().name());
            item.put("message", alert.getMessage());
            details.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowDays", days);
        result.put("aiCalls", calls.size());
        result.put("inputTokens", inputTokens);
        result.put("outputTokens", outputTokens);
        result.put("totalTokens", inputTokens + outputTokens);
        result.put("alertsTotal", alerts.size());
        result.put("alertsByStatus", alertsByStatus);
        result.put("alertDetails", details);

        // 显式的"这里为什么没有金额", 而不是让字段悄悄消失。
        result.put("costInYuan", null);
        result.put("costUnavailableReason",
                "系统未配置 token 单价，无法把 token 折算成金额；编一个费率会得到看似精确的假数字。");

        result.put("message", buildMessage(days, calls.size(), inputTokens + outputTokens,
                alerts.size(), alertsByStatus));

        log.info("AI 价值汇总: factoryId={}, days={}, calls={}, alerts={}",
                factoryId, days, calls.size(), alerts.size());
        return result;
    }

    private int normalizeDays(Integer raw) {
        if (raw == null) {
            return DEFAULT_DAYS;
        }
        return Math.max(1, Math.min(MAX_DAYS, raw));
    }

    private String buildMessage(int days, int calls, long totalTokens,
                                int alertsTotal, Map<String, Integer> byStatus) {
        StringBuilder sb = new StringBuilder();
        sb.append("最近 ").append(days).append(" 天：");

        if (calls == 0 && alertsTotal == 0) {
            // 真的什么都没发生 —— 明说, 不要伪装成"运行良好"。
            sb.append("AI 没有被调用过，也没有触发任何预警。");
            return sb.toString();
        }

        sb.append("AI 被调用 ").append(calls).append(" 次，消耗 ")
                .append(totalTokens).append(" token（未配置单价，不折算金额）；");
        sb.append("触发预警 ").append(alertsTotal).append(" 条");
        if (alertsTotal > 0) {
            sb.append("，其中已确认 ").append(byStatus.getOrDefault("ACKNOWLEDGED", 0))
                    .append(" 条、已解决 ").append(byStatus.getOrDefault("RESOLVED", 0))
                    .append(" 条、仍敞开 ").append(byStatus.getOrDefault("OPEN", 0)).append(" 条");
        }
        sb.append("。");
        return sb.toString();
    }
}
