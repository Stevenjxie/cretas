package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.FactorySettingsDTO;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.MaterialBatchRef;
import com.cretas.aims.dto.yield.MaterialInputRequest;
import com.cretas.aims.dto.yield.YieldLimitsDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
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
    private final ProductTypeRepository productTypeRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final SemiFinishedInventoryRepository wipRepo;
    private final BatchLineageEdgeRepository lineageEdgeRepo;
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

        // — G7 部分领用防呆 (Rule 1): 报工带 sourceWipNo 时, 校验 inputQuantity ≤ 源 WIP 余额 —
        // 校验在保存前 (超额不落库); 实际扣减在保存后 (order: validate → save → consume)。
        // sourceWipNo=null → 走旧路径 (首道领原料 / 老批次, 向后兼容, 不查 WIP)。
        SemiFinishedInventory sourceWip = null;
        if (req.getSourceWipNo() != null && !req.getSourceWipNo().isBlank()) {
            sourceWip = wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull(req.getSourceWipNo())
                    .orElseThrow(() -> new BusinessException(404, "源半成品库存不存在: " + req.getSourceWipNo())
                            .withHint("请重新选择要领用的上道半成品批次")
                            .withHintTarget("sourceWipNo"));
            BigDecimal want = req.getInputQuantity();
            BigDecimal avail = sourceWip.getAvailableQuantity() == null
                    ? BigDecimal.ZERO : sourceWip.getAvailableQuantity();
            if (want != null && want.compareTo(avail) > 0) {
                String u = sourceWip.getUnit() == null ? "" : sourceWip.getUnit();
                throw new BusinessException(409, "领用量超过半成品余额")
                        .withCode("WIP_INSUFFICIENT")
                        .withHint(String.format("WIP 余额仅 %s %s, 不能领 %s %s",
                                avail.stripTrailingZeros().toPlainString(), u,
                                want.stripTrailingZeros().toPlainString(), u))
                        .withSeverity("BLOCKING")
                        .withHintTarget("inputQuantity");
            }
        }

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
                .totalWorkers(req.getWorkerCount())   // P1-3 (G4): 本道人数
                .sourceBatchRefs(req.getSourceBatchRefs())
                // A2b: 领料批次引用直接挂在报工单上 (不再单独调用 recordMaterialInput)
                .materialBatchRefs(toMaterialBatchRefMaps(req.getMaterialBatchRefs()))
                // 工序批次号是任务级: 仅首条报工生成, 后续条 null (避免 uq_pr_intermediate_batch_no 冲突)
                .intermediateBatchNo(isFirstReportForTask ? generateBatchNo(t, batchId) : null)
                // G7: 本道领用的源 WIP 工序批次号 (向后兼容: null 走旧路径)
                .sourceWipNo(req.getSourceWipNo())
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

        // — G7: 扣减源 WIP (报工保存后, 同事务) —
        // sourceWip 已在保存前防呆校验过 (inputQuantity ≤ available); 此处实际递减。
        if (sourceWip != null && req.getInputQuantity() != null) {
            consumeSourceWip(sourceWip, req.getInputQuantity(), saved, t, effectiveWorker);
        }

        // — G6: 本道产出进 WIP (报工保存后, 同事务; 按 intermediateBatchNo 幂等, 跨天累加) —
        if (saved.getOutputQuantity() != null && saved.getOutputQuantity().compareTo(BigDecimal.ZERO) > 0) {
            upsertProducedWip(factoryId, batchId, t, saved);
        }

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

        // G7 防呆 Rule 1: 本道可领的源 WIP 余额 = Σ 上道工序产出的 AVAILABLE WIP available_quantity。
        // 首道 (processOrder<=1 或上道无 WIP 行) → null (领原料, 不受 WIP 约束)。
        BigDecimal wipAvailable = resolveSourceWipAvailable(factoryId, batchId, t.getProcessOrder());

        return YieldLimitsDTO.builder()
                .workProcessTaskId(workProcessTaskId)
                .targetQuantity(targetQuantity)
                .standardYieldMax(syMax)
                .unit(unit)
                .alreadyReported(alreadyReported)
                .toleranceRate(toleranceRate)
                .maxAllowed(maxAllowed)
                .remaining(remaining)
                .wipAvailable(wipAvailable)
                .message(message)
                .build();
    }

    /**
     * G7: 计算本道 (processOrder) 可领的源 WIP 余额 = 上道 (processOrder-1) 全部 AVAILABLE WIP 的
     * Σ available_quantity。供 RN 领用 input 的 {@code :max} 防呆。
     *
     * <p>首道 (processOrder=null 或 ≤1) 或上道无 WIP 行 → null (领原料, 不受 WIP 余额约束)。
     * 上道有 WIP 行但已领空 → 0。</p>
     */
    private BigDecimal resolveSourceWipAvailable(String factoryId, Long batchId, Integer processOrder) {
        if (processOrder == null || processOrder <= 1 || batchId == null) {
            return null;  // 首道领原料, 无源 WIP
        }
        int prevOrder = processOrder - 1;
        List<SemiFinishedInventory> wips =
                wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, batchId);
        List<SemiFinishedInventory> prev = wips.stream()
                .filter(w -> w.getProcessOrder() != null && w.getProcessOrder() == prevOrder)
                .filter(w -> !SemiFinishedInventory.Status.RETURNED.equals(w.getStatus()))
                .collect(Collectors.toList());
        if (prev.isEmpty()) {
            return null;  // 上道还没产出 WIP (例如本道是非 WIP 路径), 不约束
        }
        return prev.stream()
                .map(w -> nz(w.getAvailableQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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

    // ==================== G6/G7 WIP (Wave 2) ====================

    /**
     * G6: 本道产出 upsert 进 WIP 库存 (按 task 的工序批次号幂等)。
     *
     * <p>幂等键 = {@link #generateBatchNo} 的稳定派生 (task-level, 与首条报工生成的
     * {@code intermediate_batch_no} 一致)。后续条报工 {@code report.intermediateBatchNo} 为 null,
     * 故这里**重新派生**同一稳定键, 命中同一 WIP 行累加 {@code produced/available} (跨天天然支持)。</p>
     *
     * <p>溯源 {@code materialBatchRefs} 从产出报工继承 (首条建行时写, 后续累加不覆盖)。</p>
     */
    private void upsertProducedWip(String factoryId, Long batchId, WorkProcessTask t, ProductionReport saved) {
        String wipNo = generateBatchNo(t, batchId);
        BigDecimal out = saved.getOutputQuantity();
        SemiFinishedInventory wip = wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull(wipNo).orElse(null);
        if (wip == null) {
            wip = SemiFinishedInventory.builder()
                    .factoryId(factoryId)
                    .batchId(batchId)
                    .intermediateBatchNo(wipNo)
                    .sourceWorkProcessTaskId(t.getId())
                    .processOrder(t.getProcessOrder())
                    .productTypeId(t.getProductTypeId())
                    .producedQuantity(out)
                    .consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(out)
                    .unit(saved.getOutputUnit())
                    .status(SemiFinishedInventory.Status.AVAILABLE)
                    .materialBatchRefs(saved.getMaterialBatchRefs())  // 从产出报工继承溯源
                    .build();
        } else {
            // 跨天 / 同 task 多次报工: 累加产出与余额 (consumed 不变)
            BigDecimal produced = nz(wip.getProducedQuantity()).add(out);
            BigDecimal consumed = nz(wip.getConsumedQuantity());
            wip.setProducedQuantity(produced);
            wip.setAvailableQuantity(produced.subtract(consumed));
            // 重新累加产出后余额>0 → 回到 AVAILABLE (即便此前被领空 DEPLETED)
            if (wip.getAvailableQuantity().compareTo(BigDecimal.ZERO) > 0
                    && !SemiFinishedInventory.Status.RETURNED.equals(wip.getStatus())) {
                wip.setStatus(SemiFinishedInventory.Status.AVAILABLE);
            }
            if (wip.getUnit() == null) wip.setUnit(saved.getOutputUnit());
        }
        wipRepo.save(wip);
    }

    /**
     * G7: 扣减源 WIP 余额 (已在调用前防呆校验 inputQuantity ≤ available)。
     *
     * <p>consumed += input; available = produced − consumed; 余额=0 → DEPLETED。
     * 乐观锁 (@Version, Wave1) 防并发超领: 同事务内扣减, 冲突时抛 OptimisticLockException 回滚。</p>
     *
     * <p>顺手写一条 PRODUCTION→PRODUCTION lineage 边 (副产物, 复用 closure trigger), fail-soft。</p>
     */
    private void consumeSourceWip(SemiFinishedInventory sourceWip, BigDecimal input,
                                  ProductionReport saved, WorkProcessTask t, Long workerId) {
        BigDecimal consumed = nz(sourceWip.getConsumedQuantity()).add(input);
        BigDecimal produced = nz(sourceWip.getProducedQuantity());
        sourceWip.setConsumedQuantity(consumed);
        sourceWip.setAvailableQuantity(produced.subtract(consumed));
        if (sourceWip.getAvailableQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            sourceWip.setStatus(SemiFinishedInventory.Status.DEPLETED);
        }
        wipRepo.save(sourceWip);

        // lineage 副产物: 源 WIP (上道) → 本道产出工序批次号 (PRODUCTION→PRODUCTION)。
        recordWipLineageEdge(saved.getFactoryId(), sourceWip, t, saved.getBatchId(), input, workerId);
    }

    /**
     * lineage 写入器 (最小实现, Wave 2 还 lineage 死骨架债)。
     *
     * <p>WIP 领用时写一条 PRODUCTION_BATCH → PRODUCTION_BATCH 有向边: source = 源 WIP 所在批次,
     * target = 本道所在批次 (同批工序流转)。插入触发 {@code fn_maintain_lineage_closure} 维护闭包。</p>
     *
     * <p><b>fail-soft</b>: lineage 是溯源副产物, 非库存权威源。写边失败不阻塞报工主线
     * (per brief: lineage 风险高可降级)。只记 WARN, 不抛。</p>
     */
    private void recordWipLineageEdge(String factoryId, SemiFinishedInventory sourceWip,
                                      WorkProcessTask t, Long batchId, BigDecimal qty, Long workerId) {
        try {
            BatchLineageEdge edge = new BatchLineageEdge();
            edge.setFactoryId(factoryId);
            // 同批工序间 WIP 领用流转 (源 WIP→本道); 文档化 edge_type 集合无 intra-batch 流转值,
            // 用 WIP_CONSUME 明示 (VARCHAR(30) 无 CHECK 约束)。
            edge.setEdgeType("WIP_CONSUME");
            edge.setSourceType("PRODUCTION_BATCH");
            edge.setSourceId(sourceWip.getBatchId() == null
                    ? String.valueOf(batchId) : String.valueOf(sourceWip.getBatchId()));
            edge.setTargetType("PRODUCTION_BATCH");
            edge.setTargetId(String.valueOf(batchId));
            edge.setQuantityUsed(qty);
            edge.setUnit(sourceWip.getUnit());
            edge.setEventTime(LocalDateTime.now());
            edge.setOperatorId(workerId);
            Map<String, Object> meta = new HashMap<>();
            meta.put("sourceWipNo", sourceWip.getIntermediateBatchNo());
            meta.put("targetWorkProcessTaskId", t.getId());
            meta.put("targetProcessOrder", t.getProcessOrder());
            edge.setMeta(meta);
            lineageEdgeRepo.save(edge);
        } catch (Exception e) {
            log.warn("[lineage] WIP 领用边写入失败 (fail-soft, 不阻塞报工): sourceWipNo={} batchId={} qty={}",
                    sourceWip.getIntermediateBatchNo(), batchId, qty, e);
        }
    }

    /** null-safe BigDecimal: null → ZERO. */
    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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
        // P0-2: 解析末道产品标准克重, 打通 kg↔份 折算 (跨单位且无克重时 cumulative 保持 null, 诚实)
        BigDecimal gramsPerUnit = resolveGramsPerUnit(factoryId, reports);
        BatchYieldDTO dto = calcSvc.calculateBatchYield(reports, gramsPerUnit);
        enrichProcessNames(factoryId, dto);
        // G8 Wave 3 (C): 进行中标注 — 算在制 WIP 总量 + 批次完工判定
        enrichInProgressAnnotation(factoryId, batchId, dto);
        return dto;
    }

    /**
     * G8 Wave 3 (C): 填充进行中标注字段 (展示层防呆, 拍板 A 主算法 + C 标注)。
     *
     * <p><b>inProgress 判定</b> = 批次未完工 (status ≠ COMPLETED/CANCELLED) <b>或</b> 仍有在制 WIP 余额
     * (Σ AVAILABLE.availableQuantity &gt; 0)。完工 (COMPLETED) 且 WIP 全清零 → inProgress=false, 数字锁定。</p>
     *
     * <p><b>wipInProgressQuantity</b> = Σ 该批次所有 AVAILABLE WIP 行的 available_quantity
     * (尚未变成成品的中间品总量)。inProgress=false 时为 ZERO。</p>
     *
     * <p><b>cumulativeYieldRate 不变</b>: 始终是 A 完工口径 (calc 算的末道÷首道)。本方法只加展示标注,
     * 不改算法 (per 设计章一 ★推荐)。asOfYieldRate 当前与 A 口径同源, 仅语义标注。</p>
     */
    private void enrichInProgressAnnotation(String factoryId, Long batchId, BatchYieldDTO dto) {
        if (batchId == null) {
            return;  // 无批次上下文 (理论不达; 防御): 不标注
        }
        // 1) 该批次在制 WIP 总量 = Σ AVAILABLE.available_quantity (RETURNED/DEPLETED 不计)
        List<SemiFinishedInventory> wips =
                wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, batchId);
        BigDecimal wipPending = BigDecimal.ZERO;
        String wipUnit = null;
        for (SemiFinishedInventory w : wips) {
            if (!SemiFinishedInventory.Status.AVAILABLE.equals(w.getStatus())) continue;
            BigDecimal avail = nz(w.getAvailableQuantity());
            if (avail.compareTo(BigDecimal.ZERO) <= 0) continue;
            wipPending = wipPending.add(avail);
            if (wipUnit == null && w.getUnit() != null) wipUnit = w.getUnit();
        }
        boolean hasWipPending = wipPending.compareTo(BigDecimal.ZERO) > 0;

        // 2) 批次完工判定: COMPLETED/CANCELLED 视为已完工 (终态), 其余 (PLANNED/IN_PROGRESS/PAUSED) 视为进行中
        ProductionBatch pb = productionBatchRepository.findByIdAndFactoryId(batchId, factoryId).orElse(null);
        boolean batchFinished = pb != null
                && (pb.getStatus() == ProductionBatchStatus.COMPLETED
                    || pb.getStatus() == ProductionBatchStatus.CANCELLED);

        // 进行中 = 批次未完工 OR 仍有在制 WIP 余额
        boolean inProgress = !batchFinished || hasWipPending;

        dto.setInProgress(inProgress);
        dto.setWipInProgressQuantity(inProgress ? wipPending : BigDecimal.ZERO);
        dto.setWipInProgressUnit(hasWipPending ? wipUnit : null);
        // asOfYieldRate: 进行中参考数, 当前与 A 口径同源 (末道总产出/首道总投入)
        dto.setAsOfYieldRate(dto.getCumulativeYieldRate());
    }

    /**
     * P0-2: 取末道报工的 productTypeId → ProductType.gramsPerUnit (份/盒→kg 折算系数).
     * reports 为空 / 无 productTypeId / 产品无克重 → null (calc 据此保持 cumulative=null, 不臆造).
     */
    private BigDecimal resolveGramsPerUnit(String factoryId, List<ProductionReport> reports) {
        if (reports == null || reports.isEmpty()) return null;
        // 同批同产品, 取任一 report 的 productTypeId 即可
        String productTypeId = reports.stream()
                .map(ProductionReport::getProductTypeId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (productTypeId == null) return null;
        return productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .map(pt -> pt.getGramsPerUnit())
                .orElse(null);
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
        String completeError = null;
        if (triggerComplete && batchYield.getLastStepOutput() != null
                && batchYield.getLastStepOutput().compareTo(BigDecimal.ZERO) > 0) {
            // P1-1: 完工前预校验批次状态. completeProduction 仅允许 IN_PROGRESS/PAUSED, 否则抛 409
            // → 会污染本结清事务(settled 标记随回滚丢失). 先查状态, 仅可完工才调, 否则 completed=false
            // + completeError 透传前端(诚实提示"批次未开始生产"), 不抛异常.
            ProductionBatch pb = productionBatchRepository.findByIdAndFactoryId(batchId, factoryId).orElse(null);
            boolean canComplete = pb != null
                    && (pb.getStatus() == ProductionBatchStatus.IN_PROGRESS
                        || pb.getStatus() == ProductionBatchStatus.PAUSED);
            if (canComplete) {
                BigDecimal lastOutput = batchYield.getLastStepOutput();
                // P0-2: 成品按"末道产出单位"(份/盒) 入库, 份数原值入库 (订单达成率"份对份")
                String finishedUnit = batchYield.getLastStepOutputUnit();
                processingService.completeProduction(factoryId, String.valueOf(batchId),
                        lastOutput, lastOutput, BigDecimal.ZERO, finishedUnit);
                completed = true;
            } else {
                completeError = pb == null
                        ? "批次不存在"
                        : ("批次状态 " + pb.getStatus() + " 不允许完工 (需先开始生产)");
                log.warn("[完工入库] settle-day 触发完工跳过 (结清已成功不回滚): batch={} reason={}",
                        batchId, completeError);
            }
        }
        out.put("completed", completed);
        if (completeError != null) out.put("completeError", completeError);

        // D3 (Wave 3): 完工时若该批次仍有在制半成品结余 (WIP available > 0), 加诚实提示 (不阻塞完工)。
        // per 设计章三表 + fool-proof Rule 5: 提示退回总仓建调拨单, 给 next-action, 不 dead-end。
        if (completed) {
            addWipRemainingHint(factoryId, batchId, out);
        }
        return out;
    }

    /**
     * D3 (Wave 3): 完工后若批次仍有在制 WIP 结余 → 加 {@code wipRemainingHint} 诚实提示 (不阻塞完工)。
     *
     * <p>per 设计章三 + fool-proof Rule 5 (dead-end 改导航): 提示"结余 Xkg 半成品待退回总仓",
     * 让用户知道还有未消耗的中间品该处理 (退回总仓建调拨单)。仅提示, 不自动建单、不拦截完工。</p>
     *
     * <p>fail-soft: 查询/拼装失败不影响完工结果, 只记 WARN。</p>
     */
    private void addWipRemainingHint(String factoryId, Long batchId, Map<String, Object> out) {
        try {
            List<SemiFinishedInventory> remaining = wipRepo.findRemainingWip(factoryId, batchId);
            if (remaining == null || remaining.isEmpty()) return;
            BigDecimal total = BigDecimal.ZERO;
            String unit = null;
            for (SemiFinishedInventory w : remaining) {
                BigDecimal avail = nz(w.getAvailableQuantity());
                if (avail.compareTo(BigDecimal.ZERO) <= 0) continue;
                total = total.add(avail);
                if (unit == null && w.getUnit() != null) unit = w.getUnit();
            }
            if (total.compareTo(BigDecimal.ZERO) <= 0) return;
            String u = unit == null ? "" : unit;
            out.put("wipRemaining", total);
            out.put("wipRemainingUnit", unit);
            out.put("wipRemainingHint", String.format(
                    "结余 %s %s 半成品待退回总仓 (建调拨单退库), 完工不受影响",
                    total.stripTrailingZeros().toPlainString(), u));
        } catch (Exception e) {
            log.warn("[D3] 余料退回提示拼装失败 (fail-soft, 不影响完工): batch={}", batchId, e);
        }
    }
}
