package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.FactorySettingsDTO;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.MaterialBatchRef;
import com.cretas.aims.dto.yield.MaterialInputRequest;
import com.cretas.aims.dto.yield.YieldLimitsDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.yield.YieldCalculationService;
import com.cretas.aims.service.yield.YieldReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cretas.aims.dto.yield.StepYieldDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class YieldReportServiceImpl implements YieldReportService {

    private static final String YIELD = "YIELD";
    private static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("0.30");

    private final ProductionReportRepository reportRepo;
    private final WorkProcessTaskRepository taskRepo;
    private final WorkProcessRepository processRepo;
    private final YieldCalculationService calcSvc;
    private final ProcessingService processingService;
    private final FactorySettingsRepository factorySettingsRepo;
    private final MaterialBatchRepository materialBatchRepository;
    private final ObjectMapper objectMapper;

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
                // A2b: 领料批次引用直接挂在报工单上 (不再单独调用 recordMaterialInput)
                .materialBatchRefs(toMaterialBatchRefMaps(req.getMaterialBatchRefs()))
                // 工序批次号是任务级: 仅首条报工生成, 后续条 null (避免 uq_pr_intermediate_batch_no 冲突)
                .intermediateBatchNo(isFirstReportForTask ? generateBatchNo(t, batchId) : null)
                .status(ProductionReport.Status.SUBMITTED)
                .build();

        // carryover = 上道总产出 - 本道投入 (单批记录值, 不进库存)
        r.setCarryoverQuantity(computeCarryover(factoryId, batchId, t, req.getInputQuantity()));

        // — 超收检查 (A4) —
        // 基准量 = 本道投入 × WorkProcess.standardYieldMax
        BigDecimal inputQty = req.getInputQuantity();
        if (inputQty != null && inputQty.compareTo(BigDecimal.ZERO) > 0) {
            Optional<WorkProcess> wpOpt = processRepo.findById(t.getWorkProcessId());
            BigDecimal syMax = wpOpt.map(WorkProcess::getStandardYieldMax).orElse(null);
            if (syMax != null) {
                BigDecimal target = inputQty.multiply(syMax);
                if (target.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal tolerance = getToleranceForFactory(factoryId);
                    BigDecimal maxAllowed = target.multiply(BigDecimal.ONE.add(tolerance));

                    BigDecimal alreadyReported = existingTaskReports.stream()
                            .map(ProductionReport::getOutputQuantity)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal cumulative = alreadyReported.add(req.getOutputQuantity());

                    if (cumulative.compareTo(maxAllowed) > 0) {
                        boolean force = Boolean.TRUE.equals(req.getForceSubmit());
                        if (!force) {
                            String unit = wpOpt.map(WorkProcess::getUnit).orElse("");
                            String actionHint = String.format(
                                    "已报 %.2f %s, 目标 %.2f %s (投入 %.2f × 标准上限 %.0f%%), 含 %.0f%% 超收容差最多可报 %.2f %s",
                                    alreadyReported, unit,
                                    target, unit,
                                    inputQty, syMax.multiply(BigDecimal.valueOf(100)),
                                    tolerance.multiply(BigDecimal.valueOf(100)),
                                    maxAllowed.setScale(2, RoundingMode.HALF_UP), unit);
                            throw new BusinessException(409, "产出量超过超收容差上限")
                                    .withCode("OVER_RECEIPT")
                                    .withHint(actionHint)
                                    .withSeverity("BLOCKING");
                        }
                        // forceSubmit=true: 记录告警, 正常保存
                        log.warn("[A4-超收] factory={} batch={} task={} target={} cumulative={} maxAllowed={} force=true",
                                factoryId, batchId, t.getId(), target, cumulative, maxAllowed);
                    }
                }
            }
        }

        ProductionReport saved = reportRepo.save(r);

        // A2b: 报工单保存后, 对每个关联批次独立检查自动结清 (order: save → settle)
        if (req.getMaterialBatchRefs() != null) {
            for (MaterialBatchRef ref : req.getMaterialBatchRefs()) {
                if (ref.getMaterialBatchId() != null) {
                    checkAndAutoSettle(factoryId, batchId, ref.getMaterialBatchId());
                }
            }
        }

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

    /**
     * A4: 读取工厂级超收容差. 无配置或解析失败时返回默认 30%.
     * 使用 {@code findProductionSettingsByFactoryId} 投影查询 (仅读 TEXT 列, 不加载完整实体).
     * <p>注意: {@code BusinessException.withCode} 的 errorCode 通过 ApiResponse.errorWithCode 对外暴露,
     * 但 hintTarget 不经该路径传播.</p>
     */
    private BigDecimal getToleranceForFactory(String factoryId) {
        try {
            String json = factorySettingsRepo.findProductionSettingsByFactoryId(factoryId);
            if (json == null) return DEFAULT_TOLERANCE;
            FactorySettingsDTO.ProductionSettings ps =
                    objectMapper.readValue(json, FactorySettingsDTO.ProductionSettings.class);
            return ps.getYieldOverReceiptTolerance() != null
                    ? ps.getYieldOverReceiptTolerance()
                    : DEFAULT_TOLERANCE;
        } catch (Exception e) {
            log.warn("[A4] 读取容差设置失败, 使用默认 30%", e);
            return DEFAULT_TOLERANCE;
        }
    }

    @Override
    public YieldLimitsDTO getLimits(String factoryId, Long batchId, Long workProcessTaskId, BigDecimal inputQuantity) {
        WorkProcessTask t = taskRepo.findByFactoryIdAndId(factoryId, workProcessTaskId)
                .orElseThrow(() -> new BusinessException(404, "工序任务不存在: " + workProcessTaskId));

        String workProcessId = t.getWorkProcessId();
        Optional<WorkProcess> wpOpt = processRepo.findById(workProcessId);
        BigDecimal syMax = wpOpt.map(WorkProcess::getStandardYieldMax).orElse(null);
        String unit = wpOpt.map(WorkProcess::getUnit).orElse("");

        BigDecimal alreadyReported = reportRepo.findYieldReportsByTask(factoryId, t.getId()).stream()
                .map(ProductionReport::getOutputQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal toleranceRate = getToleranceForFactory(factoryId);

        // Compute target / maxAllowed / remaining only when we have a valid base
        BigDecimal targetQuantity = null;
        BigDecimal maxAllowed = null;
        BigDecimal remaining = null;
        String message;

        boolean hasInput = inputQuantity != null && inputQuantity.compareTo(BigDecimal.ZERO) > 0;
        if (!hasInput) {
            message = "未填投入量, 无超收告警";
        } else if (syMax == null) {
            message = "该工序未配置标准出成上限, 无超收告警";
        } else {
            targetQuantity = inputQuantity.multiply(syMax);
            if (targetQuantity.compareTo(BigDecimal.ZERO) > 0) {
                maxAllowed = targetQuantity.multiply(BigDecimal.ONE.add(toleranceRate))
                        .setScale(4, RoundingMode.HALF_UP);
                remaining = maxAllowed.subtract(alreadyReported);
                message = String.format("已报 %.2f %s / 目标 %.2f %s / 含 %.0f%% 超收容差最多可报 %.2f %s",
                        alreadyReported, unit,
                        targetQuantity, unit,
                        toleranceRate.multiply(BigDecimal.valueOf(100)),
                        maxAllowed, unit);
            } else {
                message = "投入量为零, 无超收告警";
            }
        }

        return YieldLimitsDTO.builder()
                .workProcessTaskId(workProcessTaskId)
                .targetQuantity(targetQuantity)
                .standardYieldMax(syMax)
                .unit(unit)
                .alreadyReported(alreadyReported)
                .toleranceRate(toleranceRate)
                .maxAllowed(maxAllowed)
                .remaining(remaining)
                .message(message)
                .build();
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
                .materialBatchRefs(toMaterialBatchRefMaps(req.getMaterialBatchRefs()))  // A2b: 链接领料批次列表
                .status(ProductionReport.Status.SUBMITTED)
                .build();
        ProductionReport saved = reportRepo.save(r);

        // A2b: 保存后对每个关联批次独立检查自动结清
        if (req.getMaterialBatchRefs() != null) {
            for (MaterialBatchRef ref : req.getMaterialBatchRefs()) {
                if (ref.getMaterialBatchId() != null) {
                    checkAndAutoSettle(factoryId, batchId, ref.getMaterialBatchId());
                }
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("reportId", saved.getId());
        return out;
    }

    /**
     * A2b: 将 List<MaterialBatchRef> 转为 List<Map<String,Object>> 用于 jsonb 序列化.
     * null/empty → null (backward-compatible: 仓管员不填则无自动结清路径).
     */
    private List<Map<String, Object>> toMaterialBatchRefMaps(List<MaterialBatchRef> refs) {
        if (refs == null || refs.isEmpty()) return null;
        return refs.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("materialBatchId", r.getMaterialBatchId());
            m.put("quantity", r.getQuantity());
            if (r.getUnit() != null) m.put("unit", r.getUnit());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * A2b: 检查触发批次是否 USED_UP, 若是则查找关联本批次 (batchId) 的未结清 YIELD 报工,
     * 对每条候选报工检查其 material_batch_refs 中全部 materialBatchId 是否均 USED_UP,
     * 全部满足才打 settled=true (all-or-nothing 语义).
     *
     * @return 本次实际结清的报工条数 (0 表示未结清任何报工)
     */
    private int checkAndAutoSettle(String factoryId, Long batchId, String materialBatchId) {
        // 1. 查触发批次状态
        Optional<MaterialBatch> batchOpt = materialBatchRepository.findById(materialBatchId);
        if (batchOpt.isEmpty()) return 0;
        MaterialBatch mb = batchOpt.get();
        // "原料用完" = status 是 USED_UP (权威信号). 排除 EXPIRED/DEFECTIVE/SCRAPPED 等 remaining=0 但非正常耗尽的状态.
        if (mb.getStatus() != MaterialBatchStatus.USED_UP) {
            return 0;  // 触发批次未用完，不触发
        }
        // 2. 找到 material_batch_refs 包含此 materialBatchId 的所有未结清 YIELD 报工
        // materialBatchId is a String (VARCHAR PK) → must be quoted in JSON
        String refJson = "[{\"materialBatchId\":\"" + materialBatchId + "\"}]";
        List<ProductionReport> candidates = reportRepo.findUnsettledYieldContainingMaterialBatch(
                factoryId, batchId, refJson);
        if (candidates.isEmpty()) return 0;
        // 3. 对每条候选报工: 检查其 material_batch_refs 中所有 materialBatchId 是否全部 USED_UP
        LocalDateTime now = LocalDateTime.now();
        List<ProductionReport> toSettle = new ArrayList<>();
        for (ProductionReport candidate : candidates) {
            if (allRefsUsedUp(candidate.getMaterialBatchRefs())) {
                candidate.setSettled(true);
                candidate.setSettledAt(now);
                toSettle.add(candidate);
            }
        }
        if (!toSettle.isEmpty()) {
            reportRepo.saveAll(toSettle);
            log.info("A2b 自动结清: factoryId={}, batchId={}, triggerMaterialBatchId={}, settledCount={}",
                    factoryId, batchId, materialBatchId, toSettle.size());
        }
        return toSettle.size();
    }

    /**
     * A2b: 检查 materialBatchRefs 列表中所有 materialBatchId 是否全部 USED_UP.
     * null/empty → false (无关联批次不触发自动结清).
     */
    private boolean allRefsUsedUp(List<Map<String, Object>> refs) {
        if (refs == null || refs.isEmpty()) return false;
        for (Map<String, Object> ref : refs) {
            Object mbIdObj = ref.get("materialBatchId");
            if (mbIdObj == null) continue;
            // materialBatchId is a String (VARCHAR PK); jsonb deserializes it as String directly.
            String mbId = mbIdObj.toString();
            Optional<MaterialBatch> mb = materialBatchRepository.findById(mbId);
            if (mb.isEmpty()) continue;  // 找不到批次视为忽略
            // 仅 USED_UP 视为"原料用完". EXPIRED/DEFECTIVE 等状态即使 remaining=0 也不触发结清.
            if (mb.get().getStatus() != MaterialBatchStatus.USED_UP) {
                return false;  // 至少一个非 USED_UP → 不结清
            }
        }
        return true;
    }

    @Override
    @Transactional
    public Map<String, Object> autoSettleByMaterialBatch(String factoryId, Long batchId, String materialBatchId) {
        int settled = checkAndAutoSettle(factoryId, batchId, materialBatchId);
        Map<String, Object> out = new HashMap<>();
        out.put("settledCount", settled);
        return out;
    }

    @Override
    public BatchYieldDTO getYield(String factoryId, Long batchId) {
        List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        BatchYieldDTO dto = calcSvc.calculateBatchYield(reports, null);
        enrichProcessNames(factoryId, dto);
        return dto;
    }

    /** audit YIELD-4: 批量查 task→work_process→processName 回填 steps (避免 N+1). 查不到留 null, 前端 fallback. */
    private void enrichProcessNames(String factoryId, BatchYieldDTO dto) {
        if (dto.getSteps() == null || dto.getSteps().isEmpty()) {
            return;
        }
        Set<Long> taskIds = dto.getSteps().stream()
                .map(StepYieldDTO::getWorkProcessTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (taskIds.isEmpty()) {
            return;
        }
        Map<Long, String> taskToProcessId = taskRepo.findByFactoryIdAndIdIn(factoryId, taskIds).stream()
                .filter(t -> t.getWorkProcessId() != null)
                .collect(Collectors.toMap(WorkProcessTask::getId, WorkProcessTask::getWorkProcessId, (a, b) -> a));
        Set<String> processIds = new HashSet<>(taskToProcessId.values());
        Map<String, String> processIdToName = processRepo.findAllById(processIds).stream()
                .collect(Collectors.toMap(WorkProcess::getId, WorkProcess::getProcessName, (a, b) -> a));
        for (StepYieldDTO step : dto.getSteps()) {
            String pid = taskToProcessId.get(step.getWorkProcessTaskId());
            if (pid != null) {
                step.setProcessName(processIdToName.get(pid));
            }
        }
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
