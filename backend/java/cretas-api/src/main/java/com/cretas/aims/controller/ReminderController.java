package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.Reminder;
import com.cretas.aims.entity.enums.ReminderStatus;
import com.cretas.aims.service.ReminderService;
import com.cretas.aims.utils.SecurityUtils;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sprint 4 W2 S-REMIND-1 — 我的提醒 REST API.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/mobile/{factoryId}/reminders — 我的提醒列表</li>
 *   <li>GET /api/mobile/{factoryId}/reminders/badge — bell badge 计数</li>
 *   <li>POST /api/mobile/{factoryId}/reminders/{id}/snooze</li>
 *   <li>POST /api/mobile/{factoryId}/reminders/{id}/dismiss</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;

    @GetMapping
    @RequirePermission(value = {
            "sales:read", "sales:read_write",
            "finance:read", "finance:read_write",
            "system:read_write"
    })
    public ResponseEntity<ApiResponse<Page<Reminder>>> listMine(
            @PathVariable String factoryId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<ReminderStatus> statuses = parseStatuses(status);
        Page<Reminder> result = reminderService.listMine(
                factoryId, userId, statuses,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "dueDate")));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/badge")
    @RequirePermission(value = {
            "sales:read", "sales:read_write",
            "finance:read", "finance:read_write",
            "system:read_write"
    })
    public ResponseEntity<ApiResponse<Map<String, Long>>> badge(
            @PathVariable String factoryId) {
        Long userId = SecurityUtils.getCurrentUserId();
        long pending = reminderService.countPending(factoryId, userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("pending", pending)));
    }

    @PostMapping("/{id}/snooze")
    @RequirePermission(value = {
            "sales:read_write", "finance:read_write", "system:read_write"
    })
    public ResponseEntity<ApiResponse<Reminder>> snooze(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestBody @jakarta.validation.Valid SnoozeRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        Reminder updated = reminderService.snooze(factoryId, id, userId, req.getSnoozedUntil());
        return ResponseEntity.ok(ApiResponse.success("已延后", updated));
    }

    @PostMapping("/{id}/dismiss")
    @RequirePermission(value = {
            "sales:read_write", "finance:read_write", "system:read_write"
    })
    public ResponseEntity<ApiResponse<Reminder>> dismiss(
            @PathVariable String factoryId,
            @PathVariable String id) {
        Long userId = SecurityUtils.getCurrentUserId();
        Reminder updated = reminderService.dismiss(factoryId, id, userId);
        return ResponseEntity.ok(ApiResponse.success("已处理", updated));
    }

    private List<ReminderStatus> parseStatuses(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return ReminderStatus.valueOf(s);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(s -> s != null)
                .collect(Collectors.toList());
    }

    @Data
    public static class SnoozeRequest {
        @NotNull
        private LocalDate snoozedUntil;
    }
}
