package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.ProcessTaskDTO;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.ProcessWorkReportingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.utils.ReportAuthGuard;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/process-work-reporting")
@Tag(name = "工序报工审批", description = "PROCESS模式下的报工审批、补报、冲销管理")
@RequiredArgsConstructor
@RequireModule("production_report")
public class ProcessWorkReportingController {

    private final ProcessWorkReportingService service;

    @GetMapping("/pending-approval")
    @Operation(summary = "待审核报工列表")
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
    @Operation(summary = "审批通过(幂等)")
    @RequireRole({"factory_super_admin", "permission_admin", "production_manager", "workshop_supervisor"})
    public ApiResponse<Map<String, Object>> approve(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @RequestAttribute("userId") Long approvedBy) {
        return ApiResponse.success(service.approveReport(factoryId, id, approvedBy));
    }

    @RequirePermission({"production:read_write"})
    @PutMapping("/{id}/reject")
    @Operation(summary = "审批拒绝")
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
    @Operation(summary = "批量审批(全部成功或全部回滚)")
    @RequireRole({"factory_super_admin", "permission_admin", "production_manager", "workshop_supervisor"})
    public ApiResponse<Map<String, Object>> batchApprove(
            @PathVariable String factoryId,
            @RequestBody List<Long> reportIds,
            @RequestAttribute("userId") Long approvedBy) {
        return ApiResponse.success(service.batchApprove(factoryId, reportIds, approvedBy));
    }

    @RequirePermission({"production:read_write"})
    @PostMapping("/normal")
    @Operation(summary = "正常报工(IN_PROGRESS任务，需审批)")
    public ApiResponse<Map<String, Object>> submitNormalReport(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long workerId,
            @RequestAttribute(value = "role", required = false) String role) {
        if (body.get("processTaskId") == null) {
            throw new BusinessException(400, "缺少必填字段: processTaskId").withHint("请提供工序任务ID").withHintTarget("processTaskId");
        }
        if (body.get("outputQuantity") == null) {
            throw new BusinessException(400, "缺少必填字段: outputQuantity").withHint("请提供产出数量").withHintTarget("outputQuantity");
        }
        String processTaskId = (String) body.get("processTaskId");
        String reporterName = (String) body.getOrDefault("reporterName", "");
        BigDecimal outputQuantity = new BigDecimal(body.get("outputQuantity").toString());
        String notes = (String) body.getOrDefault("notes", null);
        // M3: targetWorkerId (代报) 仅主管可用; 操作员传则忽略, 强制为登录者。
        boolean isSupervisor = ReportAuthGuard.isSupervisor(role);
        Long effectiveWorkerId = workerId;
        if (isSupervisor && body.get("targetWorkerId") != null) {
            effectiveWorkerId = Long.valueOf(body.get("targetWorkerId").toString());
        }
        return ApiResponse.success(service.submitNormalReport(
                factoryId, processTaskId, effectiveWorkerId, reporterName, outputQuantity, notes));
    }

    @RequirePermission({"production:read_write"})
    @PostMapping("/supplement")
    @Operation(summary = "补报(COMPLETED/CLOSED任务，需审批)")
    public ApiResponse<Map<String, Object>> submitSupplement(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long workerId) {
        if (body.get("processTaskId") == null) {
            throw new BusinessException(400, "缺少必填字段: processTaskId").withHint("请提供工序任务ID").withHintTarget("processTaskId");
        }
        if (body.get("outputQuantity") == null) {
            throw new BusinessException(400, "缺少必填字段: outputQuantity").withHint("请提供产出数量").withHintTarget("outputQuantity");
        }
        String processTaskId = (String) body.get("processTaskId");
        String reporterName = (String) body.getOrDefault("reporterName", "");
        BigDecimal outputQuantity = new BigDecimal(body.get("outputQuantity").toString());
        String processCategory = (String) body.getOrDefault("processCategory", null);
        String notes = (String) body.getOrDefault("notes", null);
        return ApiResponse.success(service.submitSupplement(
                factoryId, processTaskId, workerId, reporterName, outputQuantity, processCategory, notes));
    }

    @RequirePermission({"production:read_write"})
    @PostMapping("/{id}/reversal")
    @Operation(summary = "冲销(已审批记录的数量修正)")
    @RequireRole({"factory_super_admin", "permission_admin", "production_manager", "workshop_supervisor"})
    public ApiResponse<Map<String, Object>> createReversal(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @RequestAttribute("userId") Long createdBy,
            @RequestParam(required = false) String reason) {
        return ApiResponse.success(service.createReversal(factoryId, id, createdBy, reason));
    }

    @GetMapping("/by-task/{taskId}")
    @Operation(summary = "某任务的报工记录")
    public ApiResponse<List<Map<String, Object>>> getReportsByTask(
            @PathVariable String factoryId,
            @PathVariable String taskId) {
        return ApiResponse.success(service.getReportsByTask(factoryId, taskId));
    }

    @GetMapping("/by-task/{taskId}/workers")
    @Operation(summary = "某任务的员工汇总")
    public ApiResponse<List<ProcessTaskDTO.WorkerSummary>> getWorkerSummary(
            @PathVariable String factoryId,
            @PathVariable String taskId) {
        return ApiResponse.success(service.getWorkerSummaryByTask(factoryId, taskId));
    }
}
