package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.service.ProcessWorkReportingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/process-work-reporting")
@Tag(name = "Process work reporting", description = "Process-mode work report approval, supplement and reversal APIs")
@RequiredArgsConstructor
@RequireModule("production_report")
public class ProcessWorkReportingController {

    private final ProcessWorkReportingService service;

    @GetMapping("/pending-approval")
    @Operation(summary = "Pending process work reports")
    @RequireRole({"factory_super_admin", "permission_admin", "production_manager", "workshop_supervisor"})
    public ApiResponse<PageResponse<Map<String, Object>>> getPendingApprovals(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), size, Sort.by(Sort.Direction.ASC, "createdAt"));
        return ApiResponse.success(service.getPendingApprovals(factoryId, pageable));
    }

    @RequirePermission({"production:read_write"})
    @PutMapping("/{id}/approve")
    @Operation(summary = "Approve process work report")
    @RequireRole({"factory_super_admin", "permission_admin", "production_manager", "workshop_supervisor"})
    public ApiResponse<Map<String, Object>> approve(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @RequestAttribute("userId") Long approvedBy) {
        return ApiResponse.success(service.approveReport(factoryId, id, approvedBy));
    }

    @RequirePermission({"production:read_write"})
    @PutMapping("/{id}/reject")
    @Operation(summary = "Reject process work report")
    @RequireRole({"factory_super_admin", "permission_admin", "production_manager", "workshop_supervisor"})
    public ApiResponse<Map<String, Object>> reject(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestAttribute("userId") Long rejectedBy) {
        String reason = body.getOrDefault("reason", "");
        return ApiResponse.success(service.rejectReport(factoryId, id, reason, rejectedBy));
    }

    @RequirePermission({"production:read_write"})
    @PutMapping("/batch-approve")
    @Operation(summary = "Batch approve process work reports")
    @RequireRole({"factory_super_admin", "permission_admin", "production_manager", "workshop_supervisor"})
    public ApiResponse<Map<String, Object>> batchApprove(
            @PathVariable String factoryId,
            @RequestBody List<Long> reportIds,
            @RequestAttribute("userId") Long approvedBy) {
        return ApiResponse.success(service.batchApprove(factoryId, reportIds, approvedBy));
    }

    @GetMapping("/reports")
    @Operation(summary = "报工列表", description = "替代 legacy GET /work-reporting/reports")
    @RequirePermission("work_report:read")
    public ApiResponse<org.springframework.data.domain.Page<com.cretas.aims.dto.WorkReportResponse>> listReports(
            @PathVariable String factoryId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate startDate,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.listReports(factoryId, type, startDate, endDate, page, size));
    }

    @GetMapping("/reports/{id}")
    @Operation(summary = "报工详情", description = "替代 legacy GET /work-reporting/reports/{id}")
    @RequirePermission("work_report:read")
    public ApiResponse<com.cretas.aims.dto.WorkReportResponse> getReportDetail(
            @PathVariable String factoryId, @PathVariable Long id) {
        return ApiResponse.success(service.getReportDetail(factoryId, id));
    }

    /**
     * ⚠️ 这条路径与上面的 {@code /reports/{id}} 同前缀。Spring 的路径匹配把字面量段
     * 排在模板段之前，所以 {@code historical-average} 不会被当成 {@code id}
     * （legacy controller 里 {@code /reports/last} 与 {@code /reports/{id}} 已经这么共存过）。
     * ⛔ 这是「推理正确但没验」的典型形状，所以 {@code ProcessReportSummaryTest}
     * 用 MockMvc 真发一次请求钉住它 —— 落到错的方法上是 500，不是编译错误。
     */
    @GetMapping("/reports/historical-average")
    @Operation(summary = "工序历史均值",
            description = "替代 legacy GET /work-reporting/reports/historical-average（异常检测用）")
    @RequirePermission("work_report:read")
    public ApiResponse<com.cretas.aims.dto.WorkReportHistoricalAverageResponse> getHistoricalAverage(
            @PathVariable String factoryId,
            @RequestParam String processCategory,
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(service.getHistoricalAverage(factoryId, processCategory, days));
    }

    /**
     * 报工看板汇总。⛔ 与 legacy {@code /work-reporting/summary} 不同，
     * <b>不接受 {@code startDate} / {@code endDate}</b> —— 理由（含被砍掉的四个字段
     * 逐条登记）见 {@link com.cretas.aims.dto.WorkReportSummaryResponse}。
     */
    @GetMapping("/summary")
    @Operation(summary = "报工看板汇总", description = "替代 legacy GET /work-reporting/summary")
    @RequirePermission("work_report:read")
    public ApiResponse<com.cretas.aims.dto.WorkReportSummaryResponse> getSummary(
            @PathVariable String factoryId) {
        return ApiResponse.success(service.getSummary(factoryId));
    }

    @GetMapping("/by-task/{taskId}")
    @Operation(summary = "Reports by process task")
    public ApiResponse<List<Map<String, Object>>> getReportsByTask(
            @PathVariable String factoryId,
            @PathVariable String taskId) {
        return ApiResponse.success(service.getReportsByTask(factoryId, taskId));
    }

    @GetMapping("/by-task/{taskId}/workers")
    @Operation(summary = "Worker summary by process task")
    public ApiResponse<List<WorkProcessTaskDTO.WorkerSummary>> getWorkerSummary(
            @PathVariable String factoryId,
            @PathVariable String taskId) {
        return ApiResponse.success(service.getWorkerSummaryByTask(factoryId, taskId));
    }

}
