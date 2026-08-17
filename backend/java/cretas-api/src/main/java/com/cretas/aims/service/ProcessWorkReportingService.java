package com.cretas.aims.service;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.common.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

public interface ProcessWorkReportingService {

    /** Approve a report — idempotent via conditional update */
    Map<String, Object> approveReport(String factoryId, Long reportId, Long approvedBy);

    /** Reject a report */
    Map<String, Object> rejectReport(String factoryId, Long reportId, String reason, Long rejectedBy);

    /** Batch approve — all or nothing */
    Map<String, Object> batchApprove(String factoryId, List<Long> reportIds, Long approvedBy);

    /** Pending approval list */
    PageResponse<Map<String, Object>> getPendingApprovals(String factoryId, Pageable pageable);

    /**
     * 报工列表（替代 legacy {@code GET /work-reporting/reports}）。
     *
     * <p>⚠️ 返回 {@link com.cretas.aims.dto.WorkReportResponse} 而不是 {@code Map} ——
     * 口径见设计卡 {@code docs/decisions/2026-08-17-legacy报工栈退役.md}：
     * 新增出口一律 DTO，存量 Map 冻结不扩散。拼字典没有编译期检查，
     * 少一个键不会有任何东西变红（2026-08-17「报工人为空」就是这么活下来的）。
     */
    org.springframework.data.domain.Page<com.cretas.aims.dto.WorkReportResponse> listReports(
            String factoryId, String reportType,
            java.time.LocalDate startDate, java.time.LocalDate endDate,
            int page, int size);

    /** 报工详情（替代 legacy {@code GET /work-reporting/reports/{id}}）。 */
    com.cretas.aims.dto.WorkReportResponse getReportDetail(String factoryId, Long reportId);

    /**
     * 报工看板汇总（替代 legacy {@code GET /work-reporting/summary}）。
     *
     * <p>⛔ <b>没有 {@code startDate} / {@code endDate}</b>：legacy 那两个参数只喂
     * 三个零消费方的字段，本次没有搬过来（逐条登记见
     * {@link com.cretas.aims.dto.WorkReportSummaryResponse}），留着参数就是一个
     * 不影响任何返回值的参数。RN 侧唯一调用点 {@code useDashboardData.ts:65}
     * 本来就是无参调用。
     */
    com.cretas.aims.dto.WorkReportSummaryResponse getSummary(String factoryId);

    /**
     * 某工序近 N 天的产量均值 / 标准差（替代 legacy
     * {@code GET /work-reporting/reports/historical-average}）。
     *
     * @param days 往前推的天数（RN 侧固定传 30）
     */
    com.cretas.aims.dto.WorkReportHistoricalAverageResponse getHistoricalAverage(
            String factoryId, String processCategory, int days);

    /** Reports by task */
    List<Map<String, Object>> getReportsByTask(String factoryId, String taskId);

    /** Worker summary for a task */
    List<WorkProcessTaskDTO.WorkerSummary> getWorkerSummaryByTask(String factoryId, String taskId);
}
