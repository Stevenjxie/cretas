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
import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.cretas.aims.service.wip.ProductFamilyResolver;
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
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ProductTypeRepository productTypeRepo;
    /** 产品族自动识别 (以原料为主) — SFI 防呆过滤的"同族"信号来源, 就地识别零手填. */
    private final ProductFamilyResolver productFamilyResolver;
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
            BigDecimal sourceWipQuantity = sourceWipQuantity(report);
            SemiFinishedInventory sourceWip = validateSourceWip(
                    factoryId, report.getSourceWipNo(), sourceWipQuantity, report.getInputUnit(), report.getId());
            consumeSourceWip(sourceWip, sourceWipQuantity, report, task, operatorId);
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
                CostRollup rollup = outputRollupWithBatchInputMaterial(factoryId, task);
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

        // Fool-Proof Rule 4 — idempotency guard: 以 report_id 为幂等键 (同一份报工不重复入账)。
        // ⚠️ BUG-GOLD-RERUN-WEIGHTED-AVG-SKIP: 旧实现用 (factoryId, semiCode, IN), 但 IN txn 的
        //   sourceRef 也是 semiCode → 同一半成品码的合法第二批生产被误判"已存在"跳过, 移动均价不累加。
        //   改用 report_id (每份报工唯一) → 第二批(不同 report)正常入账累加, 重复提交同一 report 仍幂等。
        boolean alreadyPosted = txnRepo.findByFactoryIdAndReportId(factoryId, report.getId()).stream()
                .anyMatch(t -> SemiFinishedInventoryTransaction.TxnType.IN.equals(t.getTxnType()));
        if (alreadyPosted) {
            log.info("[SP1-semi] idempotent skip for report {} semiCode={}: IN txn already exists for this report", report.getId(), semiCode);
            return;
        }

        String outputUnit = firstNonBlank(report.getSemiOutputUnit(),
                firstNonBlank(report.getOutputUnit(), task.getPlannedUnit()));

        // Compute in-unit cost from this report's cost roll-up
        BigDecimal rollLabor = report.getLaborCost();
        BigDecimal rollMaterial = report.getMaterialCost();
        if ("OUTPUT".equals(report.getReportKind())) {
            CostRollup rollup = outputRollupWithBatchInputMaterial(factoryId, task);
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
            ensureRowExists(factoryId, semiCode, task, outputUnit);
            sfi = wipRepo
                    .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, semiCode)
                    .orElseThrow(() -> new IllegalStateException(
                            "[SP1-semi] row missing after ensureRowExists, semiCode=" + semiCode));
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
        BigDecimal oldUnitCost = sfi.getUnitCost();        // 累加前旧单价 (可能 null), 不在此处强制 ZERO
        BigDecimal newProduced = oldQty.add(inQty);
        sfi.setProducedQuantity(newProduced);
        BigDecimal consumed = nz(sfi.getConsumedQuantity());
        // 盘点修正量 (default 0) 纳入余额 (禁止盘点修正被产出重算覆盖; produced/成本不动)
        sfi.setAvailableQuantity(newProduced.subtract(consumed).add(nz(sfi.getAdjustmentQuantity())));
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
        // R4 (🔴红线) 诚实 null: 移动均价缺成本不得当 ¥0 摊薄。
        //   只有"两侧成本对各自正数量都已知"才算混合单价; 任一侧"有量但成本未知" → 整体 newUnitCost 诚实 null,
        //   不退化成假摊薄值 (历史 bug: 老 100kg@¥10 + 新 50kg@null → 假 6.6667; 反向 → 假 5.3333)。
        //
        //   oldKnown / inKnown 判据: 该侧 qty 为 0 (无成本贡献, 视为已知) 或其 unitCost 非 null。
        //   - 占位行 first-IN (oldQty=0, oldUnitCost=null) → oldKnown=true → newUnitCost=inUnitCost (字节一致)。
        //   - 两侧都已知 → 正常加权 (oldQty=0 时 oldUnitCost coerce ZERO 不影响, 0×0=0)。
        boolean oldKnown = oldQty.signum() == 0 || oldUnitCost != null;
        boolean inKnown = inQty.signum() == 0 || inUnitCost != null;
        boolean anyPositiveQtyWithCost =
                (oldQty.signum() > 0 && oldUnitCost != null) || (inQty.signum() > 0 && inUnitCost != null);
        BigDecimal newUnitCost = null;
        if (newProduced.signum() > 0 && oldKnown && inKnown && anyPositiveQtyWithCost) {
            BigDecimal oldComponent = oldQty.multiply(oldUnitCost == null ? BigDecimal.ZERO : oldUnitCost);
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
     *
     * <p>F1 修复 (2026-06-14): {@code intermediateBatchNo} 参数化 — 同一 ensure-row-then-lock 同时服务
     * SEMI ledger ({@code semiCode}) 与 FINISHED 产出 ({@code wipNo}) 两条路径, 占位行键由调用方传入,
     * 保持与后续 {@code findForUpdate} 的键一致。
     */
    private void ensureRowExists(String factoryId, String intermediateBatchNo, WorkProcessTask task, String outputUnit) {
        SemiFinishedInventory placeholder = SemiFinishedInventory.builder()
                .factoryId(factoryId)
                .batchId(task.getProductionBatchId())
                .intermediateBatchNo(intermediateBatchNo)
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
            log.info("[wip] new-row race for intermediateBatchNo={}: lost insert race (constraint hit), "
                    + "row already created by concurrent tx", intermediateBatchNo);
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

    // ==================== G3 小结 (interim-settle) — task-free SFI in/out ====================

    /**
     * G3 小结半成品入库 (SFI IN) — 见接口 Javadoc。
     *
     * <p>ensure-row-then-lock (复用 W8 BUG-SP1-NEW-ROW 并发安全范式): {@code findForUpdate} 拿悲观写锁;
     * 不存在 → REQUIRES_NEW 子事务建 0 量占位行 (撞 unique 约束 catch 幂等) → 重 {@code findForUpdate} 拿锁;
     * 单一 {@link #applyMovingAverageIn} 累加路径 (诚实 null 成本)。
     */
    @Override
    @Transactional
    public void postClerkOutput(String factoryId, String intermediateBatchNo, String productTypeId,
                                BigDecimal inQty, String unit, BigDecimal inUnitCost,
                                List<Map<String, Object>> materialBatchRefs) {
        if (inQty == null || inQty.signum() <= 0) {
            return;
        }
        if (wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo)
                .isEmpty()) {
            SemiFinishedInventory placeholder = SemiFinishedInventory.builder()
                    .factoryId(factoryId)
                    .intermediateBatchNo(intermediateBatchNo)
                    .productTypeId(productTypeId)
                    .unit(unit)
                    .producedQuantity(BigDecimal.ZERO)
                    .consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(BigDecimal.ZERO)
                    .accumulatedCost(null)
                    .unitCost(null)
                    .status(SemiFinishedInventory.Status.AVAILABLE)
                    .build();
            try {
                if (self != null) {
                    self.commitEmptySemiRow(placeholder);
                } else {
                    commitEmptySemiRow(placeholder);
                }
            } catch (DataIntegrityViolationException dup) {
                // 并发竞争输掉 insert race → 对方已建好同行, 目标 (行存在) 已达成 → 安全继续
                log.info("[wip] postClerkOutput new-row race for intermediateBatchNo={}: row already created by concurrent tx",
                        intermediateBatchNo);
            }
        }
        SemiFinishedInventory sfi = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo)
                .orElseThrow(() -> new IllegalStateException("SFI row missing after create: " + intermediateBatchNo));
        BigDecimal totalCost = inUnitCost == null ? null : inUnitCost.multiply(inQty);
        applyMovingAverageIn(sfi, inQty, inUnitCost, totalCost, unit, materialBatchRefs);
        wipRepo.save(sfi);
        log.info("[wip] postClerkOutput SFI IN: factory={}, batchNo={}, inQty={}, newProduced={}, available={}",
                factoryId, intermediateBatchNo, inQty, sfi.getProducedQuantity(), sfi.getAvailableQuantity());
    }

    /**
     * G3 小结半成品出库 (SFI OUT) — 见接口 Javadoc。
     *
     * <p>{@code findForUpdate} 悲观行锁 → {@code consumedQuantity += qty} →
     * {@code availableQuantity = produced - consumed}。守卫 not-below-zero (行缺失 no-op / 超扣 clamp)。
     */
    @Override
    @Transactional
    public void consumeClerkSemi(String factoryId, String intermediateBatchNo, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            return;
        }
        java.util.Optional<SemiFinishedInventory> opt = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo);
        if (opt.isEmpty()) {
            // 守卫: 无 SFI 行 = 没有库存可扣 (该半成品未曾入库) → no-op, 不建负行不报错。
            log.warn("[wip] consumeClerkSemi: no SFI row for batchNo={} (factory={}) — skip (nothing stocked)",
                    intermediateBatchNo, factoryId);
            return;
        }
        SemiFinishedInventory sfi = opt.get();
        BigDecimal produced = nz(sfi.getProducedQuantity());
        BigDecimal newConsumed = nz(sfi.getConsumedQuantity()).add(qty);
        if (newConsumed.compareTo(produced) > 0) {
            // 守卫 not-below-zero: 超扣 clamp 到 produced (available 不为负)。诚实告警, 不静默吞。
            log.warn("[wip] consumeClerkSemi over-draw: batchNo={}, attemptConsumed={} > produced={} — clamping",
                    intermediateBatchNo, newConsumed, produced);
            newConsumed = produced;
        }
        sfi.setConsumedQuantity(newConsumed);
        sfi.setAvailableQuantity(produced.subtract(newConsumed).add(nz(sfi.getAdjustmentQuantity())));
        if (sfi.getAvailableQuantity().compareTo(BigDecimal.ZERO) <= 0
                && !SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
            sfi.setStatus(SemiFinishedInventory.Status.DEPLETED);
        }
        wipRepo.save(sfi);
        log.info("[wip] consumeClerkSemi SFI OUT: factory={}, batchNo={}, qty={}, consumed={}, available={}",
                factoryId, intermediateBatchNo, qty, sfi.getConsumedQuantity(), sfi.getAvailableQuantity());
    }

    /**
     * G3 小结半成品出库 (SFI OUT) — 严格版 (SFI 投料, 禁止降级)。见接口 Javadoc。
     *
     * <p>{@code findForUpdate} 悲观行锁 → 校验存在 + 足量 → 扣减。缺失/不足 <b>抛</b> (不 no-op/clamp)。
     */
    @Override
    @Transactional
    public BigDecimal consumeClerkSemiStrict(String factoryId, String intermediateBatchNo, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        SemiFinishedInventory sfi = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo)
                .orElseThrow(() -> new BusinessException(409, "半成品库存不存在: " + intermediateBatchNo)
                        .withCode("SFI_NOT_FOUND")
                        .withHint("请重新选择仍有库存的半成品批次")
                        .withSeverity("BLOCKING")
                        .withHintTarget(intermediateBatchNo));
        BigDecimal produced = nz(sfi.getProducedQuantity());
        BigDecimal consumed = nz(sfi.getConsumedQuantity());
        BigDecimal available = produced.subtract(consumed);
        if (qty.compareTo(available) > 0) {
            // 禁止降级: 不足即抛 (不 clamp), 防 phantom/不足库存生产成品。
            throw new BusinessException(409, "半成品库存不足: " + intermediateBatchNo
                    + " 余" + available.stripTrailingZeros().toPlainString()
                    + " 需" + qty.stripTrailingZeros().toPlainString())
                    .withCode("SFI_INSUFFICIENT")
                    .withHint("请减少投料量或选择其他半成品批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget(intermediateBatchNo);
        }
        BigDecimal newConsumed = consumed.add(qty);
        sfi.setConsumedQuantity(newConsumed);
        sfi.setAvailableQuantity(produced.subtract(newConsumed).add(nz(sfi.getAdjustmentQuantity())));
        if (sfi.getAvailableQuantity().compareTo(BigDecimal.ZERO) <= 0
                && !SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
            sfi.setStatus(SemiFinishedInventory.Status.DEPLETED);
        }
        wipRepo.save(sfi);
        log.info("[wip] consumeClerkSemiStrict SFI OUT: factory={}, batchNo={}, qty={}, consumed={}, available={}",
                factoryId, intermediateBatchNo, qty, sfi.getConsumedQuantity(), sfi.getAvailableQuantity());
        return qty;
    }

    /**
     * G3 成本传导 — 读常驻半成品库存(SFI)的移动均价 unitCost (见接口 Javadoc)。
     *
     * <p>只读非锁 finder ({@code findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull}): 与小结 OUT
     * 同事务读值稳定 (OUT 不改 unitCost)。🔴 诚实 null: 行缺失 / unitCost 未知 → null (不伪造 ¥0)。
     */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal getSemiUnitCost(String factoryId, String intermediateBatchNo) {
        if (intermediateBatchNo == null || intermediateBatchNo.isBlank()) {
            return null;
        }
        return wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo)
                .map(SemiFinishedInventory::getUnitCost)
                .orElse(null);
    }

    /**
     * 撤销小结 — {@link #postClerkOutput} 的逆 (SFI IN un-stock)。见接口 Javadoc。
     *
     * <p>{@code findForUpdate} 悲观行锁 → 下游已消耗守卫 (available_after&lt;0 抛) → produced/accumulatedCost 冲销 +
     * unitCost 重算 → 写 REVERSE/REVERSAL 流水。
     */
    @Override
    @Transactional
    public void reverseClerkOutput(String factoryId, String intermediateBatchNo, BigDecimal qty,
                                   BigDecimal totalCost, Long operatorId) {
        if (qty == null || qty.signum() <= 0) {
            return;
        }
        SemiFinishedInventory sfi = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo)
                .orElseThrow(() -> new BusinessException(409, "半成品库存行不存在, 无法撤销入库: " + intermediateBatchNo)
                        .withCode("SFI_NOT_FOUND")
                        .withHint("该半成品运行余额行已不存在, 可能已被其它操作清除")
                        .withSeverity("BLOCKING")
                        .withHintTarget(intermediateBatchNo));
        BigDecimal produced = nz(sfi.getProducedQuantity());
        BigDecimal consumed = nz(sfi.getConsumedQuantity());
        BigDecimal adj = nz(sfi.getAdjustmentQuantity());
        BigDecimal newProduced = produced.subtract(qty);
        BigDecimal newAvailable = newProduced.subtract(consumed).add(adj);
        if (newAvailable.signum() < 0) {
            // 🔴 下游已消耗守卫 (禁止降级): 冲销会致负库存 → loud-fail, 不产 phantom。
            BigDecimal reversible = produced.subtract(consumed).max(BigDecimal.ZERO);
            throw new BusinessException(409, "半成品 " + intermediateBatchNo + " 已被下游消耗 "
                    + consumed.stripTrailingZeros().toPlainString() + ", 可撤销入库仅 "
                    + reversible.stripTrailingZeros().toPlainString()
                    + " (需撤销 " + qty.stripTrailingZeros().toPlainString() + "), 无法撤销小结; 请先撤销下游消耗")
                    .withCode("SFI_DOWNSTREAM_CONSUMED")
                    .withHint("该批次半成品已被后续工序/计划领用, 请先撤销下游相关小结再重试")
                    .withSeverity("BLOCKING")
                    .withHintTarget(intermediateBatchNo);
        }
        sfi.setProducedQuantity(newProduced);
        // 成本冲销 (诚实): totalCost null → accumulatedCost 未曾加此段, 不减; 否则减去确切金额。
        BigDecimal acc = sfi.getAccumulatedCost();
        if (acc != null && totalCost != null) {
            acc = acc.subtract(totalCost);
            sfi.setAccumulatedCost(acc);
        }
        // 重算 unitCost = accumulatedCost / producedQuantity (produced≤0 或 accumulatedCost null → null, 诚实)。
        sfi.setUnitCost((newProduced.signum() > 0 && acc != null)
                ? acc.divide(newProduced, 4, RoundingMode.HALF_UP) : null);
        sfi.setAvailableQuantity(newAvailable);
        if (newAvailable.signum() <= 0) {
            if (!SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
                sfi.setStatus(SemiFinishedInventory.Status.DEPLETED);
            }
        } else if (!SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
            sfi.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        }
        wipRepo.save(sfi);

        SemiFinishedInventoryTransaction revTxn = SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef("interim-settle-reverse-in:" + intermediateBatchNo)
                .quantity(qty.negate())                 // IN 冲销 = 负量
                .unitCostAtTxn(sfi.getUnitCost())
                .balanceAfter(newAvailable)
                .balanceCostAfter(sfi.getUnitCost())
                .operatorId(operatorId)
                .build();
        txnRepo.save(revTxn);
        log.info("[interim-reverse] reverseClerkOutput SFI IN 冲销: factory={}, batchNo={}, qty={}, "
                        + "newProduced={}, newAvailable={}, unitCost={}",
                factoryId, intermediateBatchNo, qty, newProduced, newAvailable, sfi.getUnitCost());
    }

    /**
     * 撤销小结 — {@link #consumeClerkSemi} / {@link #consumeClerkSemiStrict} 的逆 (SFI OUT restore)。见接口 Javadoc。
     *
     * <p>{@code findForUpdate} 悲观行锁 → consumed 反冲 (缺失容忍 / 反冲不为负守卫) → 写 REVERSE/REVERSAL 流水。
     */
    @Override
    @Transactional
    public void restoreClerkSemi(String factoryId, String intermediateBatchNo, BigDecimal qty, Long operatorId) {
        if (qty == null || qty.signum() <= 0) {
            return;
        }
        java.util.Optional<SemiFinishedInventory> opt = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo);
        if (opt.isEmpty()) {
            // 还量容忍: 源库存行已不存在 → 无处可还, 跳过 (不产负行, 不阻塞撤销)。诚实告警。
            log.warn("[interim-reverse] restoreClerkSemi: no SFI row for batchNo={} (factory={}) — skip (nothing to restore)",
                    intermediateBatchNo, factoryId);
            return;
        }
        SemiFinishedInventory sfi = opt.get();
        BigDecimal produced = nz(sfi.getProducedQuantity());
        BigDecimal newConsumed = nz(sfi.getConsumedQuantity()).subtract(qty);
        if (newConsumed.signum() < 0) {
            // 诚实: 还量超过历史消耗量 → 报错而非静默置 0。
            throw new BusinessException(409, "撤销出库量超过已消耗量: " + intermediateBatchNo
                    + " 请求还 " + qty.stripTrailingZeros().toPlainString()
                    + " 已消耗 " + nz(sfi.getConsumedQuantity()).stripTrailingZeros().toPlainString())
                    .withCode("SFI_REVERSE_OVER_CONSUMED")
                    .withSeverity("BLOCKING")
                    .withHintTarget(intermediateBatchNo);
        }
        sfi.setConsumedQuantity(newConsumed);
        sfi.setAvailableQuantity(produced.subtract(newConsumed).add(nz(sfi.getAdjustmentQuantity())));
        if (sfi.getAvailableQuantity().compareTo(BigDecimal.ZERO) > 0
                && !SemiFinishedInventory.Status.RETURNED.equals(sfi.getStatus())) {
            sfi.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        }
        wipRepo.save(sfi);

        SemiFinishedInventoryTransaction revTxn = SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef("interim-settle-reverse-out:" + intermediateBatchNo)
                .quantity(qty)                          // OUT 冲销 = 正量 (还回)
                .unitCostAtTxn(sfi.getUnitCost())
                .balanceAfter(sfi.getAvailableQuantity())
                .balanceCostAfter(sfi.getUnitCost())
                .operatorId(operatorId)
                .build();
        txnRepo.save(revTxn);
        log.info("[interim-reverse] restoreClerkSemi SFI OUT 还回: factory={}, batchNo={}, qty={}, consumed={}, available={}",
                factoryId, intermediateBatchNo, qty, newConsumed, sfi.getAvailableQuantity());
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

    /**
     * F1 修复 (2026-06-14) — FINISHED/legacy 产出 WIP upsert, ensure-row-then-lock 并发安全。
     *
     * <p><b>历史 bug</b>: 此前用 {@code findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(...).orElse(null)}
     * 无悲观锁。{@code wipNo = generateBatchNo(task)} 是 task 级稳定键 (同 task 跨天/多次报工命中同行)。
     * 并发审批同一 wipNo → 两事务各读旧行 → save 时 {@code @Version} 乐观锁冲突 → 审批事务回滚 →
     * WIP 入账静默丢失 (#713 只修了半成品 SEMI 路径, FINISHED 路径漏修)。
     *
     * <p><b>修复</b>: 复用 SEMI 路径同款 ensure-row-then-lock (W8 BUG-SP1-NEW-ROW):
     * <ol>
     *   <li>{@code findForUpdate(wipNo)} 拿悲观写锁; 不存在 → {@link #ensureRowExists} 在 REQUIRES_NEW
     *       子事务建 0 量占位行 (撞 unique 约束 catch 幂等) → 重 {@code findForUpdate} 拿锁。</li>
     *   <li>单一累加路径 (existing-row 直接拿锁 / 新建占位行重拿锁), 累加在悲观锁保护下串行化, 无乐观锁冲突。</li>
     * </ol>
     *
     * <p>成本语义不变 (FINISHED 用 {@code accumulatedCost / producedQuantity}, 非 SEMI 的 moving-average
     * inUnitCost 加权): 0 量占位行 accumulatedCost=null → 首次累加后 = 本次 rollup, unitCost = accumulated/produced,
     * 与历史 new-row / existing-row 行为字节一致。诚实 null: accumulatedCost 为 null (无工价无料价) → unitCost null。
     */
    private SemiFinishedInventory upsertProducedWip(String factoryId, ProductionReport report, WorkProcessTask task,
                                   BigDecimal rollLaborCost, BigDecimal rollMaterialCost) {
        String wipNo = generateBatchNo(task);
        BigDecimal out = nz(report.getOutputQuantity());
        String outputUnit = firstNonBlank(report.getOutputUnit(), task.getPlannedUnit());

        // ensure-row-then-lock: 拿悲观写锁, 不存在则先建 0 量占位行 (REQUIRES_NEW 子事务) 再重拿锁。
        SemiFinishedInventory wip = wipRepo
                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, wipNo)
                .orElse(null);
        if (wip == null) {
            ensureRowExists(factoryId, wipNo, task, outputUnit);
            wip = wipRepo
                    .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, wipNo)
                    .orElseThrow(() -> new IllegalStateException(
                            "[wip] row missing after ensureRowExists, wipNo=" + wipNo));
        }

        // 修2: 累加前快照历史产出量 + 单价 (用于诚实 null 毒化判定, 必须在 setProducedQuantity 前取)。
        BigDecimal wipPriorProduced = wip.getProducedQuantity();
        BigDecimal wipPriorUnitCost = wip.getUnitCost();

        // 统一累加路径 (占位行 producedQuantity=0 → 累加结果与历史全量新行字节一致; 既有行直接累加)。
        BigDecimal produced = nz(wip.getProducedQuantity()).add(out);
        BigDecimal consumed = nz(wip.getConsumedQuantity());
        wip.setProducedQuantity(produced);
        wip.setAvailableQuantity(produced.subtract(consumed).add(nz(wip.getAdjustmentQuantity())));
        if (wip.getAvailableQuantity().compareTo(BigDecimal.ZERO) > 0
                && !SemiFinishedInventory.Status.RETURNED.equals(wip.getStatus())) {
            wip.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        }
        if (wip.getUnit() == null) {
            wip.setUnit(outputUnit);
        }
        if (wip.getMaterialBatchRefs() == null && report.getMaterialBatchRefs() != null) {
            wip.setMaterialBatchRefs(report.getMaterialBatchRefs());
        }

        // 修2 (🔴红线) 诚实 null: FINISHED 路径与半成品 applyMovingAverageIn 对齐 —
        //   "有正产出量但本次/历史成本未知" 不得当 ¥0 摊薄稀释 unitCost。
        //   历史 bug: 老 100kg@accumulated=1000 + 新 50kg@(rollLabor=null,rollMaterial=null) →
        //            accumulatedCost 仍 1000, produced=150 → unitCost=1000/150=6.67 (把未知 50kg 当 ¥0)。
        //
        //   判据 (任一为真 → 整体 unitCost 诚实 null):
        //   - 本次贡献: out>0 且本次 rollup 成本完全未知 (rollLabor 与 rollMaterial 都 null);
        //   - 历史已被毒化: 既有行 producedQuantity>0 但 unitCost 为 null (此前某次"有量无成本"已置 null) —
        //     既然历史段成本未知, 累加后仍无法诚实摊。
        //   仅当两者皆"成本已知/不毒化"时才计算 accumulatedCost/produced 加权 (与历史字节一致)。
        BigDecimal priorProduced = nz(wipPriorProduced);
        boolean priorPoisoned = priorProduced.signum() > 0 && wipPriorUnitCost == null;
        boolean inCostUnknown = out.signum() > 0 && rollLaborCost == null && rollMaterialCost == null;

        wip.setAccumulatedCost(nullSafeAdd(wip.getAccumulatedCost(), rollLaborCost, rollMaterialCost));
        if (!priorPoisoned && !inCostUnknown
                && wip.getAccumulatedCost() != null && produced.signum() > 0) {
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

    /**
     * P0 修 (2026-06-12, Codex gold 深测): 两点报工 OUTPUT task 成本滚动 = 本 task per-task rollup
     * + 同批次 {@code __MATERIAL_INPUT__} 哨兵 task 的料成本.
     *
     * <p>根因: 两点模式 (F006) 料成本记在 INPUT task ({@code __MATERIAL_INPUT__}), 最终产出在 OUTPUT task
     * ({@code __FINAL_OUTPUT__}). 旧 {@code calculateTaskCostRollup(outputTaskId)} 只聚合 OUTPUT task 自身
     * report (material 为 null) → 产出 WIP unitCost 永 null → ProductionCostUpdatedEvent 不发 →
     * SalesOrderItem.costUnitPrice 永不回填 → 财审 actualCost null + 撤回自愈/多段链/混合计价全部 deep-close 受阻.
     *
     * <p>逐道模式 (F001): 每道工序 task 各自有料+产出, 无 {@code __MATERIAL_INPUT__} 哨兵 task →
     * {@link #calculateBatchInputMaterialCost} 返 null → 本方法等价于原 per-task rollup. Mode-aware by construction,
     * 不破坏逐道. 诚实 null: 无 INPUT 料且 OUTPUT 自身无料 → material 仍 null (不伪造 0).
     */
    private CostRollup outputRollupWithBatchInputMaterial(String factoryId, WorkProcessTask outputTask) {
        CostRollup own = calculateTaskCostRollup(factoryId, outputTask.getId());
        BigDecimal material = own.materialCost();
        BigDecimal inputMaterial = calculateBatchInputMaterialCost(factoryId, outputTask.getProductionBatchId());
        if (inputMaterial != null) {
            material = (material == null ? BigDecimal.ZERO : material).add(inputMaterial);
        }
        return new CostRollup(own.laborCost(), material);
    }

    /**
     * 同批次所有 INPUT-kind YIELD 报工的料成本合计.
     *
     * <p>覆盖两类 INPUT 料: (a) 两点模式 {@code __MATERIAL_INPUT__} 哨兵 task 的领料 INPUT 报工;
     * (b) 二次加工/多段链"领用上道半成品 + 本段原料"的 INPUT 报工 —— 消耗上游 WIP 的成本
     * (sourceWip.unitCost × inputQty) 在报工时已算入该 INPUT report 的 {@code materialCost}.
     * OUTPUT 报工的 {@code sourceWipNo} 在三阶段落库时被置空, 故必须从 batch 的 INPUT-kind 报工聚合,
     * 不能依赖 OUTPUT report 自身。
     *
     * <p>Mode-aware by construction: 逐道模式用 legacy null-kind 整合报工 (料+产出同 report, reportKind=null,
     * 非 INPUT) → 本方法返 null, OUTPUT rollup 退回 per-task own 成本, 逐道行为不变。纯成品直产无 INPUT 报工 → null。
     * 诚实 null: 无 INPUT 料则返 null (不伪造 0)。
     *
     * <p>修复历史: 2026-06-12 P0 两点桥首版只聚合哨兵 task 的料; Codex 多段 rerun 暴露二次加工的 INPUT 报工落在
     * 普通 task 上 (非哨兵) → 旧逻辑漏聚合 → semiB unitCost 永 null。改为按 {@code reportKind=INPUT} 聚合,
     * 统一覆盖两点 (哨兵 INPUT 报工也是 INPUT-kind, 结果不变) + 二次加工两类, 不破坏逐道。
     */
    private BigDecimal calculateBatchInputMaterialCost(String factoryId, Long productionBatchId) {
        if (productionBatchId == null) {
            return null;
        }
        BigDecimal material = null;
        boolean unknownInputMaterial = false;
        for (ProductionReport r : reportRepo.findYieldReportsByBatch(factoryId, productionBatchId)) {
            if ("INPUT".equals(r.getReportKind())) {
                if (r.getMaterialCost() == null) {
                    unknownInputMaterial = true;
                } else {
                    material = (material == null ? BigDecimal.ZERO : material).add(r.getMaterialCost());
                }
            }
        }
        if (unknownInputMaterial) {
            return null;
        }
        return material;
    }

    private record CostRollup(BigDecimal laborCost, BigDecimal materialCost) {}

    private void consumeSourceWip(SemiFinishedInventory sourceWip, BigDecimal input,
                                  ProductionReport report, WorkProcessTask task, Long operatorId) {
        BigDecimal consumed = nz(sourceWip.getConsumedQuantity()).add(input);
        BigDecimal produced = nz(sourceWip.getProducedQuantity());
        sourceWip.setConsumedQuantity(consumed);
        sourceWip.setAvailableQuantity(produced.subtract(consumed).add(nz(sourceWip.getAdjustmentQuantity())));
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

    private BigDecimal sourceWipQuantity(ProductionReport report) {
        BigDecimal qty = null;
        if (report.getCustomFields() != null) {
            Object value = report.getCustomFields().get("sourceWipQuantity");
            if (value instanceof BigDecimal bd) {
                qty = bd;
            } else if (value instanceof Number number) {
                qty = BigDecimal.valueOf(number.doubleValue());
            } else if (value instanceof String str && !str.isBlank()) {
                qty = new BigDecimal(str);
            }
        }
        if (qty == null) {
            qty = report.getInputQuantity();
        }
        if (qty != null && qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(409, "半成品领用数量必须大于 0")
                    .withCode("WIP_INPUT_REQUIRED")
                    .withHint("请填写实际领用的半成品数量")
                    .withSeverity("BLOCKING")
                    .withHintTarget("sourceWipQuantity");
        }
        if (qty != null && report.getInputQuantity() != null && qty.compareTo(report.getInputQuantity()) > 0) {
            throw new BusinessException(409, "半成品领用数量不能超过本次总投入量")
                    .withCode("WIP_INPUT_EXCEEDS_TOTAL")
                    .withHint("请核对原料领用量和半成品领用量；总投入量必须等于两者合计")
                    .withSeverity("BLOCKING")
                    .withHintTarget("sourceWipQuantity");
        }
        return qty;
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
     * 修1 (🔴) 二次加工反冲 — {@link #deductForSecondaryPlan} 的逆: 还回开工扣的半成品余量。
     *
     * <p>调用方必须持有 @Transactional。findByIdForUpdate 悲观写锁防并发 (与 deduct 对称)。
     */
    @Override
    public void reverseSecondaryDeduct(Long wipId, BigDecimal qty, String factoryId, Long operatorId) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "反冲数量必须大于 0");
        }
        // 悲观写锁 (与 deductForSecondaryPlan 对称)
        SemiFinishedInventory wip = wipRepo.findByIdForUpdate(wipId)
                .orElseThrow(() -> new BusinessException(404, "半成品库存行不存在: id=" + wipId));
        if (!factoryId.equals(wip.getFactoryId())) {
            throw new BusinessException(403, "工厂 ID 不匹配，无权操作该 WIP");
        }
        BigDecimal newAvail = nz(wip.getAvailableQuantity()).add(qty);
        BigDecimal newConsumed = nz(wip.getConsumedQuantity()).subtract(qty);
        // 防御: consumed 不应反冲到负 (反冲量超过历史扣减量)。诚实报错而非静默置 0。
        if (newConsumed.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(409, String.format(
                    "反冲数量超过已消耗量: 请求 %s, 已消耗 %s (批次号: %s)",
                    qty, nz(wip.getConsumedQuantity()), wip.getIntermediateBatchNo()))
                    .withCode("WIP_REVERSE_OVER_CONSUMED");
        }
        wip.setAvailableQuantity(newAvail);
        wip.setConsumedQuantity(newConsumed);
        // 还量后有余量 → 从 DEPLETED 恢复 AVAILABLE (RETURNED 终态不动)。
        if (newAvail.compareTo(BigDecimal.ZERO) > 0
                && !SemiFinishedInventory.Status.RETURNED.equals(wip.getStatus())) {
            wip.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        }
        wipRepo.save(wip);

        // 写 REVERSE/REVERSAL 反向流水 (数量为正 = 还回); 不修改原 OUT 行 (流水不可变)。
        SemiFinishedInventoryTransaction revTxn = SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(wip.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef("secondary-plan-reverse:" + wipId)
                .quantity(qty)
                .unitCostAtTxn(wip.getUnitCost())
                .balanceAfter(newAvail)
                .balanceCostAfter(wip.getUnitCost())
                .operatorId(operatorId)
                .build();
        txnRepo.save(revTxn);
        log.info("[修1] reverseSecondaryDeduct wipId={} qty={} newAvail={} newConsumed={} factoryId={}",
                wipId, qty, newAvail, newConsumed, factoryId);
    }

    /**
     * SP2 二次加工 — 列出工厂所有可用 WIP。
     */
    @Override
    public List<SemiFinishedInventory> listAvailableWip(String factoryId) {
        return wipRepo.findAvailableByFactory(factoryId);
    }

    // ==================== C3: 工厂级半成品重量库存视图 ====================

    /**
     * C3 — 工厂级半成品重量库存快照 (全状态; 不暴露成本字段)。
     *
     * <p>实现策略:
     * <ol>
     *   <li>从 repo 取全状态 WIP 行 (findByFactoryIdForWeightView)。</li>
     *   <li>批量回填 productTypeName: 先收集所有 productTypeId, 一次 findByIdIn,
     *       构建 id→name Map, 避免 N+1 查询。</li>
     * </ol>
     * 成本字段 (accumulatedCost / unitCost) 故意不映射到 DTO — 不暴露给客户侧。
     */
    @Override
    public List<WipRowDTO> listWipByFactory(String factoryId) {
        return listWipByFactory(factoryId, null, null);
    }

    @Override
    public List<WipRowDTO> listWipByFactory(String factoryId, String productTypeId, Integer maxProcessOrder) {
        List<SemiFinishedInventory> rows = wipRepo.findByFactoryIdForWeightView(factoryId);
        if (rows.isEmpty()) {
            return List.of();
        }

        // 防呆过滤 (可选) — 逐道 SFI 投料下拉收窄可选半成品:
        //   阶段: maxProcessOrder 非空 → 仅更早阶段 (processOrder < max, 防回锅); null processOrder 行严格排除 (不变)。
        //   同族: productTypeId 非空 → 解析为"产品族"(以原料为主自动识别), 仅同族半成品 (猪蹄不显牛肉)。
        //         注意: 参数名仍是 productTypeId (前端传当前计划产品), 内部解析成族键再比较 —— 不是按 productTypeId 精确匹配。
        //         07-01 客户澄清: 熟制前半成品在同产品族内通用 (卤猪蹄/椒麻猪蹄/猪蹄冠 共用"猪蹄"半成品),
        //         按 productTypeId 精确过滤会误藏这个通用半成品; 故必须按族过滤 (见 ProductFamilyResolver)。
        // 两参数皆 null → 全量 (向后兼容)。
        final boolean filterFamily = productTypeId != null && !productTypeId.isBlank();
        final boolean filterStage = maxProcessOrder != null;

        // 1) 阶段过滤 (先做, 无 repo, 且缩小待识别族的产品集)。null processOrder 严格排除 (防回锅, 不变)。
        if (filterStage) {
            rows = rows.stream()
                    .filter(w -> w.getProcessOrder() != null && w.getProcessOrder() < maxProcessOrder)
                    .collect(Collectors.toList());
            if (rows.isEmpty()) {
                return List.of();
            }
        }

        // 2) 同族过滤: 解析当前计划产品 + 所有候选半成品产品 → 族键, 保留同族。
        //    宁缺勿藏 (诚实, 不过度收窄): 仅排除"族已知且与计划族不同"的行 —— 族未知的候选一律放行
        //    (不隐藏可能合法复用的项); 计划族本身识别不出 → 无从比较, 全放行 (只按阶段过滤)。
        if (filterFamily) {
            Set<String> ptForFamily = rows.stream()
                    .map(SemiFinishedInventory::getProductTypeId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toSet());
            ptForFamily.add(productTypeId);
            Map<String, String> familyMap = productFamilyResolver.resolveFamilies(factoryId, ptForFamily);
            String planFamily = familyMap.get(productTypeId);
            if (planFamily != null) {
                rows = rows.stream()
                        .filter(w -> {
                            String f = w.getProductTypeId() == null ? null : familyMap.get(w.getProductTypeId());
                            return f == null || f.equals(planFamily);   // 族未知放行; 族相同保留; 族不同(牛肉)排除
                        })
                        .collect(Collectors.toList());
                if (rows.isEmpty()) {
                    return List.of();
                }
            }
            // planFamily == null → 计划族识别不出, 不按族过滤 (务实: 不因缺族信息清空可选项)。
        }

        // 批量取 productType 名称 — 避免 N+1
        Set<String> ptIds = rows.stream()
                .map(SemiFinishedInventory::getProductTypeId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> ptNameMap = new HashMap<>();
        if (!ptIds.isEmpty()) {
            productTypeRepo.findByIdIn(ptIds).forEach(pt -> ptNameMap.put(pt.getId(), pt.getName()));
        }

        // ② 批次下拉补 生产日期 / 成本: 仅逐道 SFI 投料下拉 (picker context = 带 productTypeId 过滤) 填充,
        //    无参 (看板/盘点) 快照不暴露成本 (C3 "只做重量库存" 口径不变)。
        final boolean pickerContext = filterFamily;
        return rows.stream().map(w -> WipRowDTO.builder()
                .intermediateBatchNo(w.getIntermediateBatchNo())
                .sourceWorkProcessTaskId(w.getSourceWorkProcessTaskId())
                .processOrder(w.getProcessOrder())
                // processName not stored on entity; enrichment via WorkProcess join is N+1 heavy
                // and not needed for factory-level weight view (client groups by productTypeName)
                .productTypeId(w.getProductTypeId())
                .producedQuantity(w.getProducedQuantity())
                .consumedQuantity(w.getConsumedQuantity())
                .availableQuantity(w.getAvailableQuantity())
                .unit(w.getUnit())
                .status(w.getStatus())
                .productTypeName(ptNameMap.get(w.getProductTypeId()))   // null if no match
                .batchId(w.getBatchId())
                // ② picker-only: 生产日期 (createdAt 日期) + 成本 (unitCost, 诚实 null)
                .productionDate(pickerContext && w.getCreatedAt() != null ? w.getCreatedAt().toLocalDate() : null)
                .unitCost(pickerContext ? w.getUnitCost() : null)
                .build()
        ).collect(Collectors.toList());
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
