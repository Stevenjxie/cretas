package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.scheduling.*;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.rules.DroolsRule;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.SchedulingPlanCrudService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 调度计划 CRUD + 产线管理 + Dashboard + 告警管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulingPlanCrudServiceImpl implements SchedulingPlanCrudService {

    private final SchedulingPlanRepository planRepository;
    private final LineScheduleRepository scheduleRepository;
    private final WorkerAssignmentRepository assignmentRepository;
    private final ProductionLineRepository lineRepository;
    private final SchedulingAlertRepository alertRepository;
    private final UserRepository userRepository;
    private final ProductionBatchRepository batchRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final DroolsRuleRepository droolsRuleRepository;
    private final NotificationService notificationService;
    private final PushNotificationService pushNotificationService;
    private final ProductionLineSupervisorRepository supervisorRepository;
    @Lazy @Autowired
    private WorkerAssignmentService workerAssignmentService;
    @Lazy @Autowired
    private SchedulingAIService schedulingAIService;

    // ==================== 调度计划 CRUD ====================

    @Override
    @Transactional
    public SchedulingPlanDTO createPlan(String factoryId, CreateSchedulingPlanRequest request, Long userId) {
        // 检查是否已存在同日计划
        Optional<SchedulingPlan> existing = planRepository.findByFactoryIdAndPlanDateAndDeletedAtIsNull(
            factoryId, request.getPlanDate());
        if (existing.isPresent()) {
            throw new BusinessException(409, "该日期已存在调度计划, 请勿重复创建")
                    .withHint("如需查看请打开该日期已有的调度计划")
                    .withHintTarget(existing.get().getId());
        }

        SchedulingPlan plan = new SchedulingPlan();
        plan.setFactoryId(factoryId);
        plan.setPlanDate(request.getPlanDate());
        plan.setPlanName(request.getPlanName() != null ? request.getPlanName()
            : request.getPlanDate().toString() + " 生产计划");
        plan.setNotes(request.getNotes());
        plan.setCreatedBy(userId);
        plan.setStatus(SchedulingPlan.PlanStatus.draft);

        plan = planRepository.save(plan);

        // 创建排程
        if (request.getSchedules() != null && !request.getSchedules().isEmpty()) {
            List<LineSchedule> schedules = new ArrayList<>();
            int totalWorkers = 0;
            for (CreateSchedulingPlanRequest.ScheduleItem item : request.getSchedules()) {
                LineSchedule schedule = new LineSchedule();
                schedule.setPlan(plan);
                schedule.setProductionLineId(item.getProductionLineId());
                schedule.setBatchId(item.getBatchId());
                schedule.setSequenceOrder(item.getSequenceOrder() != null ? item.getSequenceOrder() : 0);
                schedule.setPlannedStartTime(item.getPlannedStartTime());
                schedule.setPlannedEndTime(item.getPlannedEndTime());
                schedule.setPlannedQuantity(item.getPlannedQuantity());
                schedule.setStatus(LineSchedule.ScheduleStatus.pending);
                schedules.add(schedule);

                if (item.getWorkerIds() != null) {
                    totalWorkers += item.getWorkerIds().size();
                }
            }
            scheduleRepository.saveAll(schedules);
            plan.setTotalBatches(schedules.size());
            plan.setTotalWorkers(totalWorkers);
            planRepository.save(plan);
        }

        // 发送调度计划创建通知
        try {
            Map<String, Object> planDetails = new HashMap<>();
            planDetails.put("planName", plan.getPlanName());
            planDetails.put("planDate", plan.getPlanDate());
            planDetails.put("totalBatches", plan.getTotalBatches());
            planDetails.put("totalWorkers", plan.getTotalWorkers());
            notificationService.notifyScheduleCreated(factoryId, plan.getId(), planDetails);
            log.info("调度计划创建通知已发送: planId={}", plan.getId());
        } catch (Exception e) {
            log.error("发送调度计划创建通知失败: planId={}", plan.getId(), e);
        }

        // 发送推送通知到工厂所有设备
        try {
            Map<String, Object> pushData = new HashMap<>();
            pushData.put("type", "plan_created");
            pushData.put("planId", plan.getId());
            pushData.put("planDate", plan.getPlanDate().toString());
            pushData.put("screen", "SchedulingPlanDetail");
            pushNotificationService.sendToFactory(
                factoryId,
                "生产计划已创建",
                String.format("计划 %s 已创建，包含 %d 个批次", plan.getPlanName(), plan.getTotalBatches()),
                pushData
            );
            log.info("调度计划创建推送通知已发送: planId={}", plan.getId());
        } catch (Exception e) {
            log.error("发送调度计划创建推送通知失败: planId={}", plan.getId(), e);
        }

        return enrichPlanDTO(SchedulingPlanDTO.fromEntity(plan));
    }

    @Override
    public SchedulingPlanDTO getPlan(String factoryId, String planId) {
        SchedulingPlan plan = planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(planId, factoryId)
            .orElseThrow(() -> new EntityNotFoundException("SchedulePlan", planId));
        SchedulingPlanDTO dto = SchedulingPlanDTO.fromEntity(plan);

        // 获取排程列表（使用批量版本解决 N+1 问题）
        List<LineSchedule> schedules = scheduleRepository.findByPlanIdOrderBySequenceOrder(planId);
        dto.setLineSchedules(enrichScheduleDTOs(schedules));

        return enrichPlanDTO(dto);
    }

    @Override
    public Page<SchedulingPlanDTO> getPlans(String factoryId, LocalDate startDate, LocalDate endDate,
                                             String status, Pageable pageable) {
        Page<SchedulingPlan> plans;

        if (startDate != null && endDate != null && status != null && !status.isEmpty()) {
            List<SchedulingPlan.PlanStatus> statuses = Arrays.stream(status.split(","))
                .map(String::trim)
                .map(s -> {
                    try {
                        return SchedulingPlan.PlanStatus.valueOf(s.toLowerCase());
                    } catch (IllegalArgumentException e) {
                        log.warn("无效的计划状态: {}", s);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            if (statuses.size() == 1) {
                plans = planRepository.findByFactoryIdAndDateRangeAndStatusPaged(
                    factoryId, startDate, endDate, statuses.get(0), pageable);
            } else if (!statuses.isEmpty()) {
                plans = planRepository.findByFactoryIdAndDateRangeAndStatusesPaged(
                    factoryId, startDate, endDate, statuses, pageable);
            } else {
                plans = planRepository.findByFactoryIdAndDateRangePaged(factoryId, startDate, endDate, pageable);
            }
        } else if (startDate != null && endDate != null) {
            plans = planRepository.findByFactoryIdAndDateRangePaged(factoryId, startDate, endDate, pageable);
        } else {
            plans = planRepository.findByFactoryIdAndDeletedAtIsNullOrderByPlanDateDesc(factoryId, pageable);
        }

        List<SchedulingPlanDTO> dtos = plans.getContent().stream()
            .map(SchedulingPlanDTO::fromEntity)
            .map(this::enrichPlanDTO)
            .collect(Collectors.toList());

        return new PageImpl<>(dtos, pageable, plans.getTotalElements());
    }

    @Override
    @Transactional
    public SchedulingPlanDTO updatePlan(String factoryId, String planId, CreateSchedulingPlanRequest request) {
        SchedulingPlan plan = planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(planId, factoryId)
            .orElseThrow(() -> new EntityNotFoundException("SchedulePlan", planId));

        if (plan.getStatus() != SchedulingPlan.PlanStatus.draft) {
            throw new BusinessException(409, "只能修改草稿状态的计划, 当前状态: " + plan.getStatus());
        }

        plan.setPlanName(request.getPlanName());
        plan.setNotes(request.getNotes());
        planRepository.save(plan);

        return enrichPlanDTO(SchedulingPlanDTO.fromEntity(plan));
    }

    @Override
    @Transactional
    public SchedulingPlanDTO confirmPlan(String factoryId, String planId, Long userId) {
        SchedulingPlan plan = planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(planId, factoryId)
            .orElseThrow(() -> new EntityNotFoundException("SchedulePlan", planId));

        if (plan.getStatus() != SchedulingPlan.PlanStatus.draft) {
            throw new BusinessException(409, "只能确认草稿状态的计划, 当前状态: " + plan.getStatus());
        }

        plan.setStatus(SchedulingPlan.PlanStatus.confirmed);
        plan.setConfirmedBy(userId);
        plan.setConfirmedAt(LocalDateTime.now());
        planRepository.save(plan);

        // 自动分配车间主任
        assignSupervisorsToSchedules(plan);

        // 发送调度计划确认通知
        try {
            String confirmerName = userRepository.findById(userId)
                .map(User::getFullName)
                .orElse("未知用户");
            notificationService.notifyScheduleConfirmed(factoryId, planId, confirmerName);
            log.info("调度计划确认通知已发送: planId={}, confirmedBy={}", planId, confirmerName);
        } catch (Exception e) {
            log.error("发送调度计划确认通知失败: planId={}", planId, e);
        }

        // 发送推送通知
        try {
            Map<String, Object> pushData = new HashMap<>();
            pushData.put("type", "plan_confirmed");
            pushData.put("planId", planId);
            pushData.put("planDate", plan.getPlanDate().toString());
            pushData.put("screen", "SchedulingPlanDetail");
            pushNotificationService.sendToFactory(
                factoryId,
                "生产计划已确认",
                String.format("计划 %s 已确认，请相关人员做好准备", plan.getPlanName()),
                pushData
            );
            log.info("调度计划确认推送通知已发送: planId={}", planId);
        } catch (Exception e) {
            log.error("发送调度计划确认推送通知失败: planId={}", planId, e);
        }

        return enrichPlanDTO(SchedulingPlanDTO.fromEntity(plan));
    }

    /**
     * 自动为排程分配车间主任
     */
    private void assignSupervisorsToSchedules(SchedulingPlan plan) {
        if (plan.getLineSchedules() == null || plan.getLineSchedules().isEmpty()) {
            return;
        }

        for (LineSchedule schedule : plan.getLineSchedules()) {
            supervisorRepository.findByProductionLineIdAndIsPrimaryTrue(schedule.getProductionLineId())
                .ifPresent(supervisor -> {
                    schedule.setSupervisorId(supervisor.getSupervisorUserId());
                    scheduleRepository.save(schedule);
                    log.info("排程 {} 已分配给车间主任 {}", schedule.getId(), supervisor.getSupervisorUserId());
                });
        }
    }

    @Override
    @Transactional
    public void cancelPlan(String factoryId, String planId, String reason) {
        SchedulingPlan plan = planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(planId, factoryId)
            .orElseThrow(() -> new EntityNotFoundException("SchedulePlan", planId));

        plan.setStatus(SchedulingPlan.PlanStatus.cancelled);
        plan.setNotes(plan.getNotes() != null ? plan.getNotes() + "\n取消原因: " + reason : "取消原因: " + reason);
        planRepository.save(plan);
    }

    // ==================== 产线管理 ====================

    @Override
    public List<ProductionLineDTO> getProductionLines(String factoryId, String status) {
        List<ProductionLine> lines;
        if (status != null) {
            ProductionLine.LineStatus lineStatus = ProductionLine.LineStatus.valueOf(status);
            lines = lineRepository.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, lineStatus);
        } else {
            lines = lineRepository.findByFactoryIdAndDeletedAtIsNull(factoryId);
        }
        return lines.stream()
            .map(ProductionLineDTO::fromEntity)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ProductionLineDTO createProductionLine(String factoryId, ProductionLineDTO request) {
        ProductionLine line = new ProductionLine();
        line.setFactoryId(factoryId);
        line.setName(request.getName());
        line.setLineCode(request.getLineCode());
        line.setLineType(request.getLineType());
        line.setDepartmentId(request.getDepartmentId());
        line.setMinWorkers(request.getMinWorkers() != null ? request.getMinWorkers() : 1);
        line.setMaxWorkers(request.getMaxWorkers() != null ? request.getMaxWorkers() : 10);
        line.setRequiredSkillLevel(request.getRequiredSkillLevel() != null ? request.getRequiredSkillLevel() : 1);
        line.setHourlyCapacity(request.getHourlyCapacity());
        line.setEquipmentIds(request.getEquipmentIds());
        line.setStatus(ProductionLine.LineStatus.active);

        line = lineRepository.save(line);
        return ProductionLineDTO.fromEntity(line);
    }

    @Override
    @Transactional
    public ProductionLineDTO updateProductionLine(String factoryId, String lineId, ProductionLineDTO request) {
        ProductionLine line = lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull(lineId, factoryId)
            .orElseThrow(() -> new EntityNotFoundException("ProductionLine", lineId));

        if (request.getName() != null) line.setName(request.getName());
        if (request.getLineCode() != null) line.setLineCode(request.getLineCode());
        if (request.getLineType() != null) line.setLineType(request.getLineType());
        if (request.getDepartmentId() != null) line.setDepartmentId(request.getDepartmentId());
        if (request.getMinWorkers() != null) line.setMinWorkers(request.getMinWorkers());
        if (request.getMaxWorkers() != null) line.setMaxWorkers(request.getMaxWorkers());
        if (request.getRequiredSkillLevel() != null) line.setRequiredSkillLevel(request.getRequiredSkillLevel());
        if (request.getHourlyCapacity() != null) line.setHourlyCapacity(request.getHourlyCapacity());
        if (request.getEquipmentIds() != null) line.setEquipmentIds(request.getEquipmentIds());

        line = lineRepository.save(line);
        return ProductionLineDTO.fromEntity(line);
    }

    @Override
    @Transactional
    public ProductionLineDTO updateProductionLineStatus(String factoryId, String lineId, String status) {
        ProductionLine line = lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull(lineId, factoryId)
            .orElseThrow(() -> new EntityNotFoundException("ProductionLine", lineId));

        line.setStatus(ProductionLine.LineStatus.valueOf(status));
        line = lineRepository.save(line);
        return ProductionLineDTO.fromEntity(line);
    }

    // ==================== Dashboard ====================

    @Override
    public SchedulingDashboardDTO getDashboard(String factoryId, LocalDate date) {
        log.debug("getDashboard: factoryId={}, date={}", factoryId, date);
        return SchedulingDashboardDTO.builder()
            .date(date).totalPlans(0).confirmedPlans(0).inProgressPlans(0).completedPlans(0)
            .totalSchedules(0).pendingSchedules(0).inProgressSchedules(0).completedSchedules(0)
            .delayedSchedules(0).totalAlerts(0).criticalAlerts(0).build();
    }

    @Override
    public SchedulingDashboardDTO getRealtimeMonitor(String factoryId, String planId) {
        log.debug("getRealtimeMonitor: factoryId={}, planId={}", factoryId, planId);
        SchedulingPlan plan = planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(planId, factoryId)
            .orElseThrow(() -> new EntityNotFoundException("ProductionPlan", planId));

        SchedulingDashboardDTO dashboard = getDashboard(factoryId, plan.getPlanDate());

        // 添加实时排程详情（使用批量版本解决 N+1 问题）
        List<LineSchedule> schedules = scheduleRepository.findByPlanIdOrderBySequenceOrder(planId);
        dashboard.setCurrentSchedules(enrichScheduleDTOs(schedules));

        return dashboard;
    }

    // ==================== 告警管理 ====================

    @Override
    public List<SchedulingAlertDTO> getUnresolvedAlerts(String factoryId) {
        return alertRepository.findByFactoryIdAndIsResolvedFalseOrderByCreatedAtDesc(factoryId)
            .stream().map(this::toAlertDTO).toList();
    }

    @Override
    public Page<SchedulingAlertDTO> getAlerts(String factoryId, String severity, String alertType, Pageable pageable) {
        return alertRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable).map(this::toAlertDTO);
    }

    @Override
    @Transactional
    public SchedulingAlertDTO acknowledgeAlert(String factoryId, String alertId, Long userId) {
        SchedulingAlert alert = alertRepository.findByIdAndFactoryId(alertId, factoryId)
            .orElseThrow(() -> new IllegalArgumentException("告警不存在"));
        alert.setAcknowledgedBy(userId);
        alert.setAcknowledgedAt(LocalDateTime.now());
        return toAlertDTO(alertRepository.save(alert));
    }

    @Override
    @Transactional
    public SchedulingAlertDTO resolveAlert(String factoryId, String alertId, Long userId, String resolutionNotes) {
        SchedulingAlert alert = alertRepository.findByIdAndFactoryId(alertId, factoryId)
            .orElseThrow(() -> new IllegalArgumentException("告警不存在"));
        alert.setIsResolved(true);
        alert.setResolvedBy(userId);
        alert.setResolvedAt(LocalDateTime.now());
        alert.setResolutionNotes(resolutionNotes);
        return toAlertDTO(alertRepository.save(alert));
    }

    private SchedulingAlertDTO toAlertDTO(SchedulingAlert alert) {
        return SchedulingAlertDTO.builder()
            .id(alert.getId())
            .factoryId(alert.getFactoryId())
            .scheduleId(alert.getScheduleId())
            .planId(alert.getPlanId())
            .alertType(alert.getAlertType() != null ? alert.getAlertType().name() : null)
            .severity(alert.getSeverity() != null ? alert.getSeverity().name() : null)
            .message(alert.getMessage())
            .suggestedAction(alert.getSuggestedAction())
            .isResolved(alert.getIsResolved())
            .resolvedAt(alert.getResolvedAt())
            .resolvedBy(alert.getResolvedBy())
            .resolutionNotes(alert.getResolutionNotes())
            .acknowledgedAt(alert.getAcknowledgedAt())
            .acknowledgedBy(alert.getAcknowledgedBy())
            .createdAt(alert.getCreatedAt())
            .build();
    }

    // ==================== 待排产批次与阈值 ====================

    @Override
    public List<ProductionPlanDTO> getPendingBatches(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<ProductionPlan> plans = productionPlanRepository
            .findByFactoryIdAndStatusAndExpectedCompletionDateBetween(
                factoryId,
                ProductionPlanStatus.PENDING,
                startDate != null ? startDate : LocalDate.now(),
                endDate != null ? endDate : LocalDate.now().plusMonths(1)
            );

        double threshold = getUrgentThreshold(factoryId);

        List<ProductionPlanDTO> dtos = new ArrayList<>();
        for (ProductionPlan plan : plans) {
            if (plan.isProbabilityStale()) {
                schedulingAIService.calculatePlanProbability(plan);
            }
            ProductionPlanDTO dto = enrichProductionPlanDTO(plan, threshold);
            dtos.add(dto);
        }

        dtos.sort((a, b) -> {
            if (a.getIsUrgent() != b.getIsUrgent()) {
                return a.getIsUrgent() ? -1 : 1;
            }
            BigDecimal crA = a.getCrValue() != null ? a.getCrValue() : BigDecimal.valueOf(999);
            BigDecimal crB = b.getCrValue() != null ? b.getCrValue() : BigDecimal.valueOf(999);
            return crA.compareTo(crB);
        });

        log.info("工厂 {} 查询到 {} 个待排产批次，其中紧急: {}",
                 factoryId, dtos.size(),
                 dtos.stream().filter(ProductionPlanDTO::getIsUrgent).count());

        return dtos;
    }

    private ProductionPlanDTO enrichProductionPlanDTO(ProductionPlan plan, double threshold) {
        ProductionPlanDTO dto = new ProductionPlanDTO();
        dto.setId(plan.getId());
        dto.setPlanNumber(plan.getPlanNumber());
        dto.setProductTypeId(plan.getProductTypeId());
        dto.setPlannedQuantity(plan.getPlannedQuantity());
        dto.setExpectedCompletionDate(plan.getExpectedCompletionDate());
        dto.setStatus(plan.getStatus());
        dto.setPriority(plan.getPriority());
        dto.setSourceType(plan.getSourceType());
        dto.setCustomerOrderNumber(plan.getCustomerOrderNumber());
        dto.setSourceCustomerName(plan.getSourceCustomerName());
        dto.setCrValue(plan.getCrValue());
        dto.setAiConfidence(plan.getAiConfidence());
        dto.setForecastReason(plan.getForecastReason());
        dto.setIsMixedBatch(plan.getIsMixedBatch());
        dto.setAllocatedQuantity(plan.getAllocatedQuantity());
        dto.setIsFullyMatched(plan.getIsFullyMatched());
        dto.setMatchingProgress(plan.getMatchingProgress());
        dto.setCurrentProbability(plan.getCurrentProbability());
        dto.setProbabilityUpdatedAt(plan.getProbabilityUpdatedAt());
        dto.setIsUrgent(plan.isUrgent(threshold));
        dto.setCreatedAt(plan.getCreatedAt());
        dto.setUpdatedAt(plan.getUpdatedAt());
        if (plan.getProductType() != null) {
            dto.setProductTypeName(plan.getProductType().getName());
        }
        return dto;
    }

    @Override
    public double getUrgentThreshold(String factoryId) {
        Optional<DroolsRule> factoryRule = droolsRuleRepository
            .findByFactoryIdAndRuleGroupAndRuleName(factoryId, "scheduling", "urgent_threshold");

        if (factoryRule.isPresent() && factoryRule.get().getEnabled()) {
            try {
                return Double.parseDouble(factoryRule.get().getRuleContent());
            } catch (NumberFormatException e) {
                log.warn("工厂 {} 的紧急阈值配置格式错误: {}", factoryId,
                         factoryRule.get().getRuleContent(), e);
            }
        }

        Optional<DroolsRule> systemRule = droolsRuleRepository
            .findByFactoryIdAndRuleGroupAndRuleName("SYSTEM", "scheduling", "urgent_threshold");

        if (systemRule.isPresent() && systemRule.get().getEnabled()) {
            try {
                return Double.parseDouble(systemRule.get().getRuleContent());
            } catch (NumberFormatException e) {
                log.warn("系统级紧急阈值配置格式错误: {}", systemRule.get().getRuleContent(), e);
            }
        }

        return 0.6;
    }

    @Override
    @Transactional
    public void updateUrgentThreshold(String factoryId, Double threshold, Long userId) {
        Optional<DroolsRule> existingRule = droolsRuleRepository
            .findByFactoryIdAndRuleGroupAndRuleName(factoryId, "scheduling", "urgent_threshold");

        DroolsRule rule;
        if (existingRule.isPresent()) {
            rule = existingRule.get();
            rule.setRuleContent(String.valueOf(threshold));
            rule.setVersion(rule.getVersion() + 1);
            rule.setUpdatedBy(userId);
            rule.setUpdatedAt(LocalDateTime.now());
        } else {
            rule = new DroolsRule();
            rule.setId(UUID.randomUUID().toString());
            rule.setFactoryId(factoryId);
            rule.setRuleGroup("scheduling");
            rule.setRuleName("urgent_threshold");
            rule.setRuleDescription("生产计划紧急阈值：完成概率低于此值时标记为紧急");
            rule.setRuleContent(String.valueOf(threshold));
            rule.setEnabled(true);
            rule.setPriority(100);
            rule.setVersion(1);
            rule.setCreatedBy(userId);
            rule.setCreatedAt(LocalDateTime.now());
            rule.setUpdatedAt(LocalDateTime.now());
        }

        droolsRuleRepository.save(rule);
        log.info("更新紧急阈值配置成功: factoryId={}, threshold={}, userId={}", factoryId, threshold, userId);
    }

    // ==================== DTO 丰富化 ====================

    @Override
    public SchedulingPlanDTO enrichPlanDTO(SchedulingPlanDTO dto) {
        if (dto.getCreatedBy() != null) {
            userRepository.findById(dto.getCreatedBy()).ifPresent(user ->
                dto.setCreatedByName(user.getFullName()));
        }
        if (dto.getConfirmedBy() != null) {
            userRepository.findById(dto.getConfirmedBy()).ifPresent(user ->
                dto.setConfirmedByName(user.getFullName()));
        }
        if (dto.getId() != null) {
            dto.setPendingSchedules((int) scheduleRepository.countByPlanIdAndStatus(
                dto.getId(), LineSchedule.ScheduleStatus.pending));
            dto.setInProgressSchedules((int) scheduleRepository.countByPlanIdAndStatus(
                dto.getId(), LineSchedule.ScheduleStatus.in_progress));
            dto.setCompletedSchedules((int) scheduleRepository.countByPlanIdAndStatus(
                dto.getId(), LineSchedule.ScheduleStatus.completed));
        }
        return dto;
    }

    @Override
    public List<LineScheduleDTO> enrichScheduleDTOs(List<LineSchedule> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> productionLineIds = schedules.stream()
                .map(LineSchedule::getProductionLineId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> batchIds = schedules.stream()
                .map(LineSchedule::getBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> scheduleIds = schedules.stream()
                .map(LineSchedule::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, ProductionLine> lineMap = productionLineIds.isEmpty() ? Collections.emptyMap() :
                lineRepository.findAllById(productionLineIds).stream()
                        .collect(Collectors.toMap(ProductionLine::getId, Function.identity()));

        Map<Long, ProductionBatch> batchMap = batchIds.isEmpty() ? Collections.emptyMap() :
                batchRepository.findAllById(batchIds).stream()
                        .collect(Collectors.toMap(ProductionBatch::getId, Function.identity()));

        List<WorkerAssignment> allAssignments = scheduleIds.isEmpty() ? Collections.emptyList() :
                assignmentRepository.findByScheduleIdIn(scheduleIds);

        Map<String, List<WorkerAssignment>> assignmentsBySchedule = allAssignments.stream()
                .collect(Collectors.groupingBy(a -> a.getSchedule().getId()));

        Set<Long> userIds = allAssignments.stream()
                .map(WorkerAssignment::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap() :
                userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        return schedules.stream().map(entity -> {
            LineScheduleDTO dto = LineScheduleDTO.fromEntity(entity);

            ProductionLine line = lineMap.get(entity.getProductionLineId());
            if (line != null) {
                dto.setProductionLineName(line.getName());
            }

            if (entity.getBatchId() != null) {
                ProductionBatch batch = batchMap.get(entity.getBatchId());
                if (batch != null) {
                    dto.setBatchNumber(batch.getBatchNumber());
                }
            }

            List<WorkerAssignment> assignments = assignmentsBySchedule.getOrDefault(entity.getId(), Collections.emptyList());
            dto.setWorkerAssignments(assignments.stream().map(assignment -> {
                WorkerAssignmentDTO assignmentDTO = WorkerAssignmentDTO.fromEntity(assignment);

                User user = userMap.get(assignment.getUserId());
                if (user != null) {
                    assignmentDTO.setUserName(user.getFullName());
                    assignmentDTO.setUserPhone(user.getPhone());
                }

                assignmentDTO.setScheduledStartTime(entity.getPlannedStartTime());
                assignmentDTO.setScheduledEndTime(entity.getPlannedEndTime());
                assignmentDTO.setProductionLineName(dto.getProductionLineName());
                assignmentDTO.setBatchNumber(dto.getBatchNumber());

                return assignmentDTO;
            }).collect(Collectors.toList()));

            return dto;
        }).collect(Collectors.toList());
    }
}
