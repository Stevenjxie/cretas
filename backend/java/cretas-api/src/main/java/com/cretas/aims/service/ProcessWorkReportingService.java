package com.cretas.aims.service;

import com.cretas.aims.dto.ProcessTaskDTO;
import com.cretas.aims.dto.ProcessWorkReportSubmitRequest;
import com.cretas.aims.dto.common.PageResponse;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface ProcessWorkReportingService {

    /** Approve a report — idempotent via conditional update */
    Map<String, Object> approveReport(String factoryId, Long reportId, Long approvedBy);

    /** Reject a report */
    Map<String, Object> rejectReport(String factoryId, Long reportId, String reason, Long rejectedBy);

    /** Batch approve — all or nothing */
    Map<String, Object> batchApprove(String factoryId, List<Long> reportIds, Long approvedBy);

    /** Submit a normal report for an IN_PROGRESS task (needs approval) */
    Map<String, Object> submitNormalReport(String factoryId, Long workerId, ProcessWorkReportSubmitRequest request);

    default Map<String, Object> submitNormalReport(String factoryId, String processTaskId,
                                                   Long workerId, String reporterName,
                                                   BigDecimal outputQuantity, String notes) {
        return submitNormalReport(factoryId, workerId, ProcessWorkReportSubmitRequest.builder()
                .processTaskId(processTaskId)
                .reporterName(reporterName)
                .outputQuantity(outputQuantity)
                .notes(notes)
                .build());
    }

    /** Submit a supplemental report for a COMPLETED/CLOSED task (needs approval) */
    Map<String, Object> submitSupplement(String factoryId, Long workerId, ProcessWorkReportSubmitRequest request);

    default Map<String, Object> submitSupplement(String factoryId, String processTaskId,
                                                 Long workerId, String reporterName,
                                                 BigDecimal outputQuantity, String processCategory, String notes) {
        return submitSupplement(factoryId, workerId, ProcessWorkReportSubmitRequest.builder()
                .processTaskId(processTaskId)
                .reporterName(reporterName)
                .outputQuantity(outputQuantity)
                .processCategory(processCategory)
                .notes(notes)
                .build());
    }

    /** Create a reversal record for an already-approved report */
    Map<String, Object> createReversal(String factoryId, Long originalReportId,
                                        Long createdBy, String reason);

    /** Pending approval list */
    PageResponse<Map<String, Object>> getPendingApprovals(String factoryId, Pageable pageable);

    /** Reports by task */
    List<Map<String, Object>> getReportsByTask(String factoryId, String taskId);

    /** Worker summary for a task */
    List<ProcessTaskDTO.WorkerSummary> getWorkerSummaryByTask(String factoryId, String taskId);

    /** Calibrate completedQuantity/pendingQuantity from actual report SUMs */
    void calibrateTaskQuantities(String factoryId);
}
