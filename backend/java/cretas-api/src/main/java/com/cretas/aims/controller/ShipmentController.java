package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.service.ShipmentRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.cretas.aims.util.ErrorSanitizer;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.cretas.aims.annotation.RequireModule;

/**
 * 出货记录控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
/**
 * Bug #318 fix: method-level @RequirePermission on write methods only.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/shipments")
@RequiredArgsConstructor
@Tag(name = "出货管理", description = "出货记录管理相关接口，包括出货记录的创建、查询、状态更新、物流追踪、统计分析等功能")
public class ShipmentController {

    private final ShipmentRecordService shipmentRecordService;

    /**
     * 获取出货记录列表（分页）
     */
    @GetMapping
    @Operation(summary = "获取出货记录列表", description = "分页查询指定工厂的出货记录，支持按状态筛选。返回分页信息和出货记录列表")
    public ResponseEntity<?> getShipments(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @RequestParam(defaultValue = "0") @Parameter(description = "页码（0-based）", example = "0") int page,
            @RequestParam(defaultValue = "10") @Parameter(description = "每页大小", example = "10") int size,
            @RequestParam(required = false) @Parameter(description = "出货状态过滤", example = "pending") String status) {
        try {
            Page<ShipmentRecord> shipments;
            if (status != null && !status.isEmpty()) {
                shipments = shipmentRecordService.getByFactoryIdAndStatus(factoryId, status, page, size);
            } else {
                shipments = shipmentRecordService.getByFactoryId(factoryId, page, size);
            }

            Map<String, Object> pageData = new HashMap<>();
            pageData.put("content", shipments.getContent());
            pageData.put("totalElements", shipments.getTotalElements());
            pageData.put("totalPages", shipments.getTotalPages());
            pageData.put("page", page);
            pageData.put("size", size);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", pageData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取出货记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    // ==================== 具体路径必须在通配符 /{id} 之前 ====================

    /**
     * 按客户查询出货记录
     */
    @GetMapping("/customer/{customerId}")
    @Operation(summary = "按客户查询出货记录", description = "根据客户ID查询该客户的所有出货记录，用于查看特定客户的发货历史")
    public ResponseEntity<?> getByCustomer(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @PathVariable @Parameter(description = "客户ID", example = "CUST001", required = true) String customerId) {
        try {
            List<ShipmentRecord> shipments = shipmentRecordService.getByCustomer(factoryId, customerId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", shipments
            ));
        } catch (Exception e) {
            log.error("按客户查询出货记录失败", e);
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
    @Operation(summary = "按日期范围查询出货记录", description = "根据日期范围查询出货记录，用于统计分析和报表生成")
    public ResponseEntity<?> getByDateRange(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Parameter(description = "开始日期", example = "2026-01-01", required = true) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Parameter(description = "结束日期", example = "2026-01-31", required = true) LocalDate endDate) {
        try {
            List<ShipmentRecord> shipments = shipmentRecordService.getByDateRange(factoryId, startDate, endDate);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", shipments
            ));
        } catch (Exception e) {
            log.error("按日期范围查询出货记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 物流追踪查询
     */
    @GetMapping("/tracking/{trackingNumber}")
    @Operation(summary = "物流追踪查询", description = "根据物流追踪号查询出货记录，用于客户查询物流状态或内部追踪货物")
    public ResponseEntity<?> getByTrackingNumber(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @PathVariable @Parameter(description = "物流追踪号", example = "SF1234567890", required = true) String trackingNumber) {
        try {
            return shipmentRecordService.getByTrackingNumber(trackingNumber)
                    .map(shipment -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", shipment
                    )))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("物流追踪查询失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取出货统计
     */
    @GetMapping("/stats")
    @Operation(summary = "获取出货统计", description = "获取工厂出货统计数据，包括各状态（待发货、已发货、已送达、已退货）的出货数量汇总")
    public ResponseEntity<?> getStats(@PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId) {
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("total", shipmentRecordService.countByFactoryId(factoryId));
            stats.put("pending", shipmentRecordService.countByStatus(factoryId, "pending"));
            stats.put("shipped", shipmentRecordService.countByStatus(factoryId, "shipped"));
            stats.put("delivered", shipmentRecordService.countByStatus(factoryId, "delivered"));
            stats.put("returned", shipmentRecordService.countByStatus(factoryId, "returned"));

            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", stats
            ));
        } catch (Exception e) {
            log.error("获取出货统计失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取最近出货记录
     */
    @GetMapping("/recent")
    @Operation(summary = "获取最近出货记录", description = "获取最近N条出货记录，按创建时间倒序排列，用于首页快速查看或仪表盘展示")
    public ResponseEntity<?> getRecentShipments(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @RequestParam(defaultValue = "10") @Parameter(description = "返回记录数量，默认10条", example = "10") int limit) {
        try {
            // 获取最近N条出货记录
            Page<ShipmentRecord> shipments = shipmentRecordService.getByFactoryId(factoryId, 0, limit);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", shipments.getContent()
            ));
        } catch (Exception e) {
            log.error("获取最近出货记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    // ==================== 通配符路径放在最后 ====================

    /**
     * 获取单个出货记录
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取出货记录详情", description = "根据ID获取单个出货记录的详细信息，包括客户信息、物流追踪号、发货状态等")
    public ResponseEntity<?> getShipment(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @PathVariable @Parameter(description = "出货记录ID（UUID格式）", example = "550e8400-e29b-41d4-a716-446655440000", required = true) String id) {
        try {
            return shipmentRecordService.getById(id)
                    .map(shipment -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", shipment
                    )))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("获取出货记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 创建出货记录
     */
    @RequirePermission({"sales:read_write", "inventory:read_write", "warehouse:read_write"})
    @RequireModule("sales_order")
    @PostMapping
    @Operation(summary = "创建出货记录", description = "创建新的出货记录，需要指定客户信息、发货产品、数量等。系统将自动记录创建人和创建时间")
    public ResponseEntity<?> createShipment(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @Valid @RequestBody @Parameter(description = "出货记录信息，包含customerId、productBatchId、quantity、shippingAddress等") ShipmentRecord shipment) {
        try {
            shipment.setFactoryId(factoryId);
            shipment.setRecordedBy(userId);
            ShipmentRecord created = shipmentRecordService.createShipment(shipment);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", created,
                "message", "出货记录创建成功"
            ));
        } catch (com.cretas.aims.exception.BusinessException be) {
            // 让业务异常 (含 R4 去重 409 / 状态守卫) 透传 GlobalExceptionHandler 正确映射状态码,
            // 不被下面的 catch-all 降级成 400 (edge-case 审计 2026-06-24)。
            throw be;
        } catch (Exception e) {
            log.error("创建出货记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 更新出货记录
     */
    @RequirePermission({"sales:read_write", "inventory:read_write", "warehouse:read_write"})
    @RequireModule("sales_order")
    @PutMapping("/{id}")
    @Operation(summary = "更新出货记录", description = "更新指定的出货记录信息，可修改发货地址、联系方式、备注等字段")
    public ResponseEntity<?> updateShipment(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @PathVariable @Parameter(description = "出货记录ID（UUID格式）", example = "550e8400-e29b-41d4-a716-446655440000", required = true) String id,
            @RequestBody @Parameter(description = "更新数据，包含需要修改的字段") ShipmentRecord updateData) {
        try {
            ShipmentRecord updated = shipmentRecordService.updateShipment(id, updateData);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", updated,
                "message", "出货记录更新成功"
            ));
        } catch (com.cretas.aims.exception.BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("更新出货记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 更新出货状态
     */
    @RequirePermission({"sales:read_write", "inventory:read_write", "warehouse:read_write"})
    @RequireModule("sales_order")
    @PutMapping("/{id}/status")
    @Operation(summary = "更新出货状态", description = "更新出货记录的状态，支持的状态包括：pending（待发货）、shipped（已发货）、delivered（已送达）、returned（已退货）")
    public ResponseEntity<?> updateStatus(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @PathVariable @Parameter(description = "出货记录ID（UUID格式）", example = "550e8400-e29b-41d4-a716-446655440000", required = true) String id,
            @RequestBody @Parameter(description = "状态信息 {\"status\": \"shipped\"}，可选值: pending/shipped/delivered/returned") Map<String, String> body) {
        try {
            String status = body.get("status");
            ShipmentRecord updated = shipmentRecordService.updateStatus(id, status);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", updated,
                "message", "状态更新成功"
            ));
        } catch (com.cretas.aims.exception.BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("更新出货状态失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 删除出货记录
     */
    @RequirePermission({"sales:read_write", "inventory:read_write", "warehouse:read_write"})
    @RequireModule("sales_order")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除出货记录", description = "删除指定的出货记录，已发货或已送达的记录建议保留以便追溯")
    public ResponseEntity<?> deleteShipment(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @PathVariable @Parameter(description = "出货记录ID（UUID格式）", example = "550e8400-e29b-41d4-a716-446655440000", required = true) String id) {
        try {
            shipmentRecordService.deleteShipment(id);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "出货记录删除成功"
            ));
        } catch (com.cretas.aims.exception.BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("删除出货记录失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }
}
