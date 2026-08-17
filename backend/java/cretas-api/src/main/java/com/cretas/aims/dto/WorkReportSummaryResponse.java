package com.cretas.aims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 报工看板汇总（替代 legacy {@code GET /work-reporting/summary}）。
 *
 * <p>⚠️ 返回 DTO 而不是 {@code Map} —— 口径见设计卡
 * {@code docs/decisions/2026-08-17-legacy报工栈退役.md}：新增出口一律 DTO，
 * 存量 Map 冻结不扩散。
 *
 * <h3>🔴 只带这三个字段，是量出来的，不是省事</h3>
 *
 * legacy 的 {@code getSummary} 往 Map 里塞了 7 个键。实测（2026-08-17，
 * grep 全部 RN + web-admin 源码，排除测试）唯一的生产消费方是
 * {@code useDashboardData.ts:65}，它只读三个：
 * {@code pendingApprovalCount} / {@code todayOutputTotal} / {@code todayYieldRate}。
 *
 * <p><b>显式登记「故意不带过来」的四个</b>（⛔ 不是漏了，是零消费方 —— 登记是留痕不是豁免）：
 * <ul>
 *   <li>{@code progressSummary} / {@code hoursSummary} —— 原样透出的 native 聚合 Map
 *       （键是 {@code total_output} 这类 snake_case）；零消费方，搬过来等于把冻结的
 *       Map 出口又开一个；</li>
 *   <li>{@code weeklyOutput} —— 近 7 天趋势 List&lt;Map&gt;；零消费方；</li>
 *   <li>{@code todayCount} —— 今日报工条数；零消费方（RN 侧 {@code todayCount} 的
 *       两处命中分别属于 dashboard-stats 与餐饮领用单，不是这条路）。</li>
 * </ul>
 *
 * <p>⇒ 连带的后果：新端点<b>不再接受 {@code startDate} / {@code endDate}</b>。
 * legacy 那两个参数只喂上面被砍掉的三个字段，留着就是一个不影响任何返回值的参数
 * —— 比少一个字段更坏。RN 侧唯一的调用是无参的 {@code getSummary()}，不受影响。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkReportSummaryResponse {

    /**
     * 待审批报工条数。
     *
     * <p>🔴 口径与 legacy 完全一致：{@code status = SUBMITTED}（见
     * {@code WorkReportingServiceImpl.countPendingReports}）。
     *
     * <p>⚠️ 它与本 controller 的 {@code /pending-approval} 列表<b>不是同一个口径</b>：
     * 那条列表查的是 {@code approvalStatus = 'PENDING'} 且 {@code workProcessTaskId IS NOT NULL}
     * （{@code findPendingApprovalsForFactory}）。两个数可以不相等。
     *
     * <p>本次<b>故意不统一</b>：迁移的判据是「换出口不换数」，改这个数是一次产品决定，
     * 要跟退役第 5 步（删 {@code WorkReportApprovalScreen}、审批只留网页端）一起做，
     * ⛔ 不能顺手夹带在一个「补端点」的改动里。由 {@code ProcessReportSummaryTest} 钉住
     * 当前用的是哪一个，改动只能是显式的。
     */
    private long pendingApprovalCount;

    /** 今日产出合计（PROGRESS 报工的 output_quantity 求和）。 */
    private BigDecimal todayOutputTotal;

    /**
     * 今日良品率（百分数，scale 1，HALF_UP）。
     *
     * <p>⚠️ 产出为 0 时返回 {@code 0} 而不是抛除零 —— 与 legacy 同口径。
     */
    private BigDecimal todayYieldRate;
}
