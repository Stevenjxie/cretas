package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.WorkOrder;
import com.cretas.aims.service.WorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.cretas.aims.util.ErrorSanitizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.cretas.aims.annotation.RequireModule;

/**
 * 工单控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/work-orders")
@RequiredArgsConstructor
@Tag(name = "工单管理", description = "生产工单管理相关接口")
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    /**
     * 获取工单列表（分页）
     */
    @RequirePermission({"production:read"})
    @GetMapping
    @Operation(summary = "获取工单列表", description = "分页查询指定工厂的工单")
    public ResponseEntity<?> getWorkOrders(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "0") @Parameter(description = "页码") int page,
            @RequestParam(defaultValue = "10") @Parameter(description = "每页大小") int size,
            @RequestParam(required = false) @Parameter(description = "状态筛选") String status) {
        try {
            Page<WorkOrder> workOrders;
            PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

            if (status != null && !status.isEmpty()) {
                workOrders = workOrderService.getWorkOrdersByStatus(factoryId, status, pageRequest);
            } else {
                workOrders = workOrderService.getWorkOrders(factoryId, pageRequest);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", workOrders.getContent());
            response.put("page", page);
            response.put("size", size);
            response.put("totalElements", workOrders.getTotalElements());
            response.put("totalPages", workOrders.getTotalPages());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取工单列表失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取工单统计
     */
    @RequirePermission({"production:read"})
    @GetMapping("/stats")
    @Operation(summary = "获取工单统计", description = "获取工厂工单统计数据")
    public ResponseEntity<?> getStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("total", workOrderService.countByFactory(factoryId));
            stats.put("pending", workOrderService.countByStatus(factoryId, "PENDING"));
            stats.put("inProgress", workOrderService.countByStatus(factoryId, "IN_PROGRESS"));
            stats.put("completed", workOrderService.countByStatus(factoryId, "COMPLETED"));
            stats.put("cancelled", workOrderService.countByStatus(factoryId, "CANCELLED"));
            stats.put("overdue", workOrderService.getOverdueWorkOrders(factoryId).size());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", stats
            ));
        } catch (Exception e) {
            log.error("获取工单统计失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取逾期工单
     */
    @RequirePermission({"production:read"})
    @GetMapping("/overdue")
    @Operation(summary = "获取逾期工单", description = "获取未完成且已逾期的工单")
    public ResponseEntity<?> getOverdueWorkOrders(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        try {
            List<WorkOrder> overdue = workOrderService.getOverdueWorkOrders(factoryId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", overdue
            ));
        } catch (Exception e) {
            log.error("获取逾期工单失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取我的工单
     */
    @GetMapping("/my")
    @Operation(summary = "获取我的工单", description = "获取分配给当前用户的工单")
    public ResponseEntity<?> getMyWorkOrders(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<WorkOrder> workOrders = workOrderService.getWorkOrdersByAssignee(factoryId, userId, pageRequest);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", workOrders.getContent(),
                "totalElements", workOrders.getTotalElements(),
                "totalPages", workOrders.getTotalPages()
            ));
        } catch (Exception e) {
            log.error("获取我的工单失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取单个工单详情
     */
    @RequirePermission({"production:read"})
    @GetMapping("/{id}")
    @Operation(summary = "获取工单详情", description = "根据ID获取工单详情")
    public ResponseEntity<?> getWorkOrder(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "工单ID") String id) {
        try {
            return workOrderService.getWorkOrderById(factoryId, id)
                    .map(workOrder -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", workOrder
                    )))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("获取工单详情失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 创建工单
     */
    @RequirePermission({"production:read_write"})
    @RequireModule("production_plan")
    @PostMapping
    @Operation(summary = "创建工单", description = "创建新的工单")
    public ResponseEntity<?> createWorkOrder(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody @Parameter(description = "工单信息") WorkOrder workOrder) {
        try {
            workOrder.setFactoryId(factoryId);
            workOrder.setCreatedBy(userId);
            WorkOrder created = workOrderService.createWorkOrder(workOrder);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", created,
                "message", "工单创建成功"
            ));
        } catch (Exception e) {
            log.error("创建工单失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 更新工单
     */
    @RequirePermission({"production:read_write"})
    @RequireModule("production_plan")
    @PutMapping("/{id}")
    @Operation(summary = "更新工单", description = "更新工单信息")
    public ResponseEntity<?> updateWorkOrder(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "工单ID") String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody @Parameter(description = "更新数据") WorkOrder updateData) {
        try {
            updateData.setUpdatedBy(userId);
            WorkOrder updated = workOrderService.updateWorkOrder(factoryId, id, updateData);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", updated,
                "message", "工单更新成功"
            ));
        } catch (Exception e) {
            log.error("更新工单失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 开始工单
     */
    @RequirePermission({"production:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{id}/start")
    @Operation(summary = "开始工单", description = "将工单状态改为进行中")
    public ResponseEntity<?> startWorkOrder(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "工单ID") String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        try {
            WorkOrder started = workOrderService.startWorkOrder(factoryId, id, userId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", started,
                "message", "工单已开始"
            ));
        } catch (Exception e) {
            log.error("开始工单失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 完成工单
     */
    @RequirePermission({"production:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{id}/complete")
    @Operation(summary = "完成工单", description = "将工单标记为已完成")
    public ResponseEntity<?> completeWorkOrder(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "工单ID") String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        try {
            WorkOrder completed = workOrderService.completeWorkOrder(factoryId, id, userId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", completed,
                "message", "工单已完成"
            ));
        } catch (Exception e) {
            log.error("完成工单失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 取消工单
     */
    @RequirePermission({"production:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消工单", description = "取消工单")
    public ResponseEntity<?> cancelWorkOrder(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "工单ID") String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody @Parameter(description = "取消原因") Map<String, String> body) {
        try {
            String reason = body.getOrDefault("reason", "");
            WorkOrder cancelled = workOrderService.cancelWorkOrder(factoryId, id, reason, userId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", cancelled,
                "message", "工单已取消"
            ));
        } catch (Exception e) {
            log.error("取消工单失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 分配工单
     */
    @RequirePermission({"production:read_write"})
    @RequireModule("production_plan")
    @PostMapping("/{id}/assign")
    @Operation(summary = "分配工单", description = "将工单分配给指定用户")
    public ResponseEntity<?> assignWorkOrder(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "工单ID") String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long currentUserId,
            @RequestBody @Parameter(description = "分配信息") Map<String, Long> body) {
        try {
            Long assignedTo = body.get("assignedTo");
            WorkOrder assigned = workOrderService.assignWorkOrder(factoryId, id, assignedTo, currentUserId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", assigned,
                "message", "工单已分配"
            ));
        } catch (Exception e) {
            log.error("分配工单失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 删除工单
     */
    @RequirePermission({"production:read_write"})
    @RequireModule("production_plan")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除工单", description = "删除指定的工单")
    public ResponseEntity<?> deleteWorkOrder(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "工单ID") String id) {
        try {
            workOrderService.deleteWorkOrder(factoryId, id);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "工单删除成功"
            ));
        } catch (Exception e) {
            log.error("删除工单失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }
}
