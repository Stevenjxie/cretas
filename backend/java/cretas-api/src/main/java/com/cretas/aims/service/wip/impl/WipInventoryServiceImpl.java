package com.cretas.aims.service.wip.impl;

import com.cretas.aims.dto.yield.OutputOptionsResponse;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.event.ProductionCostUpdatedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WipInventoryServiceImpl implements WipInventoryService {

    private final SemiFinishedInventoryRepository wipRepo;
    private final SemiFinishedInventoryTransactionRepository txnRepo;
    private final ProductionReportRepository reportRepo;
    private final BatchLineageEdgeRepository lineageEdgeRepo;
    private final WorkProcessTaskRepository taskRepo;
    private final WorkProcessRepository workProcessRepo;
    /** SP3: 成本更新事件发布 — 异步回填+预警. */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * W8 BUG-SP1-NEW-ROW 修复: 自引用代理, 用于在 {@code REQUIRES_NEW} 子事务中创建新行,
     * 使 Spring 事务 AOP 代理生效 (同类内 this.method() 调用不走代理, 注解失效)。
     * {@code @Lazy} 打破构造期自引用循环。
     */
    @Autowired
    @Lazy
    private WipInventoryServiceImpl self;

    @Override
    public SemiFinishedInventory validateSourceWip(
            String factoryId, String sourceWipNo, BigDecimal inputQuantity, String inputUnit, Long excludeReportId) {
        if (sourceWipNo == null || sourceWipNo.isBlank()) {
            return null;
        }
        SemiFinishedInventory sourceWip = loadSourceWipForValidation(factoryId, sourceWipNo)
                .orElseThrow(() -> new BusinessException(404, "源半成品库存不存在: " + sourceWipNo)
                        .withHint("请重新选择要领用的上道半成品批次")
                        .withHintTarget("sourceWipNo"));
        if (inputQuantity == null) {
            throw new BusinessException(409, "领用半成品时必须填写本道投入量")
                    .withCode("WIP_INPUT_REQUIRED")
                    .withHint("已选择上道半成品，请填写本道实际投入量")
                    .withSeverity("BLOCKING")
                    .withHintTarget("inputQuantity");
        }
        validateUnit(sourceWip, inputUnit);
        validateAvailable(sourceWip, inputQuantity, pendingReserved(factoryId, sourceWipNo, excludeReportId));
        return sourceWip;
    }

    private java.util.Optional<SemiFinishedInventory> loadSourceWipForValidation(String factoryId, String sourceWipNo) {
        if (factoryId == null || factoryId.isBlank()) {
            return wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull(sourceWipNo);
        }
        return wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, sourceWipNo);
    }

    @Override
    @Transactional
    public void postApprovedOutput(String factoryId, ProductionReport report, WorkProcessTask task, Long operatorId) {
        if (report == null || task == null || task.getProductionBatchId() == null) {
            return;
        }
        if (Boolean.TRUE.equals(report.getCustomFields() == null ? null : report.getCustomFields().get("wipPosted"))) {
            log.info("Skip WIP posting for report {}: already posted", report.getId());
            return;
        }
        if (report.getSourceWipNo() != null && !report.getSourceWipNo().isBlank()
                && report.getInputQuantity() != null) {
            SemiFinishedInventory sourceWip = validateSourceWip(
                    factoryId, report.getSourceWipNo(), report.getInputQuantity(), report.getInputUnit(), report.getId());
            consumeSourceWip(sourceWip, report.getInputQuantity(), report, task, operatorId);
        }
        String outputKind = report.getOutputKind();
        // SP1: SEMI/BOTH → post semi-finished ledger; FINISHED/null(legacy) → existing WIP path
        boolean isSemi = "SEMI".equals(outputKind) || "BOTH".equals(outputKind);
        boolean isFinished = outputKind == null || "FINISHED".equals(outputKind) || "BOTH".equals(outputKind);

        if (isSemi) {
            postSemiOutputLedger(factoryId, report, task, operatorId);
        }

        if (isFinished && report.getOutputQuantity() != null && report.getOutputQuantity().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal rollLabor = report.getLaborCost();
            BigDecimal rollMaterial = report.getMaterialCost();
            if ("OUTPUT".equals(report.getReportKind())) {
                CostRollup rollup = calculateTaskCostRollup(factoryId, task.getId());
                rollLabor = rollup.laborCost();
                rollMaterial = rollup.materialCost();
            }
            SemiFinishedInventory producedWip = upsertProducedWip(factoryId, report, task, rollLabor, rollMaterial);
            // W4 成本链修复: FINISHED/legacy 路径也发 SP3 成本事件 → 回填 SalesOrderItem.costUnitPrice.
            // 此前仅 SEMI 路径 (postSemiOutputLedger) 发事件, 导致整合/旧式报工 (output_kind=null,
            // F006 实际数据全走此路径) 即使算出 WIP unitCost 也永不回填 costUnitPrice → 财审 actualCost 永远 null。
            // 诚实 null 传播: unitCost 为 null (无工价/无料价) 时不发事件 (无成本可回填)。
            if (producedWip != null && producedWip.getUnitCost() != null
                    && task.getProductionBatchId() != null) {
                eventPublisher.publishEvent(new ProductionCostUpdatedEvent(
                        this,
                        factoryId,
                        task.getProductionBatchId(),
                        task.getProductTypeId(),
                        producedWip.getUnitCost(),
                        producedWip.getAccumulatedCost()
                ));
            }
        }
        markWipPosted(report);
    }

    /**
     * SP1: 半成品产出 ledger posting.
     *
     * <p>Rules enforced:
     * <ol>
     *   <li>R1 — SELECT FOR UPDATE on SemiFinishedInventory to prevent concurrent moving-average divergence.</li>
     *   <li>R2 — Runs inside caller's @Transactional (single transaction).</li>
     *   <li>R3 — SemiFinishedInventoryTransaction rows are immutable (no update, only REVERSE rows).</li>
     *   <li>R4 — semiCode/semiOutputQuantity null → skip gracefully (backward compat for old reports).</li>
     *   <li>Fool-Proof Rule 4 — idempotency: same (factoryId, semiCode, IN) guard against double-post.</li>
     * </ol>
     *
     * <p>Moving-average cost formula:
     * <pre>newUnitCost = (oldQty × oldUnitCost + inQty × inUnitCost) / (oldQty + inQty)</pre>
     * Scale-4, ROUND_HALF_UP.  inUnitCost = (laborCost + materialCost) / semiOutputQuantity (honest null if zero qty).
     */
    private void postSemiOutputLedger(String factoryId, ProductionReport report,
                                       WorkProcessTask task, Long operatorId) {
        String semiCode = report.getSemiCode();
        BigDecimal inQty = report.getSemiOutputQuantity();
        // R4: backward-compat — skip if fields absent
        if (semiCode == null || semiCode.isBlank() || inQty == null || inQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[SP1-semi] skip postSemiOutputLedger for report {}: semiCode={} qty={}", report.getId(), semiCode, inQty);
            return;
        }

        // Fool-Proof Rule 4 — idempotency guard
        if (txnRepo.findByFactoryIdAndSourceRefAndTxnType(
                factoryId, semiCode, SemiFinishedInventoryTransaction.TxnType.IN).isPresent()) {
            log.info("[SP1-semi] idempotent skip for report {} semiCode={}: IN txn already exists", report.getId(), semiCode);
            return;
        }

        String outputUnit = firstNonBlank(report.getSemiOutputUnit(),
                firstNonBlank(report.getOutputUnit(), task.getPlannedUnit()));

        // Compute in-unit cost from this report's cost roll-up
        BigDecimal rollLabor = report.getLaborCost();
        BigDecimal rollMaterial = report.getMaterialCost();
        if ("OUTPUT".equals(report.getReportKind())) {
            CostRollup rollup = calculateTaskCostRollup(factoryId, task.getId());
            rollLabor = rollup.laborCost();
            rollMaterial = rollup.materialCost();
        }
        BigDecimal totalCost = nullSafeAdd(null, rollLabor, rollMaterial);
        BigDecimal inUnitCost = null;
        if (totalCost != null && inQty.signum() > 0) {
            inUnitCost = totalCost.divide(inQty, 4, RoundingMode.HALF_UP);
        }

        // R1 + W8 BUG-SP1-NEW-ROW 修复 — ensure-row-then-lock:
        //
        // postSemiOutputLedger 嵌在调用方 approval @Transactional 内 (propagation REQUIRED 加入同一事务)。
        // 历史 bug: findForUpdate 返回 empty 时直接 build+save 新行无并发保护, 两线程同时进 new-row 分支:
        //   - H2 (修复前无 unique) → 2 行重复 = WIP 库存翻倍;
        //   - PG (Flyway partial unique) → 第二个 insert 撞约束 → 整个 approval 事务 doom → 500 + WIP 漏记。
        //
        // 修复: 不存在行时先在 REQUIRES_NEW 子事务里 insert 一条 **0 量空占位行** (ensureRowExists),
        //   独立 commit 与调用方事务解耦。并发竞争撞 unique → 子事务隔离 → catch (对方已建好同行)。
        //   之后主流程总能 findForUpdate 拿到锁 → 走 existing-row moving-average 累加 inQty (在调用方事务内)。
        //
        // 为何 0 量占位安全 (而非全量行): 若调用方 approval 事务事后回滚, 残留的是 0 量空行 (无量无成本),
        //   且 IN ledger txn 也随之回滚 → 下次重试 idempotency guard 放行 → findForUpdate 命中 0 量行 →
        //   累加 0+inQty=inQty → **不双计**。全量行残留则会被重试再次累加 = 双计 bug; 0 量占位天然幂等自愈。
        SemiFinishedInventory sfi = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, semiCode)
                .orElse(null);
        if (sfi == null) {
            ensureSemiRowExists(factoryId, semiCode, task, outputUnit);
            sfi = wipRepo
                    .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, semiCode)
                    .orElseThrow(() -> new IllegalStateException(
                            "[SP1-semi] row missing after ensureSemiRowExists, semiCode=" + semiCode));
        }
        // 统一 moving-average 累加路径 (新建占位行 oldQty=0 → 累加结果与原全量新行字节一致; 既有行直接累加)
        applyMovingAverageIn(sfi, inQty, inUnitCost, totalCost, outputUnit, report.getMaterialBatchRefs());
        wipRepo.save(sfi);

        // Write immutable ledger entry (R3 — no update ever, only REVERSE rows for cancellation)
        BigDecimal balanceAfter = sfi.getAvailableQuantity();
        BigDecimal balanceCostAfter = sfi.getUnitCost();
        SemiFinishedInventoryTransaction txn = SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                .sourceRef(semiCode)
                .quantity(inQty)
                .unitCostAtTxn(inUnitCost)
                .balanceAfter(balanceAfter)
                .balanceCostAfter(balanceCostAfter)
                .reportId(report.getId())
                .operatorId(operatorId)
                .build();
        txnRepo.save(txn);
        log.info("[SP1-semi] posted IN txn for report {} semiCode={} qty={} unitCost={} balanceAfter={}",
                report.getId(), semiCode, inQty, inUnitCost, balanceAfter);

        // SP3: 发布成本更新事件 → 异步回填 costUnitPrice + 超支预警
        if (task.getProductionBatchId() != null) {
            eventPublisher.publishEvent(new ProductionCostUpdatedEvent(
                    this,
                    factoryId,
                    task.getProductionBatchId(),
                    task.getProductTypeId(),
                    sfi.getUnitCost(),
                    sfi.getAccumulatedCost()
            ));
        }
    }

    /**
     * W8 BUG-SP1-NEW-ROW 修复 — moving-average IN 累加 (新建占位行 / 既有行统一走此路径)。
     *
     * <p>把本次 IN ({@code inQty} @ {@code inUnitCost}, totalCost) 累加进 {@code sfi}:
     * <pre>newUnitCost = (oldQty×oldUnitCost + inQty×inUnitCost) / (oldQty + inQty)</pre>
     * Scale-4 HALF_UP。同时更新 produced/available/status/unit/materialBatchRefs/accumulatedCost。
     *
     * <p>新建占位行 (oldQty=0, oldUnitCost=null) 累加: newProduced=inQty, newUnitCost=inUnitCost,
     * accumulatedCost=totalCost → 与历史"全量新行"行为字节一致 (单一累加路径防分叉)。
     *
     * <p>调用前提: {@code sfi} 已持有 PESSIMISTIC_WRITE 行锁 (existing-row 直接拿锁 / 新建占位行重拿锁),
     * 累加在锁保护下串行化。
     */
    private void applyMovingAverageIn(SemiFinishedInventory sfi, BigDecimal inQty, BigDecimal inUnitCost,
                                      BigDecimal totalCost, String outputUnit,
                                      java.util.List<java.util.Map<String, Object>> materialBatchRefs) {
        BigDecimal oldQty = nz(sfi.getProducedQuantity());
        BigDecimal oldUnitCost = sfi.getUnitCost() == null ? BigDecimal.ZERO : sfi.getUnitCost();
        BigDecimal newProduced = oldQty.add(inQty);
        sfi.setProducedQuantity(newProduced);
        BigDecimal consumed = nz(sfi.getConsumedQuantity());
        sfi.setAvailableQuantity(newProduced.subtract(consumed));
        if (sfi.getAvailableQuantity().compareTo(BigDecimal.ZERO) > 0
                && !SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
            sfi.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        }
        if (sfi.getUnit() == null) {
            sfi.setUnit(outputUnit);
        }
        if (sfi.getMaterialBatchRefs() == null && materialBatchRefs != null) {
            sfi.setMaterialBatchRefs(materialBatchRefs);
        }
        // 诚实 null: 老行无成本 (unitCost==null) 且本次也无成本 (inUnitCost==null) → newUnitCost 保持 null,
        //   不退化成 0.0000 (与历史新行路径 unitCost=inUnitCost / existing-row 行为字节一致)。
        BigDecimal newUnitCost = null;
        boolean hasOldCost = sfi.getUnitCost() != null;   // 注意: 此时尚未 setUnitCost, 读到的是累加前的旧值
        boolean hasInCost = inUnitCost != null;
        if (newProduced.signum() > 0 && (hasOldCost || hasInCost)) {
            BigDecimal oldComponent = oldQty.multiply(oldUnitCost);
            BigDecimal inComponent = inUnitCost == null ? BigDecimal.ZERO : inQty.multiply(inUnitCost);
            newUnitCost = oldComponent.add(inComponent).divide(newProduced, 4, RoundingMode.HALF_UP);
        }
        sfi.setUnitCost(newUnitCost);
        sfi.setAccumulatedCost(nullSafeAdd(sfi.getAccumulatedCost(), totalCost));
    }

    /**
     * W8 BUG-SP1-NEW-ROW 修复 — 确保 (factoryId, semiCode) 的 WIP 行存在 (并发安全, 幂等)。
     *
     * <p>在独立 {@code REQUIRES_NEW} 子事务里 insert 一条 **0 量空占位行** 并 commit:
     * <ul>
     *   <li>producedQuantity / consumedQuantity / availableQuantity = 0, accumulatedCost / unitCost = null。
     *       真正的量+成本由调用方在外层事务的 moving-average 路径累加 (0+inQty=inQty)。</li>
     *   <li>子事务独立 commit → 与调用方 approval 事务解耦 → 残留占位行天然幂等自愈 (见 postSemiOutputLedger 注释)。</li>
     * </ul>
     *
     * <p>并发竞争: 另一线程先 insert 同 (factoryId, semiCode) → 撞 unique 约束 →
     * {@link DataIntegrityViolationException}。子事务 REQUIRES_NEW 隔离 → 仅子事务 rollback-only,
     * 不污染调用方事务 → 此处 catch (对方已建好同行, 目标"行存在"已达成) → 正常返回。
     *
     * <p>经 Spring 代理 ({@code self}) 调用使 REQUIRES_NEW 生效 (同类 this 调用绕过 AOP 代理)。
     * 单测无 Spring 代理 (self == null) → fallback 直调 {@link #commitEmptySemiRow} (无真实事务, repo 已 mock)。
     */
    private void ensureSemiRowExists(String factoryId, String semiCode, WorkProcessTask task, String outputUnit) {
        SemiFinishedInventory placeholder = SemiFinishedInventory.builder()
                .factoryId(factoryId)
                .batchId(task.getProductionBatchId())
                .intermediateBatchNo(semiCode)
                .sourceWorkProcessTaskId(task.getId())
                .processOrder(task.getProcessOrder())
                .productTypeId(task.getProductTypeId())
                .producedQuantity(BigDecimal.ZERO)
                .consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(BigDecimal.ZERO)
                .unit(outputUnit)
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .accumulatedCost(null)
                .unitCost(null)
                .build();
        try {
            if (self != null) {
                self.commitEmptySemiRow(placeholder);
            } else {
                commitEmptySemiRow(placeholder);
            }
        } catch (DataIntegrityViolationException dup) {
            // 并发竞争输掉 insert race → 对方已建好同行, 目标 (行存在) 已达成 → 安全继续
            log.info("[SP1-semi] new-row race for semiCode={}: lost insert race (constraint hit), "
                    + "row already created by concurrent tx", semiCode);
        }
    }

    /**
     * W8 BUG-SP1-NEW-ROW 修复 — 在 {@code REQUIRES_NEW} 子事务里 insert 占位行并独立 commit。
     *
     * <p>{@code saveAndFlush} 强制立即 flush → unique 约束冲突即时抛出 (而非延迟到子事务 commit 边界),
     * 使 {@link #ensureSemiRowExists} catch 时机明确。{@code public} + 经 {@code self} 代理调用是
     * REQUIRES_NEW 生效的硬性要求。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitEmptySemiRow(SemiFinishedInventory placeholder) {
        wipRepo.saveAndFlush(placeholder);
    }

    private void markWipPosted(ProductionReport report) {
        Map<String, Object> fields = report.getCustomFields();
        if (fields == null) {
            fields = new HashMap<>();
        } else {
            fields = new HashMap<>(fields);
        }
        fields.put("wipPosted", true);
        fields.put("wipPostedAt", LocalDateTime.now().toString());
        report.setCustomFields(fields);
    }

    private SemiFinishedInventory upsertProducedWip(String factoryId, ProductionReport report, WorkProcessTask task,
                                   BigDecimal rollLaborCost, BigDecimal rollMaterialCost) {
        String wipNo = generateBatchNo(task);
        BigDecimal out = nz(report.getOutputQuantity());
        SemiFinishedInventory wip = wipRepo
                .findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, wipNo)
                .orElse(null);
        String outputUnit = firstNonBlank(report.getOutputUnit(), task.getPlannedUnit());

        if (wip == null) {
            wip = SemiFinishedInventory.builder()
                    .factoryId(factoryId)
                    .batchId(task.getProductionBatchId())
                    .intermediateBatchNo(wipNo)
                    .sourceWorkProcessTaskId(task.getId())
                    .processOrder(task.getProcessOrder())
                    .productTypeId(task.getProductTypeId())
                    .producedQuantity(out)
                    .consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(out)
                    .unit(outputUnit)
                    .status(SemiFinishedInventory.Status.AVAILABLE)
                    .materialBatchRefs(report.getMaterialBatchRefs())
                    .build();
        } else {
            BigDecimal produced = nz(wip.getProducedQuantity()).add(out);
            BigDecimal consumed = nz(wip.getConsumedQuantity());
            wip.setProducedQuantity(produced);
            wip.setAvailableQuantity(produced.subtract(consumed));
            if (wip.getAvailableQuantity().compareTo(BigDecimal.ZERO) > 0
                    && !SemiFinishedInventory.Status.RETURNED.equals(wip.getStatus())) {
                wip.setStatus(SemiFinishedInventory.Status.AVAILABLE);
            }
            if (wip.getUnit() == null) {
                wip.setUnit(outputUnit);
            }
        }

        wip.setAccumulatedCost(nullSafeAdd(wip.getAccumulatedCost(), rollLaborCost, rollMaterialCost));
        BigDecimal produced = wip.getProducedQuantity();
        if (wip.getAccumulatedCost() != null && produced != null && produced.signum() > 0) {
            wip.setUnitCost(wip.getAccumulatedCost().divide(produced, 4, RoundingMode.HALF_UP));
        } else {
            wip.setUnitCost(null);
        }
        return wipRepo.save(wip);
    }

    private CostRollup calculateTaskCostRollup(String factoryId, Long workProcessTaskId) {
        BigDecimal labor = null;
        BigDecimal material = null;
        for (ProductionReport r : reportRepo.findYieldReportsByTask(factoryId, workProcessTaskId)) {
            if (r.getLaborCost() != null) {
                labor = (labor == null ? BigDecimal.ZERO : labor).add(r.getLaborCost());
            }
            if (r.getMaterialCost() != null) {
                material = (material == null ? BigDecimal.ZERO : material).add(r.getMaterialCost());
            }
        }
        return new CostRollup(labor, material);
    }

    private record CostRollup(BigDecimal laborCost, BigDecimal materialCost) {}

    private void consumeSourceWip(SemiFinishedInventory sourceWip, BigDecimal input,
                                  ProductionReport report, WorkProcessTask task, Long operatorId) {
        BigDecimal consumed = nz(sourceWip.getConsumedQuantity()).add(input);
        BigDecimal produced = nz(sourceWip.getProducedQuantity());
        sourceWip.setConsumedQuantity(consumed);
        sourceWip.setAvailableQuantity(produced.subtract(consumed));
        if (sourceWip.getAvailableQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            sourceWip.setStatus(SemiFinishedInventory.Status.DEPLETED);
        }
        wipRepo.save(sourceWip);
        recordWipLineageEdge(report.getFactoryId(), sourceWip, task, input, operatorId);
    }

    private void validateUnit(SemiFinishedInventory sourceWip, String inputUnit) {
        String wipUnit = sourceWip.getUnit();
        if (wipUnit != null && !wipUnit.isBlank()
                && inputUnit != null && !inputUnit.isBlank()
                && !wipUnit.equals(inputUnit)) {
            throw new BusinessException(409, "半成品单位与本道投入单位不一致")
                    .withCode("WIP_UNIT_MISMATCH")
                    .withHint(String.format("WIP 单位为 %s, 本道投入单位为 %s, 跨单位领用需先配置换算系数",
                            wipUnit, inputUnit))
                    .withSeverity("BLOCKING")
                    .withHintTarget("inputUnit");
        }
    }

    private BigDecimal pendingReserved(String factoryId, String sourceWipNo, Long excludeReportId) {
        if (factoryId == null || factoryId.isBlank()) {
            return BigDecimal.ZERO;
        }
        BigDecimal pending = reportRepo.sumPendingInputBySourceWipNo(factoryId, sourceWipNo, excludeReportId);
        return pending == null ? BigDecimal.ZERO : pending;
    }

    private void validateAvailable(SemiFinishedInventory sourceWip, BigDecimal inputQuantity, BigDecimal pendingReserved) {
        if (inputQuantity == null) {
            return;
        }
        BigDecimal avail = nz(sourceWip.getAvailableQuantity());
        BigDecimal reserved = nz(pendingReserved);
        BigDecimal claimable = avail.subtract(reserved).max(BigDecimal.ZERO);
        if (inputQuantity.compareTo(claimable) > 0) {
            String u = sourceWip.getUnit() == null ? "" : sourceWip.getUnit();
            if (reserved.compareTo(BigDecimal.ZERO) > 0) {
                throw new BusinessException(409, "半成品可领余额不足（含待审批占用）")
                        .withCode("WIP_RESERVED_INSUFFICIENT")
                        .withHint(String.format("库存剩余 %s %s，待审批已占用 %s %s，本次申请 %s %s，最多还能申请 %s %s。请减少投入量，或先审批/驳回前面的报工。",
                                avail.stripTrailingZeros().toPlainString(), u,
                                reserved.stripTrailingZeros().toPlainString(), u,
                                inputQuantity.stripTrailingZeros().toPlainString(), u,
                                claimable.stripTrailingZeros().toPlainString(), u))
                        .withSeverity("BLOCKING")
                        .withHintTarget("inputQuantity");
            }
            throw new BusinessException(409, "领用量超过半成品余额")
                    .withCode("WIP_INSUFFICIENT")
                    .withHint(String.format("WIP 余额仅 %s %s, 不能领 %s %s",
                            avail.stripTrailingZeros().toPlainString(), u,
                            inputQuantity.stripTrailingZeros().toPlainString(), u))
                    .withSeverity("BLOCKING")
                    .withHintTarget("inputQuantity");
        }
    }

    private void recordWipLineageEdge(String factoryId, SemiFinishedInventory sourceWip,
                                      WorkProcessTask task, BigDecimal qty, Long operatorId) {
        try {
            BatchLineageEdge edge = new BatchLineageEdge();
            edge.setFactoryId(factoryId);
            edge.setEdgeType("WIP_CONSUME");
            edge.setSourceType("PRODUCTION_BATCH");
            edge.setSourceId(String.valueOf(sourceWip.getBatchId() == null
                    ? task.getProductionBatchId() : sourceWip.getBatchId()));
            edge.setTargetType("PRODUCTION_BATCH");
            edge.setTargetId(String.valueOf(task.getProductionBatchId()));
            edge.setQuantityUsed(qty);
            edge.setUnit(sourceWip.getUnit());
            edge.setEventTime(LocalDateTime.now());
            edge.setOperatorId(operatorId);
            Map<String, Object> meta = new HashMap<>();
            meta.put("sourceWipNo", sourceWip.getIntermediateBatchNo());
            meta.put("targetWorkProcessTaskId", task.getId());
            meta.put("targetProcessOrder", task.getProcessOrder());
            edge.setMeta(meta);
            lineageEdgeRepo.save(edge);
        } catch (Exception e) {
            log.warn("[lineage] WIP 领用边写入失败 (fail-soft, 不阻塞报工): sourceWipNo={} batchId={} qty={}",
                    sourceWip.getIntermediateBatchNo(), task.getProductionBatchId(), qty, e);
        }
    }

    private String generateBatchNo(WorkProcessTask task) {
        return String.format("%s-B%d-S%d-%d",
                task.getProductTypeId() == null ? "NA" : task.getProductTypeId(),
                task.getProductionBatchId(),
                task.getProcessOrder() == null ? 0 : task.getProcessOrder(),
                task.getId());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal nullSafeAdd(BigDecimal base, BigDecimal... values) {
        BigDecimal out = base;
        for (BigDecimal value : values) {
            if (value == null) {
                continue;
            }
            out = out == null ? value : out.add(value);
        }
        return out;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    // ========== SP2 新增方法 ====================

    /**
     * SP2 二次加工 — 悲观锁扣减 WIP 余量（二次加工计划创建时调用）。
     * 调用方必须持有 @Transactional。
     */
    @Override
    public void deductForSecondaryPlan(Long wipId, BigDecimal qty, String factoryId, Long operatorId) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "扣减数量必须大于 0");
        }
        // 悲观写锁防并发超扣
        SemiFinishedInventory wip = wipRepo.findByIdForUpdate(wipId)
                .orElseThrow(() -> new BusinessException(404, "半成品库存行不存在: id=" + wipId));
        if (!factoryId.equals(wip.getFactoryId())) {
            throw new BusinessException(403, "工厂 ID 不匹配，无权操作该 WIP");
        }
        BigDecimal avail = nz(wip.getAvailableQuantity());
        if (qty.compareTo(avail) > 0) {
            throw new BusinessException(409, String.format(
                    "WIP 余量不足: 请求 %.2f, 可用 %.2f (批次号: %s)",
                    qty, avail, wip.getIntermediateBatchNo()))
                    .withCode("WIP_INSUFFICIENT")
                    .withHint("请减少领用数量或选择其他 WIP 批次");
        }
        BigDecimal newAvail = avail.subtract(qty);
        wip.setAvailableQuantity(newAvail);
        wip.setConsumedQuantity(nz(wip.getConsumedQuantity()).add(qty));
        if (newAvail.compareTo(BigDecimal.ZERO) == 0) {
            wip.setStatus(SemiFinishedInventory.Status.DEPLETED);
        }
        wipRepo.save(wip);

        // 写 OUT/SECONDARY_CONSUME 流水
        SemiFinishedInventoryTransaction outTxn = SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(wip.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                .sourceRef("secondary-plan-deduct:" + wipId)
                .quantity(qty.negate())
                .unitCostAtTxn(wip.getUnitCost())
                .balanceAfter(newAvail)
                .balanceCostAfter(wip.getUnitCost())
                .operatorId(operatorId)
                .build();
        txnRepo.save(outTxn);
        log.info("[SP2] deductForSecondaryPlan wipId={} qty={} newAvail={} factoryId={}",
                wipId, qty, newAvail, factoryId);
    }

    /**
     * SP2 二次加工 — 列出工厂所有可用 WIP。
     */
    @Override
    public List<SemiFinishedInventory> listAvailableWip(String factoryId) {
        return wipRepo.findAvailableByFactory(factoryId);
    }

    // ========== SP1 T4 — Output options endpoint ==========

    @Override
    public OutputOptionsResponse getOutputOptions(String factoryId, Long batchId) {
        List<WorkProcessTask> tasks =
                taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, batchId);

        List<OutputOptionsResponse.OutputOptionItem> items = new ArrayList<>();
        for (WorkProcessTask task : tasks) {
            if (task.getWorkProcessId() == null) {
                continue;
            }
            workProcessRepo.findByFactoryIdAndId(factoryId, task.getWorkProcessId())
                    .filter(wp -> wp.getSemiFinishedOutputCode() != null
                            && !wp.getSemiFinishedOutputCode().isBlank())
                    .ifPresent(wp -> items.add(
                            OutputOptionsResponse.OutputOptionItem.builder()
                                    .taskId(task.getId())
                                    .processName(wp.getProcessName())
                                    .semiCode(wp.getSemiFinishedOutputCode())
                                    .processOrder(task.getProcessOrder())
                                    .build()
                    ));
        }

        log.debug("[SP1-T4] getOutputOptions factoryId={} batchId={} → {} items", factoryId, batchId, items.size());
        return OutputOptionsResponse.builder().items(items).build();
    }
}
