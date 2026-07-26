package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.labelqc.LabelQcDtos.*;
import com.cretas.aims.entity.enums.LabelQcTaskStatus;
import com.cretas.aims.service.LabelQcService;
import com.cretas.aims.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/api/mobile/{factoryId}/label-qc")
@RequireModule("quality_inspection")
@RequiredArgsConstructor
public class LabelQcController {

    private final LabelQcService labelQcService;

    @PostMapping("/tasks")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<TaskDetailResponse> create(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateTaskRequest request,
            HttpServletRequest servletRequest) {
        Long userId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "拍检任务已创建",
                labelQcService.createTask(factoryId, userId, request));
    }

    @PostMapping("/tasks/{taskId}/photos")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<PhotoResponse> addPhoto(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            @Valid @RequestBody AddPhotoRequest request,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "照片已登记",
                labelQcService.addPhoto(factoryId, taskId, request));
    }

    @PostMapping("/tasks/{taskId}/submit")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<TaskDetailResponse> submit(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "已进入 AI 初筛和人工审核队列",
                labelQcService.submit(factoryId, taskId));
    }

    @PostMapping("/tasks/{taskId}/retry")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<TaskDetailResponse> retry(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "已重新进入 AI 初筛队列",
                labelQcService.retryAnalysis(factoryId, taskId));
    }

    @GetMapping("/tasks")
    @RequirePermission({"quality:read_write", "quality:read"})
    public ApiResponse<PageResponse<TaskSummaryResponse>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) Collection<LabelQcTaskStatus> statuses,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                labelQcService.list(factoryId, statuses, archived, page, size));
    }

    @GetMapping("/tasks/status-counts")
    @RequirePermission({"quality:read_write", "quality:read"})
    public ApiResponse<StatusCountsResponse> statusCounts(
            @PathVariable String factoryId,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(labelQcService.statusCounts(factoryId));
    }

    @GetMapping("/tasks/{taskId}")
    @RequirePermission({"quality:read_write", "quality:read"})
    public ApiResponse<TaskDetailResponse> detail(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(labelQcService.detail(factoryId, taskId));
    }

    @PutMapping("/tasks/{taskId}/review")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<TaskDetailResponse> review(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            @Valid @RequestBody ReviewTaskRequest request,
            HttpServletRequest servletRequest) {
        Long reviewerId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "人工审核已完成",
                labelQcService.review(factoryId, taskId, reviewerId, request));
    }

    @PostMapping("/tasks/{taskId}/archive")
    @RequirePermission({"quality:read_write", "system:read_write"})
    public ApiResponse<TaskDetailResponse> archive(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            HttpServletRequest servletRequest) {
        Long userId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "任务已归档，可随时恢复",
                labelQcService.archive(factoryId, taskId, userId));
    }

    @PostMapping("/tasks/{taskId}/restore")
    @RequirePermission({"quality:read_write", "system:read_write"})
    public ApiResponse<TaskDetailResponse> restore(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            HttpServletRequest servletRequest) {
        Long userId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "任务已恢复",
                labelQcService.restore(factoryId, taskId, userId));
    }

    @PostMapping("/tasks/{taskId}/backup")
    @RequirePermission({"quality:read_write", "system:read_write"})
    public ApiResponse<TaskBackupResponse> backup(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            HttpServletRequest servletRequest) {
        Long userId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "备份数据已生成",
                labelQcService.exportBackup(factoryId, taskId, userId));
    }

    @PutMapping("/tasks/{taskId}/training-decision")
    @RequirePermission({"system:read_write"})
    public ApiResponse<TaskDetailResponse> decideTraining(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            @Valid @RequestBody TrainingDecisionRequest request,
            HttpServletRequest servletRequest) {
        Long technicalAdminId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                Boolean.TRUE.equals(request.approved())
                        ? "已确认进入训练集"
                        : "已拒绝进入训练集",
                labelQcService.decideTraining(
                        factoryId, taskId, technicalAdminId, request));
    }

    @GetMapping("/training-export")
    @RequirePermission({"system:read_write"})
    public ApiResponse<List<TrainingPhoto>> exportTrainingData(
            @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        if (!from.isBefore(to) || from.isBefore(to.minusDays(31))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "导出时间范围必须为 1 到 31 天");
        }
        return ApiResponse.success(
                labelQcService.exportTrainingData(factoryId, from, to, limit));
    }

    private Long trustedUser(String pathFactoryId, HttpServletRequest request) {
        Object trustedFactory = request.getAttribute("factoryId");
        if (trustedFactory != null && !pathFactoryId.equals(String.valueOf(trustedFactory))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "factory scope mismatch");
        }
        Object trustedUser = request.getAttribute("userId");
        if (trustedUser instanceof Number number) {
            return number.longValue();
        }
        if (trustedUser != null) {
            try {
                return Long.parseLong(String.valueOf(trustedUser));
            } catch (NumberFormatException ignored) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid user identity");
            }
        }
        Long securityUser = SecurityUtils.getCurrentUserId();
        if (securityUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing user identity");
        }
        return securityUser;
    }
}
