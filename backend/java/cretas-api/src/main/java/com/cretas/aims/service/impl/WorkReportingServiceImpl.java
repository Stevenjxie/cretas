package com.cretas.aims.service.impl;

import com.cretas.aims.dto.WorkReportSubmitRequest;
import com.cretas.aims.dto.WorkReportResponse;
import com.cretas.aims.entity.BatchWorkSession;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.enums.ReportMode;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.event.BatchCompletedEvent;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkReportingServiceImpl {

    private final ProductionReportRepository reportRepository;
    private final BatchWorkSessionRepository batchWorkSessionRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 提交报工
     */
    @Transactional
    public WorkReportResponse submitReport(String factoryId, Long workerId, WorkReportSubmitRequest request) {
        log.info("提交报工: factoryId={}, workerId={}, type={}", factoryId, workerId, request.getReportType());

        // P0-15: 报工模式差异化校验
        ReportMode mode = request.getReportMode() != null ? request.getReportMode() : ReportMode.MODE_1;
        if (request.getOutputQuantity() == null) {
            throw new BusinessException(400, "产量(outputQuantity)必填")
                    .withHint("请输入本次报工产量").withHintTarget("outputQuantity");
        }
        if (request.getTotalWorkMinutes() == null || request.getTotalWorkMinutes() <= 0) {
            throw new BusinessException(400, "耗时(totalWorkMinutes)必填且>0")
                    .withHint("请输入大于 0 的本次报工耗时(分钟)").withHintTarget("totalWorkMinutes");
        }
        if (mode == ReportMode.MODE_2 && request.getBatchId() == null) {
            throw new BusinessException(400, "MODE_2 按批次报工必须关联生产批次(batchId)")
                    .withHint("请选择关联的生产批次").withHintTarget("batchId");
        }
        if (mode == ReportMode.MODE_3 && (request.getWorkerIds() == null || request.getWorkerIds().isEmpty())) {
            throw new BusinessException(400, "MODE_3 按人头报工必须提供 workerIds 列表")
                    .withHint("请选择参与报工的工人").withHintTarget("workerIds");
        }

        // 跨租户校验 (edge-case 审计 2026-06-24 抓): batchId 须属于当前工厂, 否则别厂 batchId 会被
        // 接受并持久化一条跨租户关联的报工行 + 返 200 (虽 updateBatchActualQuantity 有 findByIdAndFactoryId
        // 防住别厂批次产量被改, 但仍产生垃圾行+误导成功)。提前校验 → 404, 与项目跨租户红线一致。
        if (request.getBatchId() != null) {
            com.cretas.aims.entity.ProductionBatch reportBatch = productionBatchRepository
                    .findByIdAndFactoryId(request.getBatchId(), factoryId)
                    .orElseThrow(() -> new com.cretas.aims.exception.EntityNotFoundException(
                            "生产批次不存在或不属于当前工厂: " + request.getBatchId()));
            // 开工守卫 (edge-case 审计 2026-06-24, Steve 批): legacy 路径漏批次开工守卫
            // (新 YieldReport/ProcessWorkReporting 路径已有)。未开工/终态批次不可报工。
            ProductionBatchStatus st = reportBatch.getStatus();
            if (st == ProductionBatchStatus.PLANNED || st == ProductionBatchStatus.PLANNING) {
                throw new BusinessException(409, "批次尚未开始生产, 不可报工")
                        .withHint("请先点击『开始生产』后再报工").withHintTarget("batchId");
            }
            if (st == ProductionBatchStatus.COMPLETED || st == ProductionBatchStatus.CANCELLED) {
                throw new BusinessException(409, "批次已" + st.getDescription() + ", 不可报工")
                        .withHint("已完工/已取消的批次不能再报工");
            }
        }

        // 单元B (F006 REQ-17): 防重提交改为 5 分钟窗口 — 只拦 double-click, 不再封锁同日累加。
        // 客户张权要"累加式分时段报工"(每小时报一次后台累加), 旧的全天去重把累加封死。
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        boolean duplicate;
        if (request.getBatchId() != null) {
            duplicate = reportRepository.existsByFactoryIdAndWorkerIdAndBatchIdAndReportTypeAndCreatedAtAfterAndDeletedAtIsNull(
                    factoryId, workerId, request.getBatchId(), request.getReportType(), cutoff);
        } else {
            duplicate = reportRepository.existsByFactoryIdAndWorkerIdAndReportTypeAndCreatedAtAfterAndBatchIdIsNullAndDeletedAtIsNull(
                    factoryId, workerId, request.getReportType(), cutoff);
        }
        if (duplicate) {
            throw new BusinessException(409, "5分钟内已对该批次提交过报工, 请勿重复点击 (如需累加报工请稍后再报)")
                    .withHint("同一批次同一工序可多次报工累加, 仅 5 分钟内的重复提交被拦截");
        }

        // 自动填充报告人姓名
        String reporterName = request.getReporterName();
        if (reporterName == null || reporterName.isBlank()) {
            reporterName = userRepository.findById(workerId)
                    .map(u -> u.getFullName() != null ? u.getFullName() : u.getUsername())
                    .orElse(null);
        }

        ProductionReport report = ProductionReport.builder()
                .factoryId(factoryId)
                .workerId(workerId)
                .batchId(request.getBatchId())
                .reportType(request.getReportType())
                .reportMode(mode)
                .schemaId(request.getSchemaId())
                .reportDate(request.getReportDate())
                .reporterName(reporterName)
                .processCategory(request.getProcessCategory())
                .productName(request.getProductName())
                .outputQuantity(request.getOutputQuantity())
                .goodQuantity(request.getGoodQuantity())
                .defectQuantity(request.getDefectQuantity())
                .totalWorkMinutes(request.getTotalWorkMinutes())
                .totalWorkers(request.getTotalWorkers())
                .operationVolume(request.getOperationVolume())
                .hourEntries(request.getHourEntries())
                .nonProductionEntries(request.getNonProductionEntries())
                .productionStartTime(request.getProductionStartTime())
                .productionEndTime(request.getProductionEndTime())
                .customFields(request.getCustomFields())
                .photos(request.getPhotos())
                .build();

        report = reportRepository.save(report);

        // 如果关联批次且有产量，更新批次实际产量
        if (request.getBatchId() != null && request.getOutputQuantity() != null) {
            updateBatchActualQuantity(factoryId, request.getBatchId(), request.getOutputQuantity());
        }

        return toResponse(report);
    }

    /**
     * 查询报工列表
     */
    public Page<WorkReportResponse> getReports(String factoryId, String reportType,
                                                 LocalDate startDate, LocalDate endDate,
                                                 int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "reportDate"));

        Page<ProductionReport> reports;
        if (reportType != null && startDate != null && endDate != null) {
            reports = reportRepository.findByFactoryIdAndReportTypeAndReportDateBetweenAndDeletedAtIsNull(
                    factoryId, reportType, startDate, endDate, pageRequest);
        } else if (reportType != null) {
            reports = reportRepository.findByFactoryIdAndReportTypeAndDeletedAtIsNull(
                    factoryId, reportType, pageRequest);
        } else if (startDate != null && endDate != null) {
            reports = reportRepository.findByFactoryIdAndReportDateBetweenAndDeletedAtIsNull(
                    factoryId, startDate, endDate, pageRequest);
        } else {
            reports = reportRepository.findByFactoryIdAndDeletedAtIsNull(factoryId, pageRequest);
        }

        return reports.map(this::toResponse);
    }

    /**
     * 获取报工详情
     */
    public WorkReportResponse getReport(String factoryId, Long reportId) {
        ProductionReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new RuntimeException("报工记录不存在: " + reportId));
        if (!report.getFactoryId().equals(factoryId)) {
            throw new RuntimeException("无权访问此报工记录");
        }
        return toResponse(report);
    }

    /**
     * 修改草稿 (P0-3: APPROVED/REJECTED不可修改)
     */
    @Transactional
    public WorkReportResponse updateReport(String factoryId, Long reportId, WorkReportSubmitRequest request) {
        ProductionReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new RuntimeException("报工记录不存在: " + reportId));
        if (!report.getFactoryId().equals(factoryId)) {
            throw new RuntimeException("无权修改此报工记录");
        }
        if (ProductionReport.Status.APPROVED.equals(report.getStatus())
                || ProductionReport.Status.REJECTED.equals(report.getStatus())) {
            throw new RuntimeException("已审批的报工不可修改，当前状态: " + report.getStatus());
        }

        if (request.getReportDate() != null) report.setReportDate(request.getReportDate());
        if (request.getProcessCategory() != null) report.setProcessCategory(request.getProcessCategory());
        if (request.getProductName() != null) report.setProductName(request.getProductName());
        if (request.getOutputQuantity() != null) report.setOutputQuantity(request.getOutputQuantity());
        if (request.getGoodQuantity() != null) report.setGoodQuantity(request.getGoodQuantity());
        if (request.getDefectQuantity() != null) report.setDefectQuantity(request.getDefectQuantity());
        if (request.getTotalWorkMinutes() != null) report.setTotalWorkMinutes(request.getTotalWorkMinutes());
        if (request.getTotalWorkers() != null) report.setTotalWorkers(request.getTotalWorkers());
        if (request.getOperationVolume() != null) report.setOperationVolume(request.getOperationVolume());
        if (request.getHourEntries() != null) report.setHourEntries(request.getHourEntries());
        if (request.getProductionStartTime() != null) report.setProductionStartTime(request.getProductionStartTime());
        if (request.getProductionEndTime() != null) report.setProductionEndTime(request.getProductionEndTime());
        if (request.getCustomFields() != null) report.setCustomFields(request.getCustomFields());
        if (request.getPhotos() != null) report.setPhotos(request.getPhotos());

        report = reportRepository.save(report);
        return toResponse(report);
    }

    /**
     * 审批报工 (P0-3: 仅SUBMITTED状态可审批)
     */
    @Transactional
    public WorkReportResponse approveReport(String factoryId, Long reportId, boolean approved, String rejectionReason) {
        ProductionReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new RuntimeException("报工记录不存在: " + reportId));
        if (!report.getFactoryId().equals(factoryId)) {
            throw new RuntimeException("无权审批此报工记录");
        }
        if (!ProductionReport.Status.SUBMITTED.equals(report.getStatus())) {
            throw new RuntimeException("仅SUBMITTED状态的报工可以审批，当前状态: " + report.getStatus());
        }

        report.setStatus(approved ? ProductionReport.Status.APPROVED : ProductionReport.Status.REJECTED);
        if (!approved && rejectionReason != null) {
            report.setRejectionReason(rejectionReason);
        }
        report = reportRepository.save(report);
        return toResponse(report);
    }

    /**
     * 签到
     */
    @Transactional
    public BatchWorkSession checkin(String factoryId, Long batchId, Long employeeId,
                                     String checkinMethod, Long assignedBy) {
        log.info("签到: factoryId={}, batchId={}, employeeId={}, method={}",
                factoryId, batchId, employeeId, checkinMethod);

        // 检查是否已签到
        Optional<BatchWorkSession> existing = batchWorkSessionRepository
                .findByBatchIdAndEmployeeId(batchId, employeeId);
        if (existing.isPresent() && BatchWorkSession.Status.WORKING.equals(existing.get().getStatus())) {
            throw new RuntimeException("该员工已签到此批次");
        }

        BatchWorkSession session = new BatchWorkSession();
        session.setBatchId(batchId);
        session.setEmployeeId(employeeId);
        session.setCheckInTime(LocalDateTime.now());
        session.setStatus(BatchWorkSession.Status.WORKING);
        session.setCheckinMethod(checkinMethod != null ? checkinMethod : "MANUAL");
        session.setAssignedBy(assignedBy);

        return batchWorkSessionRepository.save(session);
    }

    /**
     * 签退
     */
    @Transactional
    public BatchWorkSession checkout(String factoryId, Long batchId, Long employeeId) {
        BatchWorkSession session = batchWorkSessionRepository
                .findByBatchIdAndEmployeeId(batchId, employeeId)
                .orElseThrow(() -> new RuntimeException("未找到签到记录"));

        session.setCheckOutTime(LocalDateTime.now());
        session.setStatus(BatchWorkSession.Status.COMPLETED);

        if (session.getCheckInTime() != null) {
            long minutes = java.time.Duration.between(session.getCheckInTime(), session.getCheckOutTime()).toMinutes();
            session.setWorkMinutes((int) minutes);
        }

        return batchWorkSessionRepository.save(session);
    }

    /**
     * 获取汇总统计（含报工看板增强字段）
     */
    public Map<String, Object> getSummary(String factoryId, LocalDate startDate, LocalDate endDate) {
        if (startDate == null) startDate = LocalDate.now().minusDays(7);
        if (endDate == null) endDate = LocalDate.now();

        Map<String, Object> summary = new HashMap<>();
        summary.put("progressSummary", reportRepository.getProgressSummary(factoryId, startDate, endDate));
        summary.put("hoursSummary", reportRepository.getHoursSummary(factoryId, startDate, endDate));
        summary.put("todayCount", reportRepository.countByFactoryIdAndDate(factoryId, LocalDate.now()));

        // 报工看板增强字段
        summary.put("pendingApprovalCount", countPendingReports(factoryId));

        // 今日产出与良品率
        Map<String, Object> todayProgress = reportRepository.getProgressSummary(
                factoryId, LocalDate.now(), LocalDate.now());
        BigDecimal todayOutput = BigDecimal.ZERO;
        BigDecimal todayGood = BigDecimal.ZERO;
        if (todayProgress != null) {
            Object outputObj = todayProgress.get("total_output");
            Object goodObj = todayProgress.get("total_good");
            if (outputObj != null) todayOutput = new BigDecimal(outputObj.toString());
            if (goodObj != null) todayGood = new BigDecimal(goodObj.toString());
        }
        summary.put("todayOutputTotal", todayOutput);
        BigDecimal yieldRate = BigDecimal.ZERO;
        if (todayOutput.compareTo(BigDecimal.ZERO) > 0) {
            yieldRate = todayGood.multiply(BigDecimal.valueOf(100))
                    .divide(todayOutput, 1, java.math.RoundingMode.HALF_UP);
        }
        summary.put("todayYieldRate", yieldRate);

        // 近7天每日产出（用于趋势图）
        LocalDate weekStart = LocalDate.now().minusDays(6);
        summary.put("weeklyOutput", reportRepository.getDailyProductionTrend(factoryId, weekStart, LocalDate.now()));

        return summary;
    }

    /**
     * 获取最近一次报工记录（傻瓜化预填）
     */
    public WorkReportResponse getLastReport(String factoryId, Long workerId, String reportType) {
        ProductionReport report = reportRepository
                .findTopByFactoryIdAndWorkerIdAndReportTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
                        factoryId, workerId, reportType);
        return report != null ? toResponse(report) : null;
    }

    /**
     * 获取历史均值（异常检测用）
     */
    public Map<String, Object> getHistoricalAverage(String factoryId, String processCategory, int days) {
        LocalDate sinceDate = LocalDate.now().minusDays(days);
        Map<String, Object> raw = reportRepository.getHistoricalAverageByProcess(
                factoryId, processCategory, sinceDate);
        if (raw == null) return null;

        Map<String, Object> result = new HashMap<>();
        result.put("avgOutput", toDouble(raw.get("avg_output")));
        result.put("stddevOutput", toDouble(raw.get("stddev_output")));
        result.put("avgDefect", toDouble(raw.get("avg_defect")));
        result.put("sampleCount", toLong(raw.get("sample_count")));
        return result;
    }

    private double toDouble(Object obj) {
        return obj != null ? Double.parseDouble(obj.toString()) : 0.0;
    }

    private long toLong(Object obj) {
        return obj != null ? Long.parseLong(obj.toString()) : 0L;
    }

    // --- private helpers ---

    private void updateBatchActualQuantity(String factoryId, Long batchId, BigDecimal outputQuantity) {
        try {
            // 跨租户校验: 批次须属于当前工厂 (findByIdAndFactoryId 过滤, 避免把产量累计到别厂批次)
            Optional<ProductionBatch> batchOpt = productionBatchRepository.findByIdAndFactoryId(batchId, factoryId);
            if (batchOpt.isPresent()) {
                ProductionBatch batch = batchOpt.get();
                BigDecimal current = batch.getActualQuantity() != null ? batch.getActualQuantity() : BigDecimal.ZERO;
                batch.setActualQuantity(current.add(outputQuantity));
                productionBatchRepository.save(batch);

                // 自动完成：累计产量达到计划量 → 完成批次 → 发布事件
                checkAndCompleteBatch(batch);
            } else {
                log.warn("⚠️ 报工关联的批次不存在: batchId={}, outputQuantity={}，产量未累计到批次", batchId, outputQuantity);
            }
        } catch (Exception e) {
            log.warn("更新批次实际产量失败: batchId={}, error={}", batchId, e.getMessage());
        }
    }

    /**
     * 检查并完成批次：若 actualQuantity >= plannedQuantity 且状态非 COMPLETED，则自动完成
     */
    private void checkAndCompleteBatch(ProductionBatch batch) {
        if (batch.getStatus() == ProductionBatchStatus.COMPLETED) {
            return; // 幂等：已完成不重复触发
        }
        if (batch.getPlannedQuantity() == null || batch.getActualQuantity() == null) {
            return;
        }
        if (batch.getActualQuantity().compareTo(batch.getPlannedQuantity()) >= 0) {
            batch.setStatus(ProductionBatchStatus.COMPLETED);
            batch.setEndTime(LocalDateTime.now());
            // 如果 goodQuantity 未设置，默认等于 actualQuantity
            if (batch.getGoodQuantity() == null) {
                batch.setGoodQuantity(batch.getActualQuantity());
            }
            productionBatchRepository.save(batch);
            log.info("报工触发批次自动完成: batchId={}, actual={}, planned={}",
                    batch.getId(), batch.getActualQuantity(), batch.getPlannedQuantity());

            // 发布 BatchCompletedEvent → SupplyChainOrchestrator.onBatchCompleted()
            eventPublisher.publishEvent(new BatchCompletedEvent(this, batch));
        }
    }

    /**
     * 手动完成批次（管理员操作：部分生产、物料不足等场景）
     */
    @Transactional
    public void manualCompleteBatch(String factoryId, Long batchId) {
        ProductionBatch batch = productionBatchRepository.findById(batchId)
                .orElseThrow(() -> new RuntimeException("生产批次不存在: " + batchId));
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new RuntimeException("无权操作此批次");
        }
        if (batch.getStatus() == ProductionBatchStatus.COMPLETED) {
            throw new RuntimeException("批次已完成，无需重复操作");
        }
        if (batch.getStatus() != ProductionBatchStatus.IN_PROGRESS) {
            throw new RuntimeException("仅进行中的批次可标记完成，当前状态: " + batch.getStatus());
        }

        batch.setStatus(ProductionBatchStatus.COMPLETED);
        batch.setEndTime(LocalDateTime.now());
        if (batch.getGoodQuantity() == null) {
            batch.setGoodQuantity(batch.getActualQuantity() != null ? batch.getActualQuantity() : BigDecimal.ZERO);
        }
        productionBatchRepository.save(batch);
        log.info("手动完成批次: batchId={}, actual={}", batch.getId(), batch.getActualQuantity());

        eventPublisher.publishEvent(new BatchCompletedEvent(this, batch));
    }

    /**
     * 查询待审批报工列表
     */
    public Page<WorkReportResponse> getPendingReports(String factoryId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return reportRepository.findByFactoryIdAndStatusAndDeletedAtIsNull(
                factoryId, ProductionReport.Status.SUBMITTED, pageRequest)
                .map(this::toResponse);
    }

    /**
     * 统计待审批报工数
     */
    public long countPendingReports(String factoryId) {
        return reportRepository.countByFactoryIdAndStatusAndDeletedAtIsNull(
                factoryId, ProductionReport.Status.SUBMITTED);
    }

    private WorkReportResponse toResponse(ProductionReport r) {
        return WorkReportResponse.builder()
                .id(r.getId())
                .factoryId(r.getFactoryId())
                .batchId(r.getBatchId())
                .workerId(r.getWorkerId())
                .reportType(r.getReportType())
                .schemaId(r.getSchemaId())
                .reportDate(r.getReportDate())
                .reporterName(r.getReporterName())
                .processCategory(r.getProcessCategory())
                .productName(r.getProductName())
                .outputQuantity(r.getOutputQuantity())
                .goodQuantity(r.getGoodQuantity())
                .defectQuantity(r.getDefectQuantity())
                .totalWorkMinutes(r.getTotalWorkMinutes())
                .totalWorkers(r.getTotalWorkers())
                .operationVolume(r.getOperationVolume())
                .hourEntries(r.getHourEntries())
                .nonProductionEntries(r.getNonProductionEntries())
                .productionStartTime(r.getProductionStartTime())
                .productionEndTime(r.getProductionEndTime())
                .customFields(r.getCustomFields())
                .photos(r.getPhotos())
                .status(r.getStatus())
                .rejectionReason(r.getRejectionReason())
                .syncedToSmartbi(r.getSyncedToSmartbi())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
