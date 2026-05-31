package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.MaterialInputRequest;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.yield.YieldCalculationService;
import com.cretas.aims.service.yield.YieldReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class YieldReportServiceImpl implements YieldReportService {

    private static final String YIELD = "YIELD";
    private final ProductionReportRepository reportRepo;
    private final WorkProcessTaskRepository taskRepo;
    private final WorkProcessRepository processRepo;
    private final YieldCalculationService calcSvc;
    private final ProcessingService processingService;

    @Override
    @Transactional
    public Map<String, Object> submitReport(String factoryId, Long batchId, Long workerId, YieldReportRequest req) {
        if (req.getWorkProcessTaskId() == null) {
            throw new BusinessException(400, "缺少必填字段: workProcessTaskId")
                    .withHint("请选择工序任务").withHintTarget("workProcessTaskId");
        }
        if (req.getOutputQuantity() == null) {
            throw new BusinessException(400, "缺少必填字段: outputQuantity")
                    .withHint("请填写本道产出量").withHintTarget("outputQuantity");
        }
        WorkProcessTask t = taskRepo.findByFactoryIdAndId(factoryId, req.getWorkProcessTaskId())
                .orElseThrow(() -> new BusinessException(404, "工序任务不存在: " + req.getWorkProcessTaskId()));

        Long effectiveWorker = req.getTargetWorkerId() != null ? req.getTargetWorkerId() : workerId;

        // 前置查该 task 已有 YIELD 报工: 决定是否首条 + 作双写求和基数
        List<ProductionReport> existingTaskReports = reportRepo.findYieldReportsByTask(factoryId, t.getId());
        boolean isFirstReportForTask = existingTaskReports.isEmpty();

        ProductionReport r = ProductionReport.builder()
                .factoryId(factoryId).batchId(batchId).reportType(YIELD)
                .workerId(effectiveWorker).reporterName(req.getReporterName())
                .reportDate(LocalDate.now())
                .workProcessTaskId(t.getId()).processOrder(t.getProcessOrder())
                .productTypeId(t.getProductTypeId())
                .inputQuantity(req.getInputQuantity()).inputUnit(req.getInputUnit())
                .outputQuantity(req.getOutputQuantity()).outputUnit(req.getOutputUnit())
                .totalWorkMinutes(req.getWorkMinutes())
                .sourceBatchRefs(req.getSourceBatchRefs())
                // 工序批次号是任务级: 仅首条报工生成, 后续条 null (避免 uq_pr_intermediate_batch_no 冲突)
                .intermediateBatchNo(isFirstReportForTask ? generateBatchNo(t, batchId) : null)
                .status(ProductionReport.Status.SUBMITTED)
                .build();

        // carryover = 上道总产出 - 本道投入 (单批记录值, 不进库存)
        r.setCarryoverQuantity(computeCarryover(factoryId, batchId, t, req.getInputQuantity()));

        ProductionReport saved = reportRepo.save(r);

        // 双写 WorkProcessTask.actualQuantity = Σ该任务 YIELD output (权威=YIELD, 老字段保兼容)
        // 已有报工产出 + 本次 (显式加本次, 不依赖 JPQL flush 时序)
        BigDecimal taskTotal = existingTaskReports.stream()
                .map(ProductionReport::getOutputQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (saved.getOutputQuantity() != null) {
            taskTotal = taskTotal.add(saved.getOutputQuantity());
        }
        t.setActualQuantity(taskTotal);
        taskRepo.save(t);

        Map<String, Object> out = new HashMap<>();
        out.put("reportId", saved.getId());

        BigDecimal yieldRate = null;
        if (req.getInputUnit() != null && req.getInputUnit().equals(req.getOutputUnit())
                && req.getInputQuantity() != null && req.getInputQuantity().compareTo(BigDecimal.ZERO) > 0) {
            yieldRate = req.getOutputQuantity().divide(req.getInputQuantity(), 4, RoundingMode.HALF_UP);
        }
        out.put("yieldRate", yieldRate);
        String alert = yieldAlert(t.getWorkProcessId(), yieldRate);
        if (alert != null) out.put("alert", alert);
        return out;
    }

    private String yieldAlert(String workProcessId, BigDecimal yieldRate) {
        if (yieldRate == null || workProcessId == null) return null;
        Optional<WorkProcess> wpOpt = processRepo.findById(workProcessId);
        if (wpOpt.isEmpty()) return null;
        WorkProcess wp = wpOpt.get();
        if (wp.getStandardYieldMin() != null && yieldRate.compareTo(wp.getStandardYieldMin()) < 0) return "BELOW_MIN";
        if (wp.getStandardYieldMax() != null && yieldRate.compareTo(wp.getStandardYieldMax()) > 0) return "ABOVE_MAX";
        return null;
    }

    private BigDecimal computeCarryover(String factoryId, Long batchId, WorkProcessTask t, BigDecimal thisInput) {
        if (t.getProcessOrder() == null || t.getProcessOrder() <= 1 || thisInput == null) return null;
        List<ProductionReport> all = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        BigDecimal prevOutput = all.stream()
                .filter(x -> x.getProcessOrder() != null && x.getProcessOrder() == t.getProcessOrder() - 1)
                .map(ProductionReport::getOutputQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (prevOutput.compareTo(BigDecimal.ZERO) == 0) return null;
        return prevOutput.subtract(thisInput);
    }

    private String generateBatchNo(WorkProcessTask t, Long batchId) {
        // {产品码}-B{批次}-S{工序序}-{taskId} 人易读 + 跨 SKU 唯一 (张权 A6)
        return String.format("%s-B%d-S%d-%d",
                t.getProductTypeId() == null ? "NA" : t.getProductTypeId(),
                batchId, t.getProcessOrder() == null ? 0 : t.getProcessOrder(), t.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> recordMaterialInput(String factoryId, Long batchId, Long workerId, MaterialInputRequest req) {
        if (req.getWorkProcessTaskId() == null) {
            throw new BusinessException(400, "缺少必填字段: workProcessTaskId")
                    .withHint("请选择首道工序任务").withHintTarget("workProcessTaskId");
        }
        WorkProcessTask t = taskRepo.findByFactoryIdAndId(factoryId, req.getWorkProcessTaskId())
                .orElseThrow(() -> new BusinessException(404, "工序任务不存在: " + req.getWorkProcessTaskId()));

        ProductionReport r = ProductionReport.builder()
                .factoryId(factoryId).batchId(batchId).reportType(YIELD)
                .workerId(workerId).reportDate(LocalDate.now())
                .workProcessTaskId(t.getId()).processOrder(t.getProcessOrder())
                .productTypeId(t.getProductTypeId())
                .warehouseOutQuantity(req.getWarehouseOutQuantity())
                .feedInQuantity(req.getFeedInQuantity())
                .inputQuantity(req.getFeedInQuantity())   // 投料量落首道 input
                .inputUnit(req.getInputUnit())
                .status(ProductionReport.Status.SUBMITTED)
                .build();
        ProductionReport saved = reportRepo.save(r);
        Map<String, Object> out = new HashMap<>();
        out.put("reportId", saved.getId());
        return out;
    }

    @Override
    public BatchYieldDTO getYield(String factoryId, Long batchId) {
        List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        return calcSvc.calculateBatchYield(reports, null);
    }

    @Override
    @Transactional
    public Map<String, Object> settleDay(String factoryId, Long batchId, Long workerId, LocalDate date, boolean triggerComplete) {
        LocalDate d = date != null ? date : LocalDate.now();
        List<ProductionReport> unsettled = reportRepo.findUnsettledYieldReports(factoryId, batchId, d);
        for (ProductionReport r : unsettled) {
            r.setSettled(true);
            r.setSettledAt(LocalDateTime.now());
        }
        reportRepo.saveAll(unsettled);

        Map<String, Object> out = new HashMap<>();
        out.put("settledCount", unsettled.size());
        BatchYieldDTO batchYield = getYield(factoryId, batchId);
        out.put("batchYield", batchYield);

        boolean completed = false;
        if (triggerComplete && batchYield.getLastStepOutput() != null
                && batchYield.getLastStepOutput().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal lastOutput = batchYield.getLastStepOutput();
            processingService.completeProduction(factoryId, String.valueOf(batchId),
                    lastOutput, lastOutput, BigDecimal.ZERO);
            completed = true;
        }
        out.put("completed", completed);
        return out;
    }
}
