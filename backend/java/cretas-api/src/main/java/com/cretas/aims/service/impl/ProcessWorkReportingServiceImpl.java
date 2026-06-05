package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ProcessTaskDTO;
import com.cretas.aims.dto.ProcessWorkReportSubmitRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProcessTask;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProcessTaskStatus;
import com.cretas.aims.entity.enums.ReportMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProcessTaskRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.ProcessWorkReportingService;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProcessWorkReportingServiceImpl implements ProcessWorkReportingService {

    private static final Logger log = LoggerFactory.getLogger(ProcessWorkReportingServiceImpl.class);
    private final ProductionReportRepository reportRepository;
    private final ProcessTaskRepository taskRepository;
    private final WorkProcessRepository workProcessRepository;
    private final ProductTypeRepository productTypeRepository;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    /** Canvas-Thresholds: per-factory production overshoot tolerance (1.10 = 110% cap default). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ThresholdResolverService thresholdResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    private void runConfiguredValidation(String factoryId, String operation, java.util.Map<String, Object> context) {
        if (validationRuleEvaluator == null) return;
        try {
            validationRuleEvaluator.validate(factoryId, "production_report", operation, context);
        } catch (com.cretas.aims.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Canvas validation non-blocking error: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public Map<String, Object> approveReport(String factoryId, Long reportId, Long approvedBy) {
        log.info("Approving report {} for factory {}", reportId, factoryId);
        ProductionReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionReport", "id", reportId.toString()));

        if (!factoryId.equals(report.getFactoryId())) {
            throw new BusinessException(403, "报工记录不属于当前工厂")
                    .withHint("当前报工记录不属于该工厂, 无法操作");
        }

        // Idempotency: only approve if currently PENDING
        if (!"PENDING".equals(report.getApprovalStatus())) {
            // R25 follow-up (reviewer #15 Critical-1, qa-prompt v2.4 Rule 15.b sweep):
            // emit actionHint so FE interceptor differentiates from vanilla optimistic-lock
            // 409 (no actionHint → suppressed). Pre-fix: silent failure on double-approve.
            throw new BusinessException(409, "报工记录已被处理，当前状态: " + report.getApprovalStatus())
                    .withHint("请刷新报工列表查看最新审批状态")
                    .withHintTarget("报工记录");
        }

        report.setApprovalStatus("APPROVED");
        report.setApprovedBy(approvedBy);
        report.setApprovedAt(LocalDateTime.now());
        reportRepository.save(report);

        // Sync quantities to ProcessTask
        if (report.getProcessTaskId() != null) {
            syncQuantitiesToTask(report.getProcessTaskId(), report.getOutputQuantity(), true);
            checkAndRestoreFromSupplementing(report.getProcessTaskId());
        }

        return Map.of("reportId", reportId, "status", "APPROVED");
    }

    @Override
    @Transactional
    public Map<String, Object> rejectReport(String factoryId, Long reportId, String reason, Long rejectedBy) {
        log.info("Rejecting report {} for factory {}", reportId, factoryId);
        ProductionReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionReport", "id", reportId.toString()));

        if (!factoryId.equals(report.getFactoryId())) {
            throw new BusinessException(403, "报工记录不属于当前工厂")
                    .withHint("当前报工记录不属于该工厂, 无法操作");
        }

        if (!"PENDING".equals(report.getApprovalStatus())) {
            // R25 follow-up (reviewer #15 Critical-1)
            throw new BusinessException(409, "报工记录已被处理，当前状态: " + report.getApprovalStatus())
                    .withHint("请刷新报工列表查看最新审批状态")
                    .withHintTarget("报工记录");
        }

        report.setApprovalStatus("REJECTED");
        report.setRejectedReason(reason);
        report.setApprovedBy(rejectedBy);
        report.setApprovedAt(LocalDateTime.now());
        reportRepository.save(report);

        // Decrease pending quantity on task
        if (report.getProcessTaskId() != null) {
            ProcessTask task = taskRepository.findById(report.getProcessTaskId()).orElse(null);
            if (task != null) {
                task.setPendingQuantity(
                        task.getPendingQuantity().subtract(report.getOutputQuantity()).max(BigDecimal.ZERO));
                taskRepository.save(task);
            }
            checkAndRestoreFromSupplementing(report.getProcessTaskId());
        }

        return Map.of("reportId", reportId, "status", "REJECTED");
    }

    @Override
    @Transactional
    public Map<String, Object> batchApprove(String factoryId, List<Long> reportIds, Long approvedBy) {
        log.info("Batch approving {} reports for factory {}", reportIds.size(), factoryId);
        List<Map<String, Object>> results = new ArrayList<>();

        // R69-BUG-1 fix: track skipped IDs WITH reasons so UI can surface them.
        // 之前仅返回 skippedCount, UI 无法告知用户具体哪些 ID 被跳过 + 原因 →
        // 同 R45 BUG-17 anti-pattern (HTTP 200 + 静默跳过). 现在: skippedIds 显式返回 +
        // 全跳过场景升级为 409, 部分跳过保留 200 但带 actionHint.
        List<Map<String, Object>> skippedDetails = new ArrayList<>();
        Set<String> affectedTaskIds = new java.util.HashSet<>();

        for (Long reportId : reportIds) {
            ProductionReport report = reportRepository.findById(reportId).orElse(null);
            if (report == null) {
                skippedDetails.add(Map.of("reportId", reportId, "reason", "NOT_FOUND"));
                continue;
            }

            if (!factoryId.equals(report.getFactoryId())) {
                skippedDetails.add(Map.of("reportId", reportId, "reason", "WRONG_FACTORY"));
                continue;
            }
            if (!"PENDING".equals(report.getApprovalStatus())) {
                skippedDetails.add(Map.of(
                        "reportId", reportId,
                        "reason", "ALREADY_PROCESSED",
                        "currentStatus", report.getApprovalStatus()));
                continue;
            }

            report.setApprovalStatus("APPROVED");
            report.setApprovedBy(approvedBy);
            report.setApprovedAt(LocalDateTime.now());
            reportRepository.save(report);

            if (report.getProcessTaskId() != null) {
                syncQuantitiesToTask(report.getProcessTaskId(), report.getOutputQuantity(), true);
                affectedTaskIds.add(report.getProcessTaskId());
            }

            results.add(Map.of("reportId", reportId, "status", "APPROVED"));
        }

        // Check SUPPLEMENTING state for all affected tasks
        affectedTaskIds.forEach(this::checkAndRestoreFromSupplementing);

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("approved", results.size());
        response.put("skipped", skippedDetails.size());
        response.put("results", results);
        response.put("skippedIds", skippedDetails);

        // R69-BUG-1: if NOTHING got approved (all skipped) → 409 (state-conflict)
        // partial-success (some approved, some skipped) → 200 with response payload;
        // controller layer can read response.skipped > 0 + emit actionHint via FE interceptor.
        if (results.isEmpty() && !skippedDetails.isEmpty()) {
            String reasons = skippedDetails.stream()
                    .map(d -> d.get("reportId") + ":" + d.get("reason"))
                    .collect(Collectors.joining(", "));
            throw new BusinessException(409, "全部 " + skippedDetails.size() + " 条报工记录均无法批量审批 (" + reasons + ")")
                    .withHint("请刷新报工列表, 仅勾选状态为 PENDING 的待审批记录")
                    .withHintTarget("reportIds");
        }

        return response;
    }

    @Override
    @Transactional
    public Map<String, Object> submitNormalReport(String factoryId, Long workerId,
                                                    ProcessWorkReportSubmitRequest request) {
        String processTaskId = request.getProcessTaskId();
        BigDecimal outputQuantity = request.getOutputQuantity();
        String reporterName = request.getReporterName() == null ? "" : request.getReporterName();
        String notes = request.getNotes();
        runConfiguredValidation(factoryId, "CREATE", java.util.Map.of(
            "quantity", outputQuantity != null ? outputQuantity : java.math.BigDecimal.ZERO,
            "processId", processTaskId != null ? processTaskId : "",
            "workerId", workerId != null ? workerId : 0L));
        log.info("Submitting normal report for task {} by worker {}", processTaskId, workerId);

        // #566 T4-B6: SQL-side dedup (was in-memory filter on full task report list).
        // F006 prod 单任务报工累计 ~6700 行 → 3-15s/submit; SQL + 索引 → <5ms.
        LocalDateTime dedup30s = LocalDateTime.now().minusSeconds(30);
        Optional<ProductionReport> duplicate = reportRepository.findRecentDuplicate(
                processTaskId, workerId, outputQuantity, dedup30s);
        if (duplicate.isPresent()) {
            log.warn("Duplicate report detected for task {} worker {} qty {} within 30s", processTaskId, workerId, outputQuantity);
            ProductionReport existing = duplicate.get();
            return Map.of("reportId", existing.getId(), "taskStatus", "IN_PROGRESS",
                    "pendingQuantity", existing.getOutputQuantity(), "duplicate", true);
        }

        ProcessTask task = taskRepository.findByFactoryIdAndId(factoryId, processTaskId)
                .orElseThrow(() -> new ResourceNotFoundException("ProcessTask", "id", processTaskId));

        // Normal report only for IN_PROGRESS or PENDING tasks
        if (task.getStatus() != ProcessTaskStatus.IN_PROGRESS
                && task.getStatus() != ProcessTaskStatus.PENDING) {
            throw new BusinessException(409, "正常报工仅限进行中或待开始的任务，当前状态: " + task.getStatus())
                    .withHint("请刷新任务状态后重试, 或使用补报功能");
        }

        // Auto-transition PENDING → IN_PROGRESS
        if (task.getStatus() == ProcessTaskStatus.PENDING) {
            task.setStatus(ProcessTaskStatus.IN_PROGRESS);
        }
        // Create report — all reports need approval
        ProductionReport report = ProductionReport.builder()
                .factoryId(factoryId)
                .processTaskId(processTaskId)
                .workerId(workerId)
                .reporterName(reporterName)
                .reportType(ProductionReport.ReportType.PROGRESS)
                .reportMode(parseReportMode(request.getReportMode()))
                .reportDate(request.getReportDate() != null ? request.getReportDate() : LocalDate.now())
                .processCategory(resolveProcessName(factoryId, task, request.getProcessCategory()))
                .productName(resolveProductName(factoryId, task))
                .inputQuantity(request.getInputQuantity())
                .sourceWipNo(request.getSourceWipNo())
                .outputQuantity(outputQuantity)
                .totalWorkers(request.getTotalWorkers())
                .totalWorkMinutes(request.getTotalWorkMinutes())
                .productionStartTime(request.getProductionStartTime())
                .productionEndTime(request.getProductionEndTime())
                .customFields(buildCustomFields(request))
                .photos(request.getPhotos())
                .isSupplemental(false)
                .approvalStatus("PENDING")
                .notes(notes)
                .status(ProductionReport.Status.SUBMITTED)
                .build();

        ProductionReport saved = reportRepository.save(report);

        // Add to pendingQuantity (will move to completedQuantity upon approval)
        task.setPendingQuantity(task.getPendingQuantity().add(outputQuantity));
        taskRepository.save(task);

        if (applicationEventPublisher != null) {
            try {
                applicationEventPublisher.publishEvent(new com.cretas.aims.event.WorkReportSubmittedEvent(
                        this, factoryId, processTaskId, String.valueOf(saved.getId()), workerId));
            } catch (Exception e) { log.warn("Publish WorkReportSubmittedEvent failed: {}", e.getMessage()); }
        }

        return Map.of(
                "reportId", saved.getId(),
                "taskStatus", task.getStatus().name(),
                "pendingQuantity", task.getPendingQuantity());
    }

    @Override
    @Transactional
    public Map<String, Object> submitSupplement(String factoryId, Long workerId,
                                                 ProcessWorkReportSubmitRequest request) {
        String processTaskId = request.getProcessTaskId();
        BigDecimal outputQuantity = request.getOutputQuantity();
        String reporterName = request.getReporterName() == null ? "" : request.getReporterName();
        String processCategory = request.getProcessCategory();
        String notes = request.getNotes();
        log.info("Submitting supplement for task {} by worker {}", processTaskId, workerId);

        ProcessTask task = taskRepository.findByFactoryIdAndId(factoryId, processTaskId)
                .orElseThrow(() -> new ResourceNotFoundException("ProcessTask", "id", processTaskId));

        // Must be COMPLETED, CLOSED, or already SUPPLEMENTING
        if (task.getStatus() != ProcessTaskStatus.COMPLETED
                && task.getStatus() != ProcessTaskStatus.CLOSED
                && task.getStatus() != ProcessTaskStatus.SUPPLEMENTING) {
            throw new BusinessException(409, "只有已完成或已关闭的任务可以补报")
                    .withHint("请刷新任务状态, 进行中的任务请使用正常报工");
        }

        // Enter SUPPLEMENTING state if not already
        if (task.getStatus() != ProcessTaskStatus.SUPPLEMENTING) {
            task.setPreviousTerminalStatus(task.getStatus().name());
            task.setStatus(ProcessTaskStatus.SUPPLEMENTING);
            taskRepository.save(task);
        }
        // Create supplemental report
        ProductionReport report = ProductionReport.builder()
                .factoryId(factoryId)
                .processTaskId(processTaskId)
                .workerId(workerId)
                .reporterName(reporterName)
                .reportType(ProductionReport.ReportType.PROGRESS)
                .reportMode(parseReportMode(request.getReportMode()))
                .reportDate(request.getReportDate() != null ? request.getReportDate() : LocalDate.now())
                .outputQuantity(outputQuantity)
                .processCategory(resolveProcessName(factoryId, task, processCategory))
                .productName(resolveProductName(factoryId, task))
                .inputQuantity(request.getInputQuantity())
                .sourceWipNo(request.getSourceWipNo())
                .totalWorkers(request.getTotalWorkers())
                .totalWorkMinutes(request.getTotalWorkMinutes())
                .productionStartTime(request.getProductionStartTime())
                .productionEndTime(request.getProductionEndTime())
                .customFields(buildCustomFields(request))
                .photos(request.getPhotos())
                .isSupplemental(true)
                .approvalStatus("PENDING")
                .notes(notes)
                .status(ProductionReport.Status.SUBMITTED)
                .build();

        ProductionReport saved = reportRepository.save(report);

        // Increase pending quantity
        task.setPendingQuantity(task.getPendingQuantity().add(outputQuantity));
        taskRepository.save(task);

        return Map.of("reportId", saved.getId(), "taskStatus", "SUPPLEMENTING");
    }

    @Override
    @Transactional
    public Map<String, Object> createReversal(String factoryId, Long originalReportId,
                                               Long createdBy, String reason) {
        log.info("Creating reversal for report {} in factory {}", originalReportId, factoryId);

        ProductionReport original = reportRepository.findById(originalReportId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionReport", "id", originalReportId.toString()));

        if (!"APPROVED".equals(original.getApprovalStatus())) {
            throw new BusinessException(409, "只能冲销已审批通过的报工")
                    .withHint("待审批或已驳回的报工无需冲销, 请刷新报工列表查看最新状态");
        }

        if (reportRepository.existsByReversalOfIdAndDeletedAtIsNull(originalReportId)) {
            throw new BusinessException(409, "该报工已被冲销，不可重复操作")
                    .withHint("请刷新报工列表查看最新状态");
        }

        // Create negative reversal record
        ProductionReport reversal = ProductionReport.builder()
                .factoryId(factoryId)
                .processTaskId(original.getProcessTaskId())
                .workerId(original.getWorkerId())
                .reporterName(original.getReporterName())
                .reportType(original.getReportType())
                .reportDate(LocalDate.now())
                .outputQuantity(original.getOutputQuantity().negate())
                .processCategory(original.getProcessCategory())
                .productName(original.getProductName())
                .isSupplemental(false)
                .approvalStatus("APPROVED")
                .approvedBy(createdBy)
                .approvedAt(LocalDateTime.now())
                .reversalOfId(originalReportId)
                .rejectedReason(reason)
                .status(ProductionReport.Status.APPROVED)
                .build();

        ProductionReport saved = reportRepository.save(reversal);

        // Reverse the quantity on the task
        if (original.getProcessTaskId() != null) {
            ProcessTask task = taskRepository.findById(original.getProcessTaskId()).orElse(null);
            if (task != null) {
                task.setCompletedQuantity(
                        task.getCompletedQuantity().subtract(original.getOutputQuantity()).max(BigDecimal.ZERO));
                taskRepository.save(task);
            }
        }

        return Map.of("reversalId", saved.getId(), "originalId", originalReportId);
    }

    @Override
    public PageResponse<Map<String, Object>> getPendingApprovals(String factoryId, Pageable pageable) {
        Page<ProductionReport> page = reportRepository
                .findByFactoryIdAndApprovalStatusAndProcessTaskIdIsNotNullAndDeletedAtIsNull(
                        factoryId, "PENDING", pageable);

        List<Map<String, Object>> content = page.getContent().stream()
                .map(this::reportToMap)
                .collect(Collectors.toList());

        return PageResponse.of(content, page.getNumber() + 1, page.getSize(), page.getTotalElements());
    }

    @Override
    public List<Map<String, Object>> getReportsByTask(String factoryId, String taskId) {
        return reportRepository.findByProcessTaskIdAndDeletedAtIsNull(taskId)
                .stream()
                .map(this::reportToMap)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProcessTaskDTO.WorkerSummary> getWorkerSummaryByTask(String factoryId, String taskId) {
        List<Map<String, Object>> raw = reportRepository.getWorkerSummaryByTaskId(taskId);

        // Get all reports for this task to compute approved vs pending per worker
        List<ProductionReport> allReports = reportRepository.findByProcessTaskIdAndDeletedAtIsNull(taskId);
        Map<Long, BigDecimal> approvedByWorker = new java.util.HashMap<>();
        Map<Long, BigDecimal> pendingByWorker = new java.util.HashMap<>();
        for (ProductionReport r : allReports) {
            if (r.getWorkerId() == null) continue;
            if ("APPROVED".equals(r.getApprovalStatus())) {
                approvedByWorker.merge(r.getWorkerId(), r.getOutputQuantity(), BigDecimal::add);
            } else if ("PENDING".equals(r.getApprovalStatus())) {
                pendingByWorker.merge(r.getWorkerId(), r.getOutputQuantity(), BigDecimal::add);
            }
        }

        return raw.stream()
                .map(row -> {
                    Long workerId = ((Number) row.get("worker_id")).longValue();
                    return ProcessTaskDTO.WorkerSummary.builder()
                        .workerId(workerId)
                        .workerName((String) row.get("worker_name"))
                        .totalQuantity(new BigDecimal(row.get("total_quantity").toString()))
                        .approvedQuantity(approvedByWorker.getOrDefault(workerId, BigDecimal.ZERO))
                        .pendingQuantity(pendingByWorker.getOrDefault(workerId, BigDecimal.ZERO))
                        .reportCount(((Number) row.get("report_count")).intValue())
                        .build();
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void calibrateTaskQuantities(String factoryId) {
        log.info("Calibrating task quantities for factory: {}", factoryId);
        List<ProcessTask> activeTasks = taskRepository.findActiveTasksForCalibration(factoryId);

        for (ProcessTask task : activeTasks) {
            Map<String, Object> approvedSum = reportRepository.sumApprovedQuantityByTaskId(task.getId());
            Map<String, Object> pendingSum = reportRepository.sumPendingQuantityByTaskId(task.getId());

            BigDecimal actualCompleted = new BigDecimal(approvedSum.get("total").toString());
            BigDecimal actualPending = new BigDecimal(pendingSum.get("total").toString());

            if (actualCompleted.compareTo(task.getCompletedQuantity()) != 0
                    || actualPending.compareTo(task.getPendingQuantity()) != 0) {
                log.warn("Calibration drift detected for task {}: completed {}→{}, pending {}→{}",
                        task.getId(),
                        task.getCompletedQuantity(), actualCompleted,
                        task.getPendingQuantity(), actualPending);
                task.setCompletedQuantity(actualCompleted);
                task.setPendingQuantity(actualPending);
                taskRepository.save(task);
            }
        }
    }

    // ==================== Private helpers ====================

    private ReportMode parseReportMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReportMode.MODE_1;
        }
        try {
            return ReportMode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return ReportMode.MODE_1;
        }
    }

    private String resolveProcessName(String factoryId, ProcessTask task, String fallback) {
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        if (task == null || task.getWorkProcessId() == null) {
            return null;
        }
        return workProcessRepository.findByFactoryIdAndId(factoryId, task.getWorkProcessId())
                .map(WorkProcess::getProcessName)
                .orElse(null);
    }

    private String resolveProductName(String factoryId, ProcessTask task) {
        if (task == null || task.getProductTypeId() == null) {
            return null;
        }
        return productTypeRepository.findByIdAndFactoryId(task.getProductTypeId(), factoryId)
                .map(ProductType::getName)
                .orElse(null);
    }

    private Map<String, Object> buildCustomFields(ProcessWorkReportSubmitRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (request.getCustomFields() != null) {
            fields.putAll(request.getCustomFields());
        }
        putIfPresent(fields, "batchNumber", request.getBatchNumber());
        putIfPresent(fields, "reportMode", request.getReportMode());
        putIfPresent(fields, "workerIds", request.getWorkerIds());
        return fields.isEmpty() ? null : fields;
    }

    private void putIfPresent(Map<String, Object> fields, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s && s.isBlank()) {
            return;
        }
        fields.put(key, value);
    }

    /**
     * R70-FIX-D (R69-BUG-2): syncQuantitiesToTask 之前不 cap completedQuantity 也不 guard
     * CLOSED 状态. pt-001 实测 plannedQuantity=100 但 completedQuantity=1178 (10×over-completion).
     * 现在: (1) 不允许同步到 CLOSED 任务 → 409. (2) 超出 plannedQuantity * (1 + OVERSHOOT_PCT)
     * 拒绝同步 → 409 (10% 容忍工业实际超产). approve/batchApprove 调用方 @Transactional 会
     * rollback report 状态保持一致.
     */
    /** Fallback overshoot tolerance when ThresholdResolverService is unavailable (e.g. unit tests). */
    private static final BigDecimal FALLBACK_QUANTITY_OVERSHOOT_TOLERANCE = new BigDecimal("1.10");

    private BigDecimal resolveOvershootTolerance(String factoryId) {
        if (thresholdResolver == null) {
            return FALLBACK_QUANTITY_OVERSHOOT_TOLERANCE;
        }
        return thresholdResolver.getBigDecimal(
                factoryId,
                ThresholdKeys.PRODUCTION_OVERSHOOT_TOLERANCE,
                FALLBACK_QUANTITY_OVERSHOOT_TOLERANCE);
    }

    private void syncQuantitiesToTask(String taskId, BigDecimal quantity, boolean approved) {
        ProcessTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;

        // R70-FIX-D guard 1: CLOSED 状态拒绝同步
        if (task.getStatus() == ProcessTaskStatus.CLOSED) {
            throw new BusinessException(409, "工序任务已关闭, 不可继续报工同步 (taskId=" + taskId + ")")
                    .withHint("请刷新报工列表, 该任务已关闭, 不允许新报工")
                    .withHintTarget("processTaskId");
        }

        if (approved) {
            BigDecimal currentCompleted = task.getCompletedQuantity() != null ? task.getCompletedQuantity() : BigDecimal.ZERO;
            BigDecimal planned = task.getPlannedQuantity();
            BigDecimal newCompleted = currentCompleted.add(quantity);

            // R70-FIX-D guard 2: 超出 plannedQuantity * 110% 拒绝
            if (planned != null && planned.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal tolerance = resolveOvershootTolerance(task.getFactoryId());
                BigDecimal cap = planned.multiply(tolerance);
                if (newCompleted.compareTo(cap) > 0) {
                    throw new BusinessException(409,
                            String.format("本次报工 %s 会让累计完工 %s 超出计划 %s 的 110%% 上限 (cap=%s)",
                                    quantity.toPlainString(), newCompleted.toPlainString(),
                                    planned.toPlainString(), cap.toPlainString()))
                            .withHint("请检查计划数量, 或拆分为多次报工 (每次不超出 cap)")
                            .withHintTarget("outputQuantity");
                }
            }

            task.setCompletedQuantity(newCompleted);
            task.setPendingQuantity(task.getPendingQuantity().subtract(quantity).max(BigDecimal.ZERO));
        }

        // Auto-transition PENDING → IN_PROGRESS on first approved report
        if (task.getStatus() == ProcessTaskStatus.PENDING) {
            task.setStatus(ProcessTaskStatus.IN_PROGRESS);
        }

        taskRepository.save(task);
    }

    private void checkAndRestoreFromSupplementing(String taskId) {
        ProcessTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() != ProcessTaskStatus.SUPPLEMENTING) return;

        // Check if any supplemental reports are still PENDING
        List<ProductionReport> pendingSupplements = reportRepository
                .findByProcessTaskIdAndApprovalStatusAndDeletedAtIsNull(taskId, "PENDING")
                .stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsSupplemental()))
                .collect(Collectors.toList());

        if (pendingSupplements.isEmpty()) {
            // Restore to previous terminal status
            String previousStatus = task.getPreviousTerminalStatus();
            if (previousStatus != null) {
                task.setStatus(ProcessTaskStatus.valueOf(previousStatus));
                task.setPreviousTerminalStatus(null);
                taskRepository.save(task);
                log.info("Task {} restored from SUPPLEMENTING to {}", taskId, previousStatus);
            }
        }
    }

    private Map<String, Object> reportToMap(ProductionReport r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("factoryId", r.getFactoryId());
        map.put("processTaskId", r.getProcessTaskId());
        map.put("workerId", r.getWorkerId());
        map.put("reporterName", r.getReporterName());
        map.put("reportDate", r.getReportDate());
        map.put("inputQuantity", r.getInputQuantity());
        map.put("warehouseOutQuantity", r.getWarehouseOutQuantity());
        map.put("feedInQuantity", r.getFeedInQuantity());
        map.put("carryoverQuantity", r.getCarryoverQuantity());
        map.put("sourceWipNo", r.getSourceWipNo());
        map.put("outputQuantity", r.getOutputQuantity());
        map.put("totalWorkers", r.getTotalWorkers());
        map.put("totalWorkMinutes", r.getTotalWorkMinutes());
        map.put("productionStartTime", r.getProductionStartTime());
        map.put("productionEndTime", r.getProductionEndTime());
        map.put("reportMode", r.getReportMode());
        map.put("processCategory", r.getProcessCategory());
        map.put("productName", r.getProductName());
        map.put("approvalStatus", r.getApprovalStatus());
        map.put("isSupplemental", r.getIsSupplemental());
        map.put("approvedBy", r.getApprovedBy());
        map.put("approvedAt", r.getApprovedAt());
        map.put("rejectedReason", r.getRejectedReason());
        map.put("reversalOfId", r.getReversalOfId());
        map.put("notes", r.getNotes());
        map.put("customFields", r.getCustomFields());
        map.put("photos", r.getPhotos());
        map.put("createdAt", r.getCreatedAt());
        return map;
    }
}
