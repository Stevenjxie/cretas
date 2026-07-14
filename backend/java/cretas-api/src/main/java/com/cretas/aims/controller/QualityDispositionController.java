package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.quality.*;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import com.cretas.aims.service.QualityDispositionRuleService;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionResult;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionExecutionResult;
import com.cretas.aims.service.StateMachineService;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.exception.BusinessException;

/**
 * 质检处置规则控制器
 *
 * 功能：
 * 1. 根据质检结果评估处置建议
 * 2. 获取可用的处置动作列表
 * 3. 执行处置动作（集成状态机门禁）
 * 4. 查看处置历史
 * 5. 配置处置规则
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/quality-disposition")
@RequiredArgsConstructor
@Tag(name = "质检处置管理", description = "质检处置规则与执行管理")
@RequireModule("quality_inspection")
public class QualityDispositionController {

    private final QualityDispositionRuleService qualityDispositionRuleService;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final DecisionAuditLogRepository decisionAuditLogRepository;
    private final StateMachineService stateMachineService;
    private final NotificationService notificationService;
    private final PushNotificationService pushNotificationService;

    /**
     * 根据质检结果评估处置建议
     *
     * 业务规则：
     * 1. 合格率 >= 95%: RELEASE (无需审批)
     * 2. 合格率 80-95%: REWORK (主管审批)
     * 3. 合格率 60-80%: DOWNGRADE (经理审批)
     * 4. 合格率 < 60%: SCRAP (质量主管审批)
     * 5. 存在安全隐患: HOLD (立即暂停，等待高层决定)
     */
    @RequirePermission({"quality:read_write"})
    @RequireModule("quality_inspection")
    @PostMapping("/evaluate")
    @Operation(summary = "评估质检处置", description = "根据质检结果评估推荐的处置动作")
    public ApiResponse<DispositionEvaluationDTO> evaluateDisposition(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @Valid @RequestBody @Parameter(description = "质检结果") QualityCheckResultDTO qualityResult) {

        log.info("评估质检处置 - factoryId={}, inspectionId={}", factoryId, qualityResult.getInspectionId());

        // 查找质检记录
        QualityInspection inspection = qualityInspectionRepository.findById(qualityResult.getInspectionId())
                .orElseThrow(() -> new EntityNotFoundException("Quality inspection", qualityResult.getInspectionId()));

        // 评估处置
        DispositionResult result = qualityDispositionRuleService.evaluateDisposition(factoryId, inspection);

        // 转换为 DTO
        DispositionEvaluationDTO dto = DispositionEvaluationDTO.builder()
                .inspectionId(inspection.getId())
                .productionBatchId(inspection.getProductionBatchId())
                .recommendedAction(result.getRecommendedAction().name())
                .recommendedActionDescription(result.getRecommendedAction().getDescription())
                .requiresApproval(result.isRequiresApproval())
                .triggeredRuleName(result.getTriggeredRuleName())
                .ruleConfigId(result.getRuleConfigId())
                .ruleVersion(result.getRuleVersion())
                .confidence(result.getConfidence())
                .reason(result.getReason())
                .alternativeActions(Arrays.stream(result.getAlternativeActions())
                        .map(action -> DispositionEvaluationDTO.AlternativeAction.builder()
                                .action(action.name())
                                .description(action.getDescription())
                                .requiresApproval(true)
                                .build())
                        .collect(Collectors.toList()))
                .inspectionSummary(DispositionEvaluationDTO.InspectionSummary.builder()
                        .passRate(inspection.getPassRate())
                        .defectRate(inspection.getDefectRate())
                        .inspectionResult(inspection.getResult())
                        .qualityGrade(inspection.getQualityGrade())
                        .sampleSize(inspection.getSampleSize())
                        .passCount(inspection.getPassCount())
                        .failCount(inspection.getFailCount())
                        .build())
                .build();

        log.info("质检处置评估完成 - action={}, requiresApproval={}",
                result.getRecommendedAction(), result.isRequiresApproval());

        return ApiResponse.success(dto);
    }

    /**
     * 获取可用的处置动作列表
     */
    @GetMapping("/actions")
    @Operation(summary = "获取可用处置动作", description = "获取所有可用的质检处置动作列表")
    public ApiResponse<List<DispositionActionDTO>> getAvailableActions(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId) {

        log.info("获取可用处置动作 - factoryId={}", factoryId);

        List<DispositionActionDTO> actions = new ArrayList<>();

        for (DispositionAction action : DispositionAction.values()) {
            DispositionActionDTO dto = DispositionActionDTO.builder()
                    .actionCode(action.name())
                    .actionName(action.getDescription())
                    .description(getActionFullDescription(action))
                    .requiresApproval(getActionRequiresApproval(action))
                    .approvalLevel(getActionApprovalLevel(action))
                    .applicableCondition(getActionApplicableCondition(action))
                    .build();
            actions.add(dto);
        }

        return ApiResponse.success(actions);
    }

    /**
     * 执行处置动作
     *
     * 步骤：
     * 1. 验证状态机转换合法性
     * 2. 执行处置动作
     * 3. 记录审计日志
     * 4. 发送通知
     */
    @RequirePermission({"quality:read_write"})
    @RequireModule("quality_inspection")
    @PostMapping("/execute")
    @Operation(summary = "执行处置动作", description = "执行质检处置动作，集成状态机门禁")
    public ApiResponse<DispositionResultDTO> executeDisposition(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @Valid @RequestBody @Parameter(description = "执行处置请求") ExecuteDispositionRequest request) {

        log.info("执行质检处置 - factoryId={}, batchId={}, action={}",
                factoryId, request.getBatchId(), request.getActionCode());

        // 查找质检记录
        QualityInspection inspection = qualityInspectionRepository.findById(request.getInspectionId())
                .orElseThrow(() -> new EntityNotFoundException("Quality inspection", request.getInspectionId()));

        // 解析处置动作
        DispositionAction action;
        try {
            action = DispositionAction.valueOf(request.getActionCode());
        } catch (BusinessException be) {
            throw be;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "无效的处置动作: " + request.getActionCode());
        }

        // 构建处置原因
        String reason = request.getOperatorComment() != null
                ? request.getOperatorComment()
                : "质检处置: " + action.getDescription();

        // 执行处置
        DispositionExecutionResult executionResult = qualityDispositionRuleService.executeDisposition(
                factoryId,
                inspection,
                action,
                request.getExecutorId(),
                reason
        );

        // 发送通知
        try {
            if (executionResult.isApprovalInitiated()) {
                notificationService.sendNotification(
                        factoryId,
                        "QUALITY_APPROVAL_REQUIRED",
                        "质检处置需要审批",
                        String.format("批次 %d 的质检处置（%s）需要审批",
                                request.getBatchId(), action.getDescription()),
                        Map.of("batchId", request.getBatchId(),
                                "action", action.name(),
                                "approvalRequestId", executionResult.getApprovalRequestId())
                );
            } else {
                notificationService.sendNotification(
                        factoryId,
                        "QUALITY_DISPOSITION_EXECUTED",
                        "质检处置已执行",
                        String.format("批次 %d 已完成质检处置: %s",
                                request.getBatchId(), action.getDescription()),
                        Map.of("batchId", request.getBatchId(),
                                "action", action.name(),
                                "newStatus", executionResult.getNewBatchStatus())
                );
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("发送处置通知失败", e);
        }

        // 转换为 DTO
        DispositionResultDTO dto = DispositionResultDTO.builder()
                .dispositionId(executionResult.getAuditLogId())
                .status(executionResult.isApprovalInitiated() ? "PENDING_APPROVAL" : "EXECUTED")
                .executedAction(executionResult.getExecutedAction().name())
                .message(executionResult.getMessage())
                .nextSteps(buildNextSteps(executionResult))
                .newBatchStatus(executionResult.getNewBatchStatus())
                .approvalInitiated(executionResult.isApprovalInitiated())
                .approvalRequestId(executionResult.getApprovalRequestId())
                .auditLogId(executionResult.getAuditLogId())
                .executedAt(LocalDateTime.now())
                .build();

        log.info("质检处置执行完成 - dispositionId={}, status={}",
                dto.getDispositionId(), dto.getStatus());

        return ApiResponse.success(dto);
    }

    /**
     * 获取处置历史
     * 优化：使用批量查询避免 N+1 问题
     */
    @GetMapping("/history/{batchId}")
    @Operation(summary = "获取处置历史", description = "获取指定批次的质检处置历史记录")
    public ApiResponse<List<DispositionHistoryDTO>> getDispositionHistory(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "生产批次ID", example = "1") Long batchId) {

        log.info("获取处置历史 - factoryId={}, batchId={}", factoryId, batchId);

        // 查找该批次的所有质检记录
        List<QualityInspection> inspections = qualityInspectionRepository
                .findByFactoryIdAndProductionBatchId(factoryId, batchId);

        if (inspections.isEmpty()) {
            return ApiResponse.success(Collections.emptyList());
        }

        // 优化：批量查询所有质检记录的审计日志，避免 N+1 问题
        Set<String> inspectionIds = inspections.stream()
                .map(QualityInspection::getId)
                .collect(Collectors.toSet());

        List<DecisionAuditLog> allAuditLogs = decisionAuditLogRepository
                .findByFactoryIdAndEntityTypeAndEntityIdIn(factoryId, "QualityInspection", inspectionIds);

        // 构建 inspectionId -> List<DecisionAuditLog> 的映射
        Map<String, List<DecisionAuditLog>> auditLogsByInspectionId = allAuditLogs.stream()
                .collect(Collectors.groupingBy(DecisionAuditLog::getEntityId));

        // 构建 inspectionId -> QualityInspection 的映射
        Map<String, QualityInspection> inspectionMap = inspections.stream()
                .collect(Collectors.toMap(QualityInspection::getId, Function.identity()));

        List<DispositionHistoryDTO> history = new ArrayList<>();

        for (QualityInspection inspection : inspections) {
            List<DecisionAuditLog> auditLogs = auditLogsByInspectionId.getOrDefault(
                    inspection.getId(), Collections.emptyList());

            for (DecisionAuditLog auditLog : auditLogs) {
                DispositionHistoryDTO dto = DispositionHistoryDTO.builder()
                        .id(auditLog.getId())
                        .batchId(batchId)
                        .inspectionId(inspection.getId())
                        .action(auditLog.getDecisionMade())
                        .actionDescription(auditLog.getDecisionMade())
                        .reason(auditLog.getReason())
                        .passRate(inspection.getPassRate())
                        .defectRate(inspection.getDefectRate())
                        .qualityGrade(inspection.getQualityGrade())
                        .executorId(auditLog.getExecutorId())
                        .executorName(auditLog.getExecutorName())
                        .executorRole(auditLog.getExecutorRole())
                        .requiresApproval(auditLog.getRequiresApproval())
                        .approvalStatus(auditLog.getApprovalStatus() != null
                                ? auditLog.getApprovalStatus().name()
                                : null)
                        .approverName(auditLog.getApproverName())
                        .approvedAt(auditLog.getApprovedAt())
                        .newStatus(auditLog.getNewState())
                        .createdAt(auditLog.getCreatedAt())
                        .build();
                history.add(dto);
            }
        }

        // 按时间倒序排序
        history.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        return ApiResponse.success(history);
    }

    /**
     * 配置处置规则
     */
    @RequirePermission({"quality:read_write"})
    @RequireModule("quality_inspection")
    @PostMapping("/rules")
    @Operation(summary = "创建处置规则", description = "创建新的质检处置规则")
    public ApiResponse<DispositionRuleDTO> createRule(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @Valid @RequestBody @Parameter(description = "规则创建请求") CreateDispositionRuleRequest request) {

        log.info("创建处置规则 - factoryId={}, ruleName={}", factoryId, request.getRuleName());

        // 注意：这里应该有一个 DispositionRuleEntity 和对应的 Service
        // 当前实现只是返回一个示例响应
        // TODO: 实现完整的规则配置功能

        DispositionRuleDTO dto = DispositionRuleDTO.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .ruleName(request.getRuleName())
                .description(request.getDescription())
                .minPassRate(request.getMinPassRate())
                .maxDefectRate(request.getMaxDefectRate())
                .action(request.getAction())
                .requiresApproval(request.getRequiresApproval())
                .approvalLevel(request.getApprovalLevel())
                .priority(request.getPriority())
                .version(1)
                .enabled(request.getEnabled())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        log.warn("处置规则配置功能尚未完全实现，返回模拟数据");

        return ApiResponse.success(dto);
    }

    /**
     * 获取处置规则列表
     */
    @GetMapping("/rules")
    @Operation(summary = "获取处置规则列表", description = "获取工厂的所有质检处置规则")
    public ApiResponse<List<DispositionRuleDTO>> getRules(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId) {

        log.info("获取处置规则列表 - factoryId={}", factoryId);

        // TODO: 实现从数据库读取规则配置
        // 当前返回默认规则示例

        List<DispositionRuleDTO> rules = new ArrayList<>();

        rules.add(DispositionRuleDTO.builder()
                .id("rule-1")
                .factoryId(factoryId)
                .ruleName("高合格率自动放行")
                .description("当合格率 >= 95% 时自动放行")
                .minPassRate(new BigDecimal("95.00"))
                .action("RELEASE")
                .requiresApproval(false)
                .priority(10)
                .version(1)
                .enabled(true)
                .build());

        rules.add(DispositionRuleDTO.builder()
                .id("rule-2")
                .factoryId(factoryId)
                .ruleName("中等合格率返工")
                .description("当合格率在 70-95% 之间时需要返工")
                .minPassRate(new BigDecimal("70.00"))
                .action("REWORK")
                .requiresApproval(true)
                .approvalLevel("SUPERVISOR")
                .priority(5)
                .version(1)
                .enabled(true)
                .build());

        rules.add(DispositionRuleDTO.builder()
                .id("rule-3")
                .factoryId(factoryId)
                .ruleName("高缺陷率报废")
                .description("当缺陷率 >= 50% 时建议报废")
                .maxDefectRate(new BigDecimal("50.00"))
                .action("SCRAP")
                .requiresApproval(true)
                .approvalLevel("QUALITY_HEAD")
                .priority(8)
                .version(1)
                .enabled(true)
                .build());

        log.warn("处置规则列表功能尚未完全实现，返回模拟数据");

        return ApiResponse.success(rules);
    }

    // ==================== 待处置列表与审批 ====================

    /**
     * 获取待处置列表
     * 返回所有需要审批或处理的质检记录
     */
    @GetMapping("/pending")
    @Operation(summary = "获取待处置列表", description = "获取所有待处理的质检处置请求")
    public ApiResponse<List<PendingDispositionDTO>> getPendingDispositions(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(required = false) @Parameter(description = "按处置动作筛选(RELEASE/CONDITIONAL_RELEASE/REWORK/SCRAP/SPECIAL_APPROVAL/HOLD)") String decisionMade) {

        log.info("获取待处置列表 - factoryId={}, decisionMade={}", factoryId, decisionMade);

        // 使用专门的查询方法获取质检待审批列表 (可选按处置动作过滤, 新重载不影响其他调用方)
        List<DecisionAuditLog> pendingLogs = decisionAuditLogRepository
                .findQualityPendingApprovals(factoryId, decisionMade);

        // 优化：批量查询质检记录避免 N+1 问题
        Set<String> entityIds = pendingLogs.stream()
                .map(DecisionAuditLog::getEntityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, QualityInspection> inspectionMap = entityIds.isEmpty() ? Collections.emptyMap() :
                qualityInspectionRepository.findAllById(entityIds).stream()
                        .collect(Collectors.toMap(QualityInspection::getId, Function.identity()));

        List<PendingDispositionDTO> pendingList = pendingLogs.stream()
                .map(auditLog -> {
                    PendingDispositionDTO dto = new PendingDispositionDTO();
                    dto.setId(auditLog.getId());
                    dto.setEntityType(auditLog.getEntityType());
                    dto.setEntityId(auditLog.getEntityId());
                    dto.setDecisionMade(auditLog.getDecisionMade());
                    dto.setReason(auditLog.getReason());
                    dto.setExecutorId(auditLog.getExecutorId());
                    dto.setExecutorName(auditLog.getExecutorName());
                    dto.setExecutorRole(auditLog.getExecutorRole());
                    dto.setRuleConfigName(auditLog.getRuleConfigName());
                    dto.setCreatedAt(auditLog.getCreatedAt());

                    // 从预加载的 Map 中获取质检记录并提取批次信息
                    if (auditLog.getEntityId() != null) {
                        QualityInspection inspection = inspectionMap.get(auditLog.getEntityId());
                        if (inspection != null) {
                            dto.setProductionBatchId(inspection.getProductionBatchId());
                        }
                    }

                    // 根据处置动作推断审批级别
                    dto.setApprovalLevel(getApprovalLevelForAction(auditLog.getDecisionMade()));

                    return dto;
                })
                .collect(Collectors.toList());

        return ApiResponse.success(pendingList);
    }

    /**
     * 申请处置
     * 创建一个新的处置申请，需要后续审批
     */
    @RequirePermission({"quality:read_write"})
    @RequireModule("quality_inspection")
    @PostMapping("/apply")
    @Operation(summary = "申请处置", description = "提交质检处置申请")
    public ApiResponse<DispositionApplicationDTO> applyDisposition(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @Valid @RequestBody @Parameter(description = "处置申请请求") DispositionApplyRequest request) {

        log.info("申请处置 - factoryId={}, batchId={}, action={}",
                factoryId, request.getBatchId(), request.getActionCode());

        // 获取审批级别
        String approvalLevel = getApprovalLevelForAction(request.getActionCode());

        // 创建审计日志记录申请
        DecisionAuditLog auditLog = DecisionAuditLog.builder()
                .factoryId(factoryId)
                .entityType("QualityDisposition")
                .entityId(request.getInspectionId())
                .decisionType(DecisionAuditLog.DecisionType.APPROVAL)
                .decisionMade(request.getActionCode())
                .reason(request.getReason())
                .executorId(request.getApplicantId())
                .executorName(request.getApplicantName())
                .executionMode(DecisionAuditLog.ExecutionMode.MANUAL)
                .requiresApproval(true)
                .approvalStatus(DecisionAuditLog.ApprovalStatus.PENDING)
                .previousState("PENDING_REVIEW")
                .newState("PENDING_APPROVAL")
                .build();

        auditLog = decisionAuditLogRepository.save(auditLog);

        // 发送审批通知
        try {
            notificationService.sendNotification(
                    factoryId,
                    "QUALITY_DISPOSITION_APPLY",
                    "质检处置申请",
                    String.format("批次 %d 的质检处置申请（%s）需要审批",
                            request.getBatchId(), request.getActionCode()),
                    Map.of("applicationId", auditLog.getId(),
                            "batchId", request.getBatchId(),
                            "action", request.getActionCode())
            );
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("发送处置申请通知失败", e);
        }

        // 发送质检处置申请推送通知
        try {
            Map<String, Object> pushData = new HashMap<>();
            pushData.put("type", "quality_disposition_apply");
            pushData.put("applicationId", auditLog.getId());
            pushData.put("batchId", request.getBatchId());
            pushData.put("actionCode", request.getActionCode());
            pushData.put("screen", "QualityDispositionApproval");

            // 发送到工厂相关人员（审批人）
            Long inspectionIdLong = null;
            try {
                inspectionIdLong = Long.parseLong(request.getInspectionId());
            } catch (BusinessException be) {
                throw be;
            } catch (NumberFormatException e) {
                log.warn("无法解析 inspectionId: {}", request.getInspectionId());
            }
            pushNotificationService.sendQualityNotification(
                    request.getApplicantId(), // 暂时发给申请人，实际应发给审批人
                    inspectionIdLong,
                    request.getActionCode(),
                    String.format("批次 %d 的质检处置申请需要审批", request.getBatchId())
            );
            log.info("质检处置申请推送通知已发送: applicationId={}", auditLog.getId());
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("发送质检处置申请推送通知失败", e);
        }

        DispositionApplicationDTO dto = DispositionApplicationDTO.builder()
                .applicationId(auditLog.getId())
                .batchId(request.getBatchId())
                .inspectionId(request.getInspectionId())
                .actionCode(request.getActionCode())
                .status("PENDING_APPROVAL")
                .approvalLevel(approvalLevel)
                .applicantId(request.getApplicantId())
                .applicantName(request.getApplicantName())
                .createdAt(auditLog.getCreatedAt())
                .message("处置申请已提交，等待审批")
                .build();

        log.info("处置申请已创建 - applicationId={}", auditLog.getId());

        return ApiResponse.success(dto);
    }

    /**
     * 审批处置申请
     */
    @RequirePermission({"quality:read_write"})
    @RequireModule("quality_inspection")
    @PostMapping("/{id}/approve")
    @Operation(summary = "审批处置", description = "审批或拒绝质检处置申请")
    public ApiResponse<DispositionApprovalResultDTO> approveDisposition(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "处置申请ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890") String id,
            @Valid @RequestBody @Parameter(description = "审批请求") DispositionApproveRequest request) {

        log.info("审批处置 - factoryId={}, id={}, approved={}", factoryId, id, request.getApproved());

        // 查找审计日志
        DecisionAuditLog auditLog = decisionAuditLogRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Decision audit log", id));

        // 验证工厂ID
        if (!auditLog.getFactoryId().equals(factoryId)) {
            throw new BusinessException(400, "无权审批此处置申请");
        }

        // 更新审批状态
        auditLog.setApprovalStatus(request.getApproved()
                ? DecisionAuditLog.ApprovalStatus.APPROVED
                : DecisionAuditLog.ApprovalStatus.REJECTED);
        auditLog.setApproverId(request.getApproverId());
        auditLog.setApproverName(request.getApproverName());
        auditLog.setApprovalComment(request.getComment());
        auditLog.setApprovedAt(LocalDateTime.now());

        if (request.getApproved()) {
            auditLog.setNewState("APPROVED");
        } else {
            auditLog.setNewState("REJECTED");
        }

        auditLog = decisionAuditLogRepository.save(auditLog);

        // 获取批次ID用于通知
        Long productionBatchId = null;
        String executionStatus = null;
        String newBatchStatus = null;

        // 如果批准，执行处置动作
        if (request.getApproved() && auditLog.getEntityId() != null) {
            try {
                QualityInspection inspection = qualityInspectionRepository.findById(auditLog.getEntityId())
                        .orElse(null);
                if (inspection != null) {
                    productionBatchId = inspection.getProductionBatchId();
                    DispositionAction action = DispositionAction.valueOf(auditLog.getDecisionMade());
                    var executionResult = qualityDispositionRuleService.executeDisposition(
                            factoryId, inspection, action,
                            request.getApproverId(),
                            "审批通过后执行: " + request.getComment()
                    );
                    executionStatus = "EXECUTED";
                    newBatchStatus = executionResult.getNewBatchStatus();
                }
            } catch (BusinessException be) {
                throw be;
            } catch (Exception e) {
                log.warn("执行处置动作失败", e);
                executionStatus = "EXECUTION_FAILED";
            }
        }

        // 发送通知
        try {
            String notificationMessage = productionBatchId != null
                    ? String.format("批次 %d 的质检处置申请已%s", productionBatchId, request.getApproved() ? "批准" : "拒绝")
                    : String.format("质检处置申请 %s 已%s", id, request.getApproved() ? "批准" : "拒绝");

            Map<String, Object> notificationData = new HashMap<>();
            notificationData.put("applicationId", id);
            notificationData.put("approved", request.getApproved());
            if (productionBatchId != null) {
                notificationData.put("batchId", productionBatchId);
            }

            notificationService.sendNotification(
                    factoryId,
                    request.getApproved() ? "QUALITY_DISPOSITION_APPROVED" : "QUALITY_DISPOSITION_REJECTED",
                    request.getApproved() ? "质检处置已批准" : "质检处置已拒绝",
                    notificationMessage,
                    notificationData
            );
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("发送审批通知失败", e);
        }

        // 发送审批结果推送通知给申请人
        try {
            Long applicantId = auditLog.getExecutorId();
            if (applicantId != null) {
                Long entityIdLong = null;
                try {
                    entityIdLong = Long.parseLong(auditLog.getEntityId());
                } catch (BusinessException be) {
                    throw be;
                } catch (NumberFormatException e) {
                    log.warn("无法解析 entityId: {}", auditLog.getEntityId());
                }
                String resultText = request.getApproved() ? "已批准" : "已拒绝";
                pushNotificationService.sendQualityNotification(
                        applicantId,
                        entityIdLong,
                        request.getApproved() ? "APPROVED" : "REJECTED",
                        String.format("您的质检处置申请%s: %s", resultText,
                                request.getComment() != null ? request.getComment() : auditLog.getDecisionMade())
                );
                log.info("质检处置审批结果推送通知已发送: applicationId={}, applicantId={}", id, applicantId);
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("发送审批结果推送通知失败", e);
        }

        DispositionApprovalResultDTO dto = DispositionApprovalResultDTO.builder()
                .applicationId(id)
                .batchId(productionBatchId)
                .approved(request.getApproved())
                .approverId(request.getApproverId())
                .approverName(request.getApproverName())
                .comment(request.getComment())
                .approvedAt(auditLog.getApprovedAt())
                .executionStatus(executionStatus)
                .newBatchStatus(newBatchStatus)
                .message(request.getApproved() ? "处置申请已批准" : "处置申请已拒绝")
                .build();

        log.info("处置审批完成 - id={}, approved={}", id, request.getApproved());

        return ApiResponse.success(dto);
    }

    /**
     * 获取处置动作对应的审批级别
     */
    private String getApprovalLevelForAction(String actionCode) {
        try {
            DispositionAction action = DispositionAction.valueOf(actionCode);
            return getActionApprovalLevel(action);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            return "SUPERVISOR";
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 获取动作完整描述
     */
    private String getActionFullDescription(DispositionAction action) {
        switch (action) {
            case RELEASE:
                return "直接放行 - 质检合格，允许进入下一工序";
            case CONDITIONAL_RELEASE:
                return "条件放行 - 有限期或有条件使用";
            case REWORK:
                return "返工 - 可修复的不合格品，需要重新加工";
            case SCRAP:
                return "报废 - 无法修复的严重不合格品";
            case SPECIAL_APPROVAL:
                return "特批申请 - 超出常规规则，需要高级审批";
            case HOLD:
                return "待定 - 需要进一步检测或评估";
            default:
                return action.getDescription();
        }
    }

    /**
     * 判断动作是否需要审批
     */
    private Boolean getActionRequiresApproval(DispositionAction action) {
        switch (action) {
            case RELEASE:
                return false;
            case CONDITIONAL_RELEASE:
            case REWORK:
                return true;
            case SCRAP:
            case SPECIAL_APPROVAL:
            case HOLD:
                return true;
            default:
                return true;
        }
    }

    /**
     * 获取审批级别
     */
    private String getActionApprovalLevel(DispositionAction action) {
        switch (action) {
            case RELEASE:
                return null;
            case CONDITIONAL_RELEASE:
            case REWORK:
                return "SUPERVISOR";
            case SCRAP:
                return "QUALITY_HEAD";
            case SPECIAL_APPROVAL:
                return "FACTORY_MANAGER";
            case HOLD:
                return "MANAGER";
            default:
                return "SUPERVISOR";
        }
    }

    /**
     * 获取适用条件说明
     */
    private String getActionApplicableCondition(DispositionAction action) {
        switch (action) {
            case RELEASE:
                return "合格率 >= 95%";
            case CONDITIONAL_RELEASE:
                return "合格率 85-95%";
            case REWORK:
                return "合格率 70-85%";
            case SCRAP:
                return "缺陷率 >= 50% 或合格率 < 60%";
            case SPECIAL_APPROVAL:
                return "不满足常规规则";
            case HOLD:
                return "需要进一步评估";
            default:
                return "";
        }
    }

    /**
     * 构建下一步操作说明
     */
    private String buildNextSteps(DispositionExecutionResult result) {
        if (result.isApprovalInitiated()) {
            return "处置请求已提交审批，请等待审批结果";
        }

        switch (result.getExecutedAction()) {
            case RELEASE:
                return "批次已放行，可以进入下一工序或出库";
            case CONDITIONAL_RELEASE:
                return "批次已条件放行，请注意使用限制";
            case REWORK:
                return "批次需要返工，请安排返工计划";
            case SCRAP:
                return "批次已标记为报废，请执行报废流程";
            case HOLD:
                return "批次已暂停，请等待进一步指示";
            default:
                return "请查看处置结果并采取相应行动";
        }
    }
}
