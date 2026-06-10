package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.ProcessTaskDTO;
import com.cretas.aims.dto.ProcessWorkReportSubmitRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.service.ProcessWorkReportingService;
import com.cretas.aims.utils.ReportAuthGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * 提交旧式工序报工 (单步提交，无三阶段分离).
     *
     * @deprecated R8 双栈合并 (2026-06-10): RN 入口已切换到 YieldStepReportScreen (栈A，三阶段报工).
     *     新报工请走 POST /api/mobile/{factoryId}/production/batches/{batchId}/reports.
     *     本端点永久保留以支持已有历史数据查询和旧版客户端兼容，不下线，签名不变.
     */
    @Deprecated
    @RequirePermission({"production:read_write"})
    @PostMapping("/normal")
    @Operation(summary = "Submit normal process work report [DEPRECATED: use YieldReportController instead]")
    public ApiResponse<Map<String, Object>> submitNormalReport(
            @PathVariable String factoryId,
            @Valid @RequestBody ProcessWorkReportSubmitRequest body,
            @RequestAttribute("userId") Long workerId,
            @RequestAttribute(value = "role", required = false) String role) {
        Long effectiveWorkerId = effectiveWorkerId(workerId, role, body);
        return ApiResponse.success(service.submitNormalReport(factoryId, effectiveWorkerId, body));
    }

    @RequirePermission({"production:read_write"})
    @PostMapping("/supplement")
    @Operation(summary = "Submit supplemental process work report")
    public ApiResponse<Map<String, Object>> submitSupplement(
            @PathVariable String factoryId,
            @Valid @RequestBody ProcessWorkReportSubmitRequest body,
            @RequestAttribute("userId") Long workerId,
            @RequestAttribute(value = "role", required = false) String role) {
        Long effectiveWorkerId = effectiveWorkerId(workerId, role, body);
        return ApiResponse.success(service.submitSupplement(factoryId, effectiveWorkerId, body));
    }

    @RequirePermission({"production:read_write"})
    @PostMapping("/{id}/reversal")
    @Operation(summary = "Create approved report reversal")
    @RequireRole({"factory_super_admin", "permission_admin", "production_manager", "workshop_supervisor"})
    public ApiResponse<Map<String, Object>> createReversal(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @RequestAttribute("userId") Long createdBy,
            @RequestParam(required = false) String reason) {
        return ApiResponse.success(service.createReversal(factoryId, id, createdBy, reason));
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
    public ApiResponse<List<ProcessTaskDTO.WorkerSummary>> getWorkerSummary(
            @PathVariable String factoryId,
            @PathVariable String taskId) {
        return ApiResponse.success(service.getWorkerSummaryByTask(factoryId, taskId));
    }

    private Long effectiveWorkerId(Long currentWorkerId, String role, ProcessWorkReportSubmitRequest body) {
        if (ReportAuthGuard.isSupervisor(role) && body.getTargetWorkerId() != null) {
            return body.getTargetWorkerId();
        }
        return currentWorkerId;
    }
}
