package com.cretas.aims.controller;

import com.cretas.aims.dto.disposal.ApproveDisposalRequest;
import com.cretas.aims.dto.disposal.CreateDisposalRecordRequest;
import com.cretas.aims.dto.disposal.UpdateDisposalRecordRequest;
import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.service.DisposalRecordService;
import com.cretas.aims.util.ErrorSanitizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.cretas.aims.annotation.RequireModule;

/**
 * 报废记录控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/disposal-records")
@RequiredArgsConstructor
@Tag(name = "报废管理", description = "报废记录管理相关接口，包括报废记录的创建、查询、审批、统计分析等功能，支持按类型（原材料/产品/设备）分类管理")
@RequireModule("quality_inspection")
public class DisposalController {

    private final DisposalRecordService disposalRecordService;

    /**
     * 获取报废记录列表（分页）
     */
    @GetMapping
    @Operation(summary = "获取报废记录列表", description = "分页查询指定工厂的报废记录，支持按类型筛选。返回分页信息和报废记录列表")
    public ResponseEntity<?> getDisposalRecords(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @RequestParam(defaultValue = "0") @Parameter(description = "页码（0-based）", example = "0") int page,
            @RequestParam(defaultValue = "10") @Parameter(description = "每页大小", example = "10") int size,
            @RequestParam(required = false) @Parameter(description = "报废类型过滤：material（原材料）/product（产品）/equipment（设备）", example = "material") String type) {
        try {
            Page<DisposalRecord> records;
            if (type != null && !type.isEmpty()) {
                records = disposalRecordService.getByFactoryIdAndType(factoryId, type, page, size);
            } else {
                records = disposalRecordService.getByFactoryId(factoryId, page, size);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", records.getContent());
            response.put("page", page);
            response.put("size", size);
            response.put("totalElements", records.getTotalElements());
            response.put("totalPages", records.getTotalPages());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取报废记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取单个报废记录
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取报废记录详情", description = "根据ID获取单个报废记录的详细信息，包括报废原因、数量、审批状态等")
    public ResponseEntity<?> getDisposalRecord(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @PathVariable @Parameter(description = "报废记录ID", example = "1", required = true) Long id) {
        try {
            return disposalRecordService.getById(id)
                    .map(record -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", record
                    )))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("获取报废记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 创建报废记录
     */
    @RequirePermission({"warehouse:read_write"})
    @RequireModule("quality_inspection")
    @PostMapping
    @Operation(summary = "创建报废记录", description = "创建新的报废记录，需要指定报废类型、数量、原因等信息。创建后默认状态为待审批")
    public ResponseEntity<?> createDisposalRecord(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @Valid @RequestBody @Parameter(description = "报废记录请求") CreateDisposalRecordRequest request) {
        try {
            DisposalRecord record = toDisposalRecord(request);
            record.setFactoryId(factoryId);
            DisposalRecord created = disposalRecordService.createDisposalRecord(record);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", created,
                "message", "报废记录创建成功"
            ));
        } catch (Exception e) {
            log.error("创建报废记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 更新报废记录
     */
    @RequirePermission({"warehouse:read_write"})
    @RequireModule("quality_inspection")
    @PutMapping("/{id}")
    @Operation(summary = "更新报废记录", description = "更新指定的报废记录信息。注意：已审批通过的记录不可修改")
    public ResponseEntity<?> updateDisposalRecord(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @PathVariable @Parameter(description = "报废记录ID", example = "1", required = true) Long id,
            @Valid @RequestBody @Parameter(description = "更新数据，包含需要修改的字段") UpdateDisposalRecordRequest request) {
        try {
            DisposalRecord updateData = toUpdateDisposalRecord(request);
            DisposalRecord updated = disposalRecordService.updateDisposalRecord(id, updateData);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", updated,
                "message", "报废记录更新成功"
            ));
        } catch (IllegalStateException e) {
            // 状态机拒绝 (如"已审批的记录不能修改") 的 message 是安全的用户面文案,
            // 直接返回; 不经 ErrorSanitizer (它会把 IllegalStateException 抹成通用"操作失败")。
            log.warn("更新报废记录被拒绝: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", e.getMessage() != null ? e.getMessage() : "记录当前状态不允许修改"
            ));
        } catch (Exception e) {
            log.error("更新报废记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 审批报废记录
     */
    @RequirePermission({"warehouse:read_write"})
    @RequireModule("quality_inspection")
    @PutMapping("/{id}/approve")
    @Operation(summary = "审批报废记录", description = "审批指定的报废记录，需要提供审批人信息。审批通过后记录状态变更为已审批，库存将相应减少")
    public ResponseEntity<?> approveDisposal(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @PathVariable @Parameter(description = "报废记录ID", example = "1", required = true) Long id,
            @Valid @RequestBody @Parameter(description = "审批信息") ApproveDisposalRequest request) {
        try {
            DisposalRecord approved = disposalRecordService.approveDisposal(
                    id, request.getApproverId(), request.getApproverName());
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", approved,
                "message", "报废记录审批成功"
            ));
        } catch (Exception e) {
            log.error("审批报废记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 删除报废记录
     */
    @RequirePermission({"warehouse:read_write"})
    @RequireModule("quality_inspection")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除报废记录", description = "删除指定的报废记录。注意：已审批通过的记录不可删除")
    public ResponseEntity<?> deleteDisposalRecord(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @PathVariable @Parameter(description = "报废记录ID", example = "1", required = true) Long id) {
        try {
            disposalRecordService.deleteDisposalRecord(id);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "报废记录删除成功"
            ));
        } catch (IllegalStateException e) {
            // 状态机拒绝 (如"已审批的记录不能删除") message 安全, 直接返回; 不经 ErrorSanitizer。
            log.warn("删除报废记录被拒绝: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", e.getMessage() != null ? e.getMessage() : "记录当前状态不允许删除"
            ));
        } catch (Exception e) {
            log.error("删除报废记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取待审批的报废记录
     */
    @GetMapping("/pending")
    @Operation(summary = "获取待审批报废记录", description = "获取指定工厂所有待审批的报废记录列表，用于审批人员查看待处理的审批任务")
    public ResponseEntity<?> getPendingApprovals(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId) {
        try {
            List<DisposalRecord> pending = disposalRecordService.getPendingApprovals(factoryId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", pending
            ));
        } catch (Exception e) {
            log.error("获取待审批报废记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 按日期范围查询
     */
    @GetMapping("/date-range")
    @Operation(summary = "按日期范围查询报废记录", description = "根据时间范围查询报废记录，用于统计分析和报表生成")
    public ResponseEntity<?> getByDateRange(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "开始时间", example = "2026-01-01T00:00:00", required = true) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "结束时间", example = "2026-01-31T23:59:59", required = true) LocalDateTime endDate) {
        try {
            List<DisposalRecord> records = disposalRecordService.getByDateRange(factoryId, startDate, endDate);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", records
            ));
        } catch (Exception e) {
            log.error("按日期范围查询报废记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取报废统计
     */
    @GetMapping("/stats")
    @Operation(summary = "获取报废统计", description = "获取指定时间范围内的报废统计数据，包括总数量、总金额、各类型分布等")
    public ResponseEntity<?> getStats(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "开始时间", example = "2026-01-01T00:00:00", required = true) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "结束时间", example = "2026-01-31T23:59:59", required = true) LocalDateTime endDate) {
        try {
            Map<String, Object> stats = disposalRecordService.getDisposalStats(factoryId, startDate, endDate);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", stats
            ));
        } catch (Exception e) {
            log.error("获取报废统计失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 按类型统计
     */
    @GetMapping("/stats/by-type")
    @Operation(summary = "按类型统计报废", description = "按报废类型（原材料/产品/设备）分组统计指定时间范围内的报废数据，用于分析各类型报废占比")
    public ResponseEntity<?> getStatsByType(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "开始时间", example = "2026-01-01T00:00:00", required = true) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "结束时间", example = "2026-01-31T23:59:59", required = true) LocalDateTime endDate) {
        try {
            List<Object[]> stats = disposalRecordService.getStatsByType(factoryId, startDate, endDate);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", stats
            ));
        } catch (Exception e) {
            log.error("按类型统计报废记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取可回收报废记录
     */
    @GetMapping("/recyclable")
    @Operation(summary = "获取可回收报废记录", description = "获取指定工厂所有标记为可回收的报废记录列表，便于安排回收处理")
    public ResponseEntity<?> getRecyclableDisposals(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId) {
        try {
            List<DisposalRecord> recyclable = disposalRecordService.getRecyclableDisposals(factoryId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", recyclable
            ));
        } catch (Exception e) {
            log.error("获取可回收报废记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    // ===================================================================
    // Rule 17.1 — wire→entity mappers (Issue #384 batch 5).
    // Mapper deliberately does NOT replicate service-owned defaults:
    //   - factoryId from @PathVariable (set in createDisposalRecord)
    //   - disposalDate default LocalDateTime.now() (service-owned)
    //   - isApproved default false (service-owned)
    // ===================================================================

    /**
     * Map {@link CreateDisposalRecordRequest} → {@link DisposalRecord}.
     *
     * <p>Auto-managed by service / controller path-variable / DB:
     * {@code id}, {@code factoryId}, {@code disposalDate} (fallback only),
     * {@code isApproved} (forced false), {@code approvedBy} / {@code approvedByName} /
     * {@code approvalDate} (set later via approveDisposal),
     * {@code actualLoss} (only set post-approval), {@code createdAt}, {@code updatedAt},
     * {@code deletedAt}.
     */
    private static DisposalRecord toDisposalRecord(CreateDisposalRecordRequest r) {
        DisposalRecord rec = new DisposalRecord();
        rec.setDisposalQuantity(r.getDisposalQuantity());
        rec.setDisposalType(r.getDisposalType());
        rec.setDisposalReason(r.getDisposalReason());
        rec.setDisposalDate(r.getDisposalDate());
        rec.setDisposalMethod(r.getDisposalMethod());
        rec.setQualityInspectionId(r.getQualityInspectionId());
        rec.setReworkRecordId(r.getReworkRecordId());
        rec.setProductionBatchId(r.getProductionBatchId());
        rec.setMaterialBatchId(r.getMaterialBatchId());
        rec.setEstimatedLoss(r.getEstimatedLoss());
        rec.setRecoveryValue(r.getRecoveryValue());
        rec.setEvidenceImages(r.getEvidenceImages());
        rec.setNotes(r.getNotes());
        return rec;
    }

    /**
     * Map {@link UpdateDisposalRecordRequest} → partial {@link DisposalRecord}.
     *
     * <p>The resulting entity carries only the mutable fields. Service
     * iterates each field with an {@code if (... != null)} guard, so leaving
     * a wire field absent preserves the existing DB value.
     */
    private static DisposalRecord toUpdateDisposalRecord(UpdateDisposalRecordRequest r) {
        DisposalRecord rec = new DisposalRecord();
        rec.setDisposalQuantity(r.getDisposalQuantity());
        rec.setDisposalType(r.getDisposalType());
        rec.setDisposalReason(r.getDisposalReason());
        rec.setDisposalMethod(r.getDisposalMethod());
        rec.setEstimatedLoss(r.getEstimatedLoss());
        rec.setRecoveryValue(r.getRecoveryValue());
        rec.setEvidenceImages(r.getEvidenceImages());
        rec.setNotes(r.getNotes());
        return rec;
    }
}
