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

    /** Reports by task */
    List<Map<String, Object>> getReportsByTask(String factoryId, String taskId);

    /** Worker summary for a task */
    List<WorkProcessTaskDTO.WorkerSummary> getWorkerSummaryByTask(String factoryId, String taskId);
}
