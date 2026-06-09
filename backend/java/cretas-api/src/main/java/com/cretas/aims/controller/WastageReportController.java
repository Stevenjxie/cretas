package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.CreateWastageReportRequest;
import com.cretas.aims.dto.inventory.WastageReportDTO;
import com.cretas.aims.entity.inventory.WastageReport;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.inventory.WastageReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 报损单控制器 (SP7 §4.2).
 *
 * <p>基础路径: {@code /api/mobile/{factoryId}/wastage-reports}
 *
 * <p>端点列表:
 * <ol>
 *   <li>POST /wastage-reports — 创建报损单 (DRAFT)</li>
 *   <li>GET  /wastage-reports — 分页查询（含 trackType/status 过滤）</li>
 *   <li>GET  /wastage-reports/{id} — 详情</li>
 *   <li>POST /wastage-reports/{id}/submit — 提交审批</li>
 *   <li>POST /wastage-reports/{id}/approve — 审批通过（双轨校验）</li>
 *   <li>POST /wastage-reports/{id}/reject — 驳回</li>
 *   <li>GET  /wastage-reports/pending — 按角色查待审批列表</li>
 * </ol>
 *
 * <p>双轨审批: WAREHOUSE → FINANCE; FACTORY → FACTORY_MANAGER（C1孪生坑：角色从 request.getAttribute 取）。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/wastage-reports")
@RequiredArgsConstructor
@Tag(name = "报损单管理", description = "SP7 仓库/工厂报损单双轨审批")
public class WastageReportController {

    private final WastageReportService wastageReportService;

    @PostMapping
    @Operation(summary = "创建报损单 (DRAFT)", description = "照片强制：photoUrls JSON 数组至少1张")
    public ApiResponse<WastageReportDTO> create(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody @Valid CreateWastageReportRequest req,
            HttpServletRequest request) {
        Long userId = extractUserId(request);
        log.info("SP7: 创建报损单 factoryId={} trackType={} batchId={}",
                factoryId, req.getTrackType(), req.getMaterialBatchId());
        WastageReportDTO result = wastageReportService.create(factoryId, req, userId);
        return ApiResponse.success("报损单已创建", result);
    }

    @GetMapping
    @Operation(summary = "分页查询报损单", description = "支持按 trackType/status 过滤")
    public ApiResponse<Page<WastageReportDTO>> list(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "轨道类型 WAREHOUSE/FACTORY") WastageReport.TrackType trackType,
            @RequestParam(required = false) @Parameter(description = "状态过滤") WastageReport.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<WastageReportDTO> result = wastageReportService.list(factoryId, trackType, status, pageable);
        return ApiResponse.success(result);
    }

    @GetMapping("/{reportId}")
    @Operation(summary = "查询报损单详情")
    public ApiResponse<WastageReportDTO> getDetail(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "报损单ID") String reportId) {
        WastageReportDTO result = wastageReportService.getDetail(reportId, factoryId);
        return ApiResponse.success(result);
    }

    @GetMapping("/pending")
    @Operation(summary = "按当前角色查询待审批报损单",
               description = "FINANCE → WAREHOUSE 轨 PENDING_APPROVAL；FACTORY_MANAGER → FACTORY 轨")
    public ApiResponse<Page<WastageReportDTO>> listPending(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        String role = extractRole(request);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "submittedAt"));
        Page<WastageReportDTO> result = wastageReportService.listPending(factoryId, role, pageable);
        return ApiResponse.success(result);
    }

    @PostMapping("/{reportId}/submit")
    @Operation(summary = "提交报损单审批", description = "DRAFT → PENDING_APPROVAL")
    public ApiResponse<Void> submit(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "报损单ID") String reportId,
            HttpServletRequest request) {
        Long userId = extractUserId(request);
        log.info("SP7: 提交报损单审批 reportId={}", reportId);
        wastageReportService.submit(reportId, factoryId, userId);
        return ApiResponse.success("报损单已提交审批", null);
    }

    @PostMapping("/{reportId}/approve")
    @Operation(summary = "审批通过报损单",
               description = "双轨: WAREHOUSE → FINANCE; FACTORY → FACTORY_MANAGER。原子扣减库存+写 audit trail")
    public ApiResponse<Void> approve(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "报损单ID") String reportId,
            HttpServletRequest request) {
        Long userId = extractUserId(request);
        String role = extractRole(request);
        log.info("SP7: 审批报损单通过 reportId={} role={}", reportId, role);
        wastageReportService.approve(reportId, factoryId, userId, role);
        return ApiResponse.success("报损单已审批通过，库存已扣减", null);
    }

    @PostMapping("/{reportId}/reject")
    @Operation(summary = "驳回报损单")
    public ApiResponse<Void> reject(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "报损单ID") String reportId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        Long userId = extractUserId(request);
        String role = extractRole(request);
        String reason = body.getOrDefault("reason", "");
        log.info("SP7: 驳回报损单 reportId={} role={}", reportId, role);
        wastageReportService.reject(reportId, factoryId, reason, userId, role);
        return ApiResponse.success("报损单已驳回", null);
    }

    // -------------------------------------------------------
    // private helpers (C1孪生坑: 角色从 request.getAttribute 取，非 SecurityContext)
    // -------------------------------------------------------

    private Long extractUserId(HttpServletRequest request) {
        Object userIdObj = request.getAttribute("userId");
        if (userIdObj instanceof Long) return (Long) userIdObj;
        if (userIdObj instanceof Number) return ((Number) userIdObj).longValue();
        return 0L;
    }

    private String extractRole(HttpServletRequest request) {
        String role = (String) request.getAttribute("role");
        if (role == null || role.isBlank()) {
            throw new BusinessException(401, "无法获取角色信息，请重新登录");
        }
        return role;
    }
}
