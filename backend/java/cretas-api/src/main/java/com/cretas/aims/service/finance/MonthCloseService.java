package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.MonthCloseReconciliationDTO;
import com.cretas.aims.dto.finance.MonthCloseResultDTO;

/**
 * Wave2 月结自动闭环编排服务.
 *
 * <p>兑现邓总 "30号数据完结, 1-3号出报表, 留20天调整窗口" —— 把月结从"月底才出报表"
 * 改为月初编排: 对账校验 → 生成 P&L → 快照锁定 → 置 CLOSED + 设20天调整窗口。
 *
 * <p>本服务 <b>编排已有能力</b> (不重复造):
 * <ul>
 *   <li>{@link ArApService#getPendingAdjustments}: 对账 — 检查未审批的应收/应付调整</li>
 *   <li>{@link IncomeStatementService#generate}: 生成单月利润表 (P&L)</li>
 *   <li>{@link AccountingPeriodService}: 复用 CLOSED 状态机 + 调整窗口</li>
 * </ul>
 *
 * @since 2026-06-04 Wave2
 */
public interface MonthCloseService {

    /**
     * 月结对账预览 (不写库). 防呆 Rule 1 "预先显示边界" —— 用户点"一键月结"之前先看对账状态.
     *
     * <p>校验项:
     * <ul>
     *   <li>未审批应收/应付调整 (有 → WARNING, 非阻塞)</li>
     *   <li>P&L 可生成 (有收入/成本数据 → 信息项)</li>
     *   <li>期间状态 (已 CLOSED → BLOCKING, 不可重复结账)</li>
     * </ul>
     *
     * @return canClose + 各项 check 明细
     */
    MonthCloseReconciliationDTO previewClose(String factoryId, Integer year, Integer month);

    /**
     * 执行月结闭环. 编排顺序:
     * <ol>
     *   <li>幂等检查: 已 CLOSED → 抛 409 (防呆 Rule 4 防重复结账)</li>
     *   <li>对账校验 (previewClose); 任一 BLOCKING 失败 → 抛 400 明确报错 (禁降级假数据)</li>
     *   <li>生成 P&L (单月)</li>
     *   <li>快照锁定: 写入 P&L 收入/净利/JSON + 对账结论到 period</li>
     *   <li>置 CLOSED + closed_at=now + adjust_deadline=now+20天 + report_ready_at=now</li>
     * </ol>
     *
     * @return 已 CLOSED 的 period + P&L + 调整窗口
     */
    MonthCloseResultDTO executeClose(String factoryId, Integer year, Integer month, Long userId);

    /** 调整窗口天数 (邓总要求 20 天). */
    int ADJUST_WINDOW_DAYS = 20;
}
