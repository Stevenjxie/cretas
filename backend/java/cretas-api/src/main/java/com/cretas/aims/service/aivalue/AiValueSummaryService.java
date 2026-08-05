package com.cretas.aims.service.aivalue;

import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.repository.alerts.AlertEventRepository;
import com.cretas.aims.repository.calibration.ToolCallRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 价值汇总的<b>唯一口径</b>。
 *
 * <p>存在的理由就是「一个指标只能有一个定义」：AI 工具（{@code system_ai_value_summary}）
 * 和 web-admin 的 HTTP 端点都要这份数据，两边各写一遍 SQL 就是下一次
 * 「footer 显示 808 项、KPI 卡片接近 0」（见 {@code ListSummaryServiceImpl:43-50}
 * 记录的事故）。所以口径落在这里，两个出口都只是渲染。
 *
 * <h2>刻意不做的两件事</h2>
 *
 * <ul>
 *   <li><b>不折算金额</b>：本仓没有 token 单价配置，编一个费率会得到看起来精确的
 *       假数字。{@link Summary#costInYuan()} 恒为 null，并由
 *       {@link Summary#costUnavailableReason()} 解释原因 —— 让「没有」是可解释的，
 *       而不是一个悄悄消失的字段。</li>
 *   <li><b>不报「省了多少钱」</b>：缺反事实（不提醒会损失多少）和因果（人是否因它行动）。
 *       改用告警状态机 {@code OPEN → ACKNOWLEDGED → RESOLVED} 的三段计数，
 *       每条带 {@code businessEntityId} 可点开核对。</li>
 * </ul>
 *
 * <h2>失败不吞</h2>
 *
 * 仓储查询异常直接向上抛。把失败吞掉再返回「0 次 / 0 条」，等于把「查不到」说成
 * 「什么都没发生」——这两件事在屏幕上长得一模一样，但含义相反（禁止降级处理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiValueSummaryService {

    public static final int DEFAULT_DAYS = 30;
    public static final int MAX_DAYS = 365;
    /** 明细最多带回几条，避免把整个窗口的告警塞进一次响应。 */
    private static final int MAX_DETAIL = 5;

    private static final String COST_UNAVAILABLE_REASON =
            "系统未配置 token 单价，无法把 token 折算成金额；编一个费率会得到看似精确的假数字。";

    private final ToolCallRecordRepository toolCallRecordRepository;
    private final AlertEventRepository alertEventRepository;

    /**
     * 一次汇总的结果。
     *
     * @param costInYuan            恒为 {@code null}，见类注释
     * @param costUnavailableReason 为什么没有金额，非空
     */
    public record Summary(
            int windowDays,
            int aiCalls,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            int alertsTotal,
            Map<String, Integer> alertsByStatus,
            List<AlertDetail> alertDetails,
            Object costInYuan,
            String costUnavailableReason
    ) {}

    /** 可点开的告警明细 —— {@code businessEntityId} 就是批次号/单据号。 */
    public record AlertDetail(
            String businessEntityType,
            String businessEntityId,
            String severity,
            String status,
            String message
    ) {}

    /** 把越界的天数夹紧，而不是抛错或透传。 */
    public static int normalizeDays(Integer raw) {
        if (raw == null) {
            return DEFAULT_DAYS;
        }
        return Math.max(1, Math.min(MAX_DAYS, raw));
    }

    public Summary summarize(String factoryId, Integer rawDays) {
        int days = normalizeDays(rawDays);
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(days);

        // 不 catch —— 查询失败必须让整个回答失败, 见类注释。
        List<ToolCallRecord> calls =
                toolCallRecordRepository.findByFactoryIdAndCreatedAtBetween(factoryId, start, end);
        List<AlertEvent> alerts =
                alertEventRepository.findByFactoryIdAndCreatedAtBetween(factoryId, start, end);

        long inputTokens = 0L;
        long outputTokens = 0L;
        for (ToolCallRecord call : calls) {
            // null token 记 0 而不是跳过该行: 跳过会让「这次调用没记 token」变成
            // 「这次调用不存在」, 次数与 token 的口径就对不上了。
            inputTokens += call.getInputTokens() == null ? 0 : call.getInputTokens();
            outputTokens += call.getOutputTokens() == null ? 0 : call.getOutputTokens();
        }

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (AlertEvent alert : alerts) {
            byStatus.merge(alert.getStatus() == null ? "UNKNOWN" : alert.getStatus().name(),
                    1, Integer::sum);
        }

        List<AlertDetail> details = new ArrayList<>();
        for (AlertEvent alert : alerts.stream().limit(MAX_DETAIL).toList()) {
            details.add(new AlertDetail(
                    alert.getBusinessEntityType(),
                    alert.getBusinessEntityId(),
                    alert.getSeverity() == null ? null : alert.getSeverity().name(),
                    alert.getStatus() == null ? null : alert.getStatus().name(),
                    alert.getMessage()));
        }

        log.info("AI 价值汇总: factoryId={}, days={}, calls={}, alerts={}",
                factoryId, days, calls.size(), alerts.size());

        return new Summary(days, calls.size(), inputTokens, outputTokens,
                inputTokens + outputTokens, alerts.size(), byStatus, details,
                null, COST_UNAVAILABLE_REASON);
    }
}
