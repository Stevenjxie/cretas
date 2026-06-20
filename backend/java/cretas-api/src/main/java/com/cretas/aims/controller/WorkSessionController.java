package com.cretas.aims.controller;

import com.cretas.aims.entity.EmployeeWorkSession;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.service.EmployeeWorkSessionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.cretas.aims.util.ErrorSanitizer;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 员工工作会话控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/work-sessions")
@Tag(name = "员工工作会话管理", description = "员工工作会话管理相关接口，包括工作会话的创建、查询、开始、结束、取消，用户活跃会话查询，按时间范围/工作类型筛选，会话统计及用户工时统计等功能")
@RequiredArgsConstructor
public class WorkSessionController {

    private final EmployeeWorkSessionService workSessionService;

    /**
     * 获取工作会话列表（分页）
     */
    @GetMapping
    @Operation(summary = "获取工作会话列表", description = "分页获取工厂的工作会话列表，支持按状态筛选")
    public ResponseEntity<?> getWorkSessions(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(defaultValue = "0") @Parameter(description = "页码（0-based）", example = "0") int page,
            @RequestParam(defaultValue = "10") @Parameter(description = "每页大小", example = "10") int size,
            @RequestParam(required = false) @Parameter(description = "会话状态：ACTIVE-进行中/COMPLETED-已完成/CANCELLED-已取消", example = "ACTIVE") String status) {
        try {
            Page<EmployeeWorkSession> sessions;
            if (status != null && !status.isEmpty()) {
                sessions = workSessionService.getByFactoryIdAndStatus(factoryId, status, page, size);
            } else {
                sessions = workSessionService.getByFactoryId(factoryId, page, size);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", sessions.getContent());
            response.put("page", page);
            response.put("size", size);
            response.put("totalElements", sessions.getTotalElements());
            response.put("totalPages", sessions.getTotalPages());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取工作会话失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取单个工作会话
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取工作会话详情", description = "根据ID获取工作会话的详细信息")
    public ResponseEntity<?> getWorkSession(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "工作会话ID", example = "1") Long id) {
        try {
            return workSessionService.getById(factoryId, id)
                    .map(session -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", session
                    )))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("获取工作会话失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 开始工作会话
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/start")
    @Operation(summary = "开始工作会话", description = "为员工创建并开始一个新的工作会话，同一员工只能有一个活跃会话")
    public ResponseEntity<?> startSession(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestBody @Parameter(description = "工作会话信息，包含员工ID、工作类型等") EmployeeWorkSession session) {
        try {
            session.setFactoryId(factoryId);
            EmployeeWorkSession started = workSessionService.startSession(session);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", started,
                "message", "工作会话已开始"
            ));
        } catch (IllegalStateException e) {
            log.warn("开始工作会话被拒绝: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        } catch (Exception e) {
            log.error("开始工作会话失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 结束工作会话
     */
    @RequirePermission({"production:read_write"})
    @PutMapping("/{id}/end")
    @Operation(summary = "结束工作会话", description = "结束指定的工作会话，可记录休息时间和备注，系统自动计算工时")
    public ResponseEntity<?> endSession(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "工作会话ID", example = "1") Long id,
            @RequestBody(required = false) @Parameter(description = "结束信息: {breakMinutes: 休息分钟数, notes: 备注}") Map<String, Object> body) {
        try {
            Integer breakMinutes = body != null ? (Integer) body.get("breakMinutes") : null;
            String notes = body != null ? (String) body.get("notes") : null;

            EmployeeWorkSession ended = workSessionService.endSession(factoryId, id, breakMinutes, notes);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", ended,
                "message", "工作会话已结束"
            ));
        } catch (IllegalStateException e) {
            log.warn("结束工作会话被拒绝: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        } catch (Exception e) {
            log.error("结束工作会话失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 取消工作会话
     */
    @RequirePermission({"production:read_write"})
    @PutMapping("/{id}/cancel")
    @Operation(summary = "取消工作会话", description = "取消指定的工作会话，取消后不计入工时统计")
    public ResponseEntity<?> cancelSession(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "工作会话ID", example = "1") Long id) {
        try {
            EmployeeWorkSession cancelled = workSessionService.cancelSession(factoryId, id);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", cancelled,
                "message", "工作会话已取消"
            ));
        } catch (IllegalStateException e) {
            log.warn("取消工作会话被拒绝: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        } catch (Exception e) {
            log.error("取消工作会话失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 更新工作会话
     */
    @RequirePermission({"production:read_write"})
    @PutMapping("/{id}")
    @Operation(summary = "更新工作会话", description = "更新工作会话信息，如工作类型、备注等")
    public ResponseEntity<?> updateSession(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "工作会话ID", example = "1") Long id,
            @RequestBody @Parameter(description = "更新的工作会话信息") EmployeeWorkSession updateData) {
        try {
            EmployeeWorkSession updated = workSessionService.updateSession(factoryId, id, updateData);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", updated,
                "message", "工作会话更新成功"
            ));
        } catch (IllegalStateException e) {
            log.warn("更新工作会话被拒绝: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        } catch (Exception e) {
            log.error("更新工作会话失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取用户当前活跃会话
     */
    @GetMapping("/active/{userId}")
    @Operation(summary = "获取用户当前活跃会话", description = "查询指定用户当前是否有活跃的工作会话，每个用户同时只能有一个活跃会话")
    public ResponseEntity<?> getActiveSession(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "用户ID", example = "22") Long userId) {
        try {
            return workSessionService.getActiveSession(factoryId, userId)
                    .map(session -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("data", session);
                        return ResponseEntity.ok(response);
                    })
                    .orElseGet(() -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("data", null);
                        response.put("message", "用户没有活跃的工作会话");
                        return ResponseEntity.ok(response);
                    });
        } catch (Exception e) {
            log.error("获取用户活跃会话失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取用户工作会话列表
     */
    @GetMapping("/user/{userId}")
    @Operation(summary = "获取用户工作会话列表", description = "获取指定用户的所有工作会话记录，包括已完成和进行中的会话")
    public ResponseEntity<?> getUserSessions(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "用户ID", example = "22") Long userId) {
        try {
            List<EmployeeWorkSession> sessions = workSessionService.getByUserId(userId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", sessions
            ));
        } catch (Exception e) {
            log.error("获取用户工作会话失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 按时间范围查询
     */
    @GetMapping("/date-range")
    @Operation(summary = "按时间范围查询工作会话", description = "查询指定时间范围内的所有工作会话记录")
    public ResponseEntity<?> getByDateRange(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "开始时间（ISO格式）", example = "2025-01-01T08:00:00") LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "结束时间（ISO格式）", example = "2025-01-31T18:00:00") LocalDateTime endTime) {
        try {
            List<EmployeeWorkSession> sessions = workSessionService.getByTimeRange(factoryId, startTime, endTime);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", sessions
            ));
        } catch (Exception e) {
            log.error("按时间范围查询工作会话失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 按工作类型查询
     */
    @GetMapping("/work-type/{workTypeId}")
    @Operation(summary = "按工作类型查询会话", description = "查询指定工作类型的所有工作会话记录")
    public ResponseEntity<?> getByWorkType(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "工作类型ID", example = "1") Integer workTypeId) {
        try {
            List<EmployeeWorkSession> sessions = workSessionService.getByWorkType(factoryId, workTypeId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", sessions
            ));
        } catch (Exception e) {
            log.error("按工作类型查询工作会话失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取工作会话统计
     */
    @GetMapping("/stats")
    @Operation(summary = "获取工作会话统计", description = "统计指定时间范围内的工作会话数据，包括总会话数、总工时、平均工时等")
    public ResponseEntity<?> getStats(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "开始时间（ISO格式）", example = "2025-01-01T00:00:00") LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "结束时间（ISO格式）", example = "2025-01-31T23:59:59") LocalDateTime endTime) {
        try {
            Map<String, Object> stats = workSessionService.getSessionStats(factoryId, startTime, endTime);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", stats
            ));
        } catch (Exception e) {
            log.error("获取工作会话统计失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取用户工时统计
     */
    @GetMapping("/user/{userId}/stats")
    @Operation(summary = "获取用户工时统计", description = "统计指定用户在时间范围内的工时数据，包括总工时、有效工时、休息时间等")
    public ResponseEntity<?> getUserStats(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "用户ID", example = "22") Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "开始时间（ISO格式）", example = "2025-01-01T00:00:00") LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Parameter(description = "结束时间（ISO格式）", example = "2025-01-31T23:59:59") LocalDateTime endTime) {
        try {
            Map<String, Object> stats = workSessionService.getUserWorkStats(userId, startTime, endTime);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", stats
            ));
        } catch (Exception e) {
            log.error("获取用户工时统计失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }
}
