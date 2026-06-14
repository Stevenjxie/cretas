package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ProductionSettlementConsumption;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R3 + R5 红线修复测试 (2026-06-14, 六扇门撤回库存恢复).
 *
 * <p><b>R3</b>: executeReversal 必须回扣 {@link MaterialBatch#getUsedQuantity()} —
 * 撤回前结单消耗的原料量, 撤回后必须归还 (否则原料库存永久少账).
 * 消耗权威源 = {@link ProductionSettlementConsumption} (settlement 结单路径
 * {@code postMaterialBatchConsumption} 写 usedQuantity 时逐行迭代的就是它).
 *
 * <p><b>R5</b>: replayMovingAverage 回放可用量必须纳入 OUT 流水 —
 * OUT 已被下游领用, availableQuantity 必须 = ΣIN + ΣREVERSE + ΣOUT (OUT 为负),
 * 否则把已发出的量算回可用 → 幻影库存.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("R3/R5: 撤回恢复原料 usedQuantity + 回放纳入 OUT")
class ReportReversalMaterialRestoreTest {

    private static final String FACTORY_ID = "F006";
    private static final Long BATCH_ID = 1924L;
    private static final Long LOG_ID = 42L;
    private static final String PLAN_ID = "PP-001";
    private static final String SETTLEMENT_ID = "ST-001";

    @InjectMocks private ReportReversalServiceImpl service;

    @Mock private ReportReversalLogRepository reversalLogRepo;
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private FinishedGoodsBatchRepository fgbRepo;
    @Mock private com.cretas.aims.repository.UserRepository userRepository;
    @Mock private com.cretas.aims.repository.workprocess.WorkProcessTaskRepository workProcessTaskRepository;
    @Mock private com.cretas.aims.service.NotificationService notificationService;
    // R3 新增 field-inject deps (原料消耗恢复)
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProductionSettlementRepository productionSettlementRepository;
    @Mock private ProductionSettlementConsumptionRepository productionSettlementConsumptionRepository;

    @BeforeEach
    void setUp() {
        // field-inject 的 R3 新 deps (与断点2 同 pattern) → 手动注入
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", productionSettlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository",
                productionSettlementConsumptionRepository);

        // executeReversal 主流程基础 mock
        ReportReversalLog log = ReportReversalLog.builder()
                .factoryId(FACTORY_ID)
                .batchId(BATCH_ID)
                .planId(PLAN_ID)
                .status(ReportReversalLog.ReversalStatus.PENDING)
                .build();
        log.setId(LOG_ID);
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        when(reversalLogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workProcessTaskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
                FACTORY_ID, BATCH_ID)).thenReturn(Collections.emptyList());
        when(fgbRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(Collections.emptyList());
    }

    // ─────────────────────────────────────────────────────────────
    // R3: 撤回回扣原料 usedQuantity
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R3: 撤回回扣原料 usedQuantity 到 0, status 从 USED_UP 恢复 AVAILABLE")
    void executeReversal_restoresMaterialUsedQuantity() {
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());

        // settlement 结单消耗 30kg → batch.usedQuantity = 30 (currentQuantity = 100 - 30 = 70)
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(SETTLEMENT_ID);
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));

        ProductionSettlementConsumption cons = new ProductionSettlementConsumption();
        cons.setId(1L);
        cons.setSettlementId(SETTLEMENT_ID);
        cons.setFactoryId(FACTORY_ID);
        cons.setProductionPlanId(PLAN_ID);
        cons.setSourceType("MATERIAL_BATCH");
        cons.setMaterialBatchId("MB-1");
        cons.setQuantity(new BigDecimal("30.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(cons));

        MaterialBatch batch = new MaterialBatch();
        batch.setId("MB-1");
        batch.setFactoryId(FACTORY_ID);
        batch.setReceiptQuantity(new BigDecimal("100.00"));
        batch.setUsedQuantity(new BigDecimal("30.00"));
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.USED_UP); // 消耗后被置 USED_UP
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("MB-1", FACTORY_ID))
                .thenReturn(Optional.of(batch));

        service.executeReversal(LOG_ID, FACTORY_ID);

        // usedQuantity 回扣 30 → 0; currentQuantity 自动恢复 100 (computed = 100 - 0 - 0)
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(batch.getCurrentQuantity()).isEqualByComparingTo(new BigDecimal("100.00"));
        // usedQuantity 归 0 → status 从 USED_UP 恢复可用
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
        verify(materialBatchRepository, atLeastOnce()).save(batch);
    }

    @Test
    @DisplayName("R3: 部分回扣 — 原料还有其他消耗时 usedQuantity 不归 0, 不恢复状态")
    void executeReversal_partialRestore_keepsRemainingUsed() {
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(SETTLEMENT_ID);
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));

        ProductionSettlementConsumption cons = new ProductionSettlementConsumption();
        cons.setId(1L);
        cons.setSettlementId(SETTLEMENT_ID);
        cons.setSourceType("MATERIAL_BATCH");
        cons.setMaterialBatchId("MB-1");
        cons.setQuantity(new BigDecimal("30.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(cons));

        // 批次总用 50 (本结单 30 + 其他来源 20) → 回扣 30 后剩 20
        MaterialBatch batch = new MaterialBatch();
        batch.setId("MB-1");
        batch.setFactoryId(FACTORY_ID);
        batch.setReceiptQuantity(new BigDecimal("100.00"));
        batch.setUsedQuantity(new BigDecimal("50.00"));
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("MB-1", FACTORY_ID))
                .thenReturn(Optional.of(batch));

        service.executeReversal(LOG_ID, FACTORY_ID);

        assertThat(batch.getUsedQuantity()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("R3: clamp — 回扣量超过 usedQuantity 时归 0 不为负 (防御脏数据)")
    void executeReversal_clampsAtZero() {
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(SETTLEMENT_ID);
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));

        ProductionSettlementConsumption cons = new ProductionSettlementConsumption();
        cons.setId(1L);
        cons.setSettlementId(SETTLEMENT_ID);
        cons.setSourceType("MATERIAL_BATCH");
        cons.setMaterialBatchId("MB-1");
        cons.setQuantity(new BigDecimal("30.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(cons));

        // usedQuantity 只有 10 但要回扣 30 → clamp 到 0
        MaterialBatch batch = new MaterialBatch();
        batch.setId("MB-1");
        batch.setFactoryId(FACTORY_ID);
        batch.setReceiptQuantity(new BigDecimal("100.00"));
        batch.setUsedQuantity(new BigDecimal("10.00"));
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.USED_UP);
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("MB-1", FACTORY_ID))
                .thenReturn(Optional.of(batch));

        service.executeReversal(LOG_ID, FACTORY_ID);

        assertThat(batch.getUsedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("R3: SEMI_FINISHED 消耗行不当原料处理 (不查 MaterialBatch)")
    void executeReversal_semiFinishedLine_notTreatedAsMaterial() {
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(SETTLEMENT_ID);
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));

        ProductionSettlementConsumption sfLine = new ProductionSettlementConsumption();
        sfLine.setId(1L);
        sfLine.setSettlementId(SETTLEMENT_ID);
        sfLine.setSourceType("SEMI_FINISHED");
        sfLine.setSemiFinishedInventoryId(77L);
        sfLine.setQuantity(new BigDecimal("15.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(sfLine));

        // 半成品被领用恢复需要 wipRepo.findByIdForUpdate
        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(77L).factoryId(FACTORY_ID)
                .producedQuantity(new BigDecimal("100.00"))
                .consumedQuantity(new BigDecimal("15.00"))
                .availableQuantity(new BigDecimal("85.00"))
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findByIdForUpdate(77L)).thenReturn(Optional.of(sfi));

        service.executeReversal(LOG_ID, FACTORY_ID);

        // 不应把 SEMI_FINISHED 行当原料批次查询
        verify(materialBatchRepository, never()).findByIdAndFactoryIdForUpdate(any(), any());
    }

    @Test
    @DisplayName("R3: 撤回后软删 settlement + consumption (允许重新结单, 防重复回扣)")
    void executeReversal_softDeletesSettlement() {
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(SETTLEMENT_ID);
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));

        ProductionSettlementConsumption cons = new ProductionSettlementConsumption();
        cons.setId(1L);
        cons.setSettlementId(SETTLEMENT_ID);
        cons.setSourceType("MATERIAL_BATCH");
        cons.setMaterialBatchId("MB-1");
        cons.setQuantity(new BigDecimal("30.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(cons));

        MaterialBatch batch = new MaterialBatch();
        batch.setId("MB-1");
        batch.setFactoryId(FACTORY_ID);
        batch.setReceiptQuantity(new BigDecimal("100.00"));
        batch.setUsedQuantity(new BigDecimal("30.00"));
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.USED_UP);
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("MB-1", FACTORY_ID))
                .thenReturn(Optional.of(batch));

        service.executeReversal(LOG_ID, FACTORY_ID);

        // settlement + consumption 被软删 (deletedAt 置值) → @Where deleted_at IS NULL 隐藏 → 可重新结单
        assertThat(settlement.getDeletedAt()).isNotNull();
        assertThat(cons.getDeletedAt()).isNotNull();
        verify(productionSettlementRepository, atLeastOnce()).save(settlement);
        verify(productionSettlementConsumptionRepository, atLeastOnce()).save(cons);
    }

    @Test
    @DisplayName("R3: 无 settlement (报工未结单) → 不查原料批次, 不报错")
    void executeReversal_noSettlement_skips() {
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());

        service.executeReversal(LOG_ID, FACTORY_ID);

        verify(materialBatchRepository, never()).findByIdAndFactoryIdForUpdate(any(), any());
    }

    @Test
    @DisplayName("R3: repo 未注入 (老 context) → 跳过原料恢复, 不抛异常 (向后兼容)")
    void executeReversal_reposNotInjected_skipsGracefully() {
        // 模拟老 context: 清空 R3 field-inject deps
        ReflectionTestUtils.setField(service, "materialBatchRepository", null);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", null);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository", null);

        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());

        // 不应抛异常
        service.executeReversal(LOG_ID, FACTORY_ID);

        ArgumentCaptor<ReportReversalLog> captor = ArgumentCaptor.forClass(ReportReversalLog.class);
        verify(reversalLogRepo, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReportReversalLog.ReversalStatus.DONE);
    }

    // ─────────────────────────────────────────────────────────────
    // R5: replayMovingAverage 回放纳入 OUT
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R5: produced 100, OUT 30 (下游已领), 撤回某 IN → availableQuantity = 70 (不是 100)")
    void replayMovingAverage_includesOut_inAvailable() {
        // 报工 r → 一条 IN txn (semiFinishedId=77)
        ProductionReport r = ProductionReport.builder().id(900L).build();
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID)).thenReturn(List.of(r));

        SemiFinishedInventoryTransaction inTxn = SemiFinishedInventoryTransaction.builder()
                .id(1L).factoryId(FACTORY_ID).semiFinishedId(77L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                .quantity(new BigDecimal("100.000000"))
                .unitCostAtTxn(new BigDecimal("5.0000"))
                .build();
        when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 900L)).thenReturn(List.of(inTxn));
        when(txnRepo.save(any())).thenAnswer(invk -> {
            SemiFinishedInventoryTransaction t = invk.getArgument(0);
            if (t.getId() == null) t.setId(999L);
            return t;
        });

        // settlement 路径无关 → 返回空, 避免 NPE
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());

        // SFI 全流水: IN +100, OUT -30 (下游已领用, OUT 存负量)
        // 撤回会先写一条 REVERSE -100, 然后回放遍历: 100(IN) -100(REVERSE) -30(OUT) = -130 净 IN→ producedQuantity 归 0;
        // 但本测试关注的是"OUT 进 available 计算"——为隔离 R5 信号, 用一个未被撤回 IN 的场景:
        // produced=200 (两笔 IN), OUT -30, 撤回其中一笔 IN(-100) → 净 IN=100, available=100-30=70。
        SemiFinishedInventoryTransaction in2 = SemiFinishedInventoryTransaction.builder()
                .id(2L).factoryId(FACTORY_ID).semiFinishedId(77L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .quantity(new BigDecimal("100.000000"))
                .unitCostAtTxn(new BigDecimal("5.0000"))
                .build();
        SemiFinishedInventoryTransaction outTxn = SemiFinishedInventoryTransaction.builder()
                .id(3L).factoryId(FACTORY_ID).semiFinishedId(77L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                .quantity(new BigDecimal("-30.000000")) // OUT 存负量 (WipInventoryServiceImpl qty.negate())
                .unitCostAtTxn(new BigDecimal("5.0000"))
                .build();
        SemiFinishedInventoryTransaction reverseTxn = SemiFinishedInventoryTransaction.builder()
                .id(4L).factoryId(FACTORY_ID).semiFinishedId(77L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .quantity(new BigDecimal("-100.000000"))
                .unitCostAtTxn(new BigDecimal("5.0000"))
                .build();
        // 回放读取的是"写完 REVERSE 之后"的全量流水
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(77L))
                .thenReturn(List.of(inTxn, in2, outTxn, reverseTxn));

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(77L).factoryId(FACTORY_ID)
                .producedQuantity(new BigDecimal("200.00"))
                .availableQuantity(new BigDecimal("170.00"))
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findById(77L)).thenReturn(Optional.of(sfi));

        service.executeReversal(LOG_ID, FACTORY_ID);

        // producedQuantity = ΣIN + ΣREVERSE = 200 - 100 = 100 (净 IN, 不含 OUT)
        assertThat(sfi.getProducedQuantity()).isEqualByComparingTo(new BigDecimal("100.00"));
        // availableQuantity = 净 IN(100) - OUT(30) = 70 —— 关键: 不是 100 (旧 bug 忽略 OUT)
        assertThat(sfi.getAvailableQuantity()).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(sfi.getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);
    }

    @Test
    @DisplayName("R5: 全部领走 (OUT 等于净 IN) → availableQuantity = 0, status DEPLETED")
    void replayMovingAverage_allConsumed_availableZero() {
        ProductionReport r = ProductionReport.builder().id(900L).build();
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID)).thenReturn(List.of(r));
        when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 900L)).thenReturn(Collections.emptyList());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());

        // 直接驱动 replay: produced 100, OUT -100 (全被领走) → available 0
        SemiFinishedInventoryTransaction inTxn = SemiFinishedInventoryTransaction.builder()
                .id(1L).semiFinishedId(77L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .quantity(new BigDecimal("100.000000"))
                .unitCostAtTxn(new BigDecimal("5.0000"))
                .build();
        SemiFinishedInventoryTransaction outTxn = SemiFinishedInventoryTransaction.builder()
                .id(2L).semiFinishedId(77L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                .quantity(new BigDecimal("-100.000000"))
                .unitCostAtTxn(new BigDecimal("5.0000"))
                .build();

        // 通过 R5 测试入口: 调一次 executeReversal 触发 replay 不方便驱动两 IN，
        // 这里直接验证 replay 经由公开行为: 用 reflection 调私有方法。
        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(77L).factoryId(FACTORY_ID)
                .producedQuantity(new BigDecimal("100.00"))
                .availableQuantity(new BigDecimal("0.00"))
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findById(77L)).thenReturn(Optional.of(sfi));
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(77L))
                .thenReturn(List.of(inTxn, outTxn));

        ReflectionTestUtils.invokeMethod(service, "replayMovingAverage",
                77L, FACTORY_ID, java.time.LocalDateTime.now());

        // 净 IN = 100 > 0 → producedQuantity = 100; available = 100 - 100(OUT) = 0 → DEPLETED
        assertThat(sfi.getProducedQuantity()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(sfi.getAvailableQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(sfi.getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);
    }

    // ─────────────────────────────────────────────────────────────
    // 修1 (honest-null 成本传导): replayMovingAverage 缺成本时 unitCost=null,
    //   不稀释 (混合 known+null) / 不残留旧值 (全 null)。
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("修1: 混合 known+null 成本 IN → unitCost=null (不把未知量当 ¥0 稀释)")
    void replayMovingAverage_mixedKnownAndNullCost_unitCostNull() {
        // 100kg @¥10 (已知) + 50kg @null (未知成本) → producedQuantity=150,
        // 旧 bug: weightedCost=1000, unitCost=1000/150=6.6667 (把未知 50kg 当 ¥0 稀释)。
        // 正确: 有未知成本量 → unitCost/accumulatedCost 诚实置 null, 不稀释。
        SemiFinishedInventoryTransaction inKnown = SemiFinishedInventoryTransaction.builder()
                .id(1L).semiFinishedId(77L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .quantity(new BigDecimal("100.000000"))
                .unitCostAtTxn(new BigDecimal("10.0000"))
                .build();
        SemiFinishedInventoryTransaction inNull = SemiFinishedInventoryTransaction.builder()
                .id(2L).semiFinishedId(77L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .quantity(new BigDecimal("50.000000"))
                .unitCostAtTxn(null) // 缺成本
                .build();

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(77L).factoryId(FACTORY_ID)
                .producedQuantity(new BigDecimal("100.00"))
                .availableQuantity(new BigDecimal("100.00"))
                .unitCost(new BigDecimal("10.0000"))       // 回放前旧值
                .accumulatedCost(new BigDecimal("1000.00")) // 回放前旧值
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findById(77L)).thenReturn(Optional.of(sfi));
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(77L))
                .thenReturn(List.of(inKnown, inNull));

        ReflectionTestUtils.invokeMethod(service, "replayMovingAverage",
                77L, FACTORY_ID, java.time.LocalDateTime.now());

        // producedQuantity 仍为净 IN = 150 (数量口径不受成本未知影响)
        assertThat(sfi.getProducedQuantity()).isEqualByComparingTo(new BigDecimal("150.00"));
        // 关键: 有未知成本量 → unitCost / accumulatedCost 诚实 null, 不是 6.6667 稀释值
        assertThat(sfi.getUnitCost()).as("混合未知成本 → unitCost 诚实 null").isNull();
        assertThat(sfi.getAccumulatedCost()).as("混合未知成本 → accumulatedCost 诚实 null").isNull();
    }

    @Test
    @DisplayName("修1: 全部成本未知 (weightedCost=0) → unitCost/accumulatedCost 清 null (不残留旧值)")
    void replayMovingAverage_allNullCost_clearsStaleValues() {
        // 只 50kg @null → producedQuantity=50, weightedCost=0。
        // 旧 bug: if(weightedCost>0) 不进, 无 else → unitCost/accumulatedCost 保持回放前旧值 (脏残留)。
        SemiFinishedInventoryTransaction inNull = SemiFinishedInventoryTransaction.builder()
                .id(1L).semiFinishedId(77L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .quantity(new BigDecimal("50.000000"))
                .unitCostAtTxn(null)
                .build();

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(77L).factoryId(FACTORY_ID)
                .producedQuantity(new BigDecimal("50.00"))
                .availableQuantity(new BigDecimal("50.00"))
                .unitCost(new BigDecimal("99.9999"))        // 回放前脏旧值
                .accumulatedCost(new BigDecimal("4999.99")) // 回放前脏旧值
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findById(77L)).thenReturn(Optional.of(sfi));
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(77L))
                .thenReturn(List.of(inNull));

        ReflectionTestUtils.invokeMethod(service, "replayMovingAverage",
                77L, FACTORY_ID, java.time.LocalDateTime.now());

        assertThat(sfi.getProducedQuantity()).isEqualByComparingTo(new BigDecimal("50.00"));
        // 关键: weightedCost=0 时清空, 不残留回放前的 99.9999 / 4999.99
        assertThat(sfi.getUnitCost()).as("全未知成本 → unitCost 清 null 不残留").isNull();
        assertThat(sfi.getAccumulatedCost()).as("全未知成本 → accumulatedCost 清 null 不残留").isNull();
    }

    @Test
    @DisplayName("修1: 全部成本已知 → unitCost = 加权均价 (正常路径不退化)")
    void replayMovingAverage_allKnownCost_computesWeightedAverage() {
        // 100kg @¥10 + 50kg @¥4 → weightedCost=1200, producedQuantity=150, unitCost=8.0000。
        SemiFinishedInventoryTransaction in1 = SemiFinishedInventoryTransaction.builder()
                .id(1L).semiFinishedId(77L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .quantity(new BigDecimal("100.000000"))
                .unitCostAtTxn(new BigDecimal("10.0000"))
                .build();
        SemiFinishedInventoryTransaction in2 = SemiFinishedInventoryTransaction.builder()
                .id(2L).semiFinishedId(77L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .quantity(new BigDecimal("50.000000"))
                .unitCostAtTxn(new BigDecimal("4.0000"))
                .build();

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(77L).factoryId(FACTORY_ID)
                .producedQuantity(new BigDecimal("100.00"))
                .availableQuantity(new BigDecimal("100.00"))
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findById(77L)).thenReturn(Optional.of(sfi));
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(77L))
                .thenReturn(List.of(in1, in2));

        ReflectionTestUtils.invokeMethod(service, "replayMovingAverage",
                77L, FACTORY_ID, java.time.LocalDateTime.now());

        assertThat(sfi.getProducedQuantity()).isEqualByComparingTo(new BigDecimal("150.00"));
        // weightedCost = 100*10 + 50*4 = 1200; unitCost = 1200/150 = 8.0000
        assertThat(sfi.getUnitCost()).isEqualByComparingTo(new BigDecimal("8.0000"));
        assertThat(sfi.getAccumulatedCost()).isEqualByComparingTo(new BigDecimal("1200.00"));
    }

    // ─────────────────────────────────────────────────────────────
    // 修2 (fail-closed): repo 已注入但 restore 失败 (批次缺失/工厂不匹配) →
    //   抛 BusinessException 整体回滚, 不软删消耗行/settlement, 不置 DONE。
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("修2: repo已注入但原料批次不存在 → 抛异常, 消耗行/settlement 未软删 (fail-closed)")
    void executeReversal_restoreFails_throwsAndDoesNotSoftDelete() {
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(SETTLEMENT_ID);
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));

        ProductionSettlementConsumption cons = new ProductionSettlementConsumption();
        cons.setId(1L);
        cons.setSettlementId(SETTLEMENT_ID);
        cons.setSourceType("MATERIAL_BATCH");
        cons.setMaterialBatchId("MB-MISSING");
        cons.setQuantity(new BigDecimal("30.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(cons));

        // 批次不存在 → restoreMaterialBatchConsumption 返 false → 应 fail-closed 抛异常
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("MB-MISSING", FACTORY_ID))
                .thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.executeReversal(LOG_ID, FACTORY_ID))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class);

        // 关键: 失败 → 消耗行/settlement 未软删 (允许修数据后重跑), 不丢库存
        assertThat(cons.getDeletedAt()).as("恢复失败 → 消耗行不软删").isNull();
        assertThat(settlement.getDeletedAt()).as("恢复失败 → settlement 不软删").isNull();
        verify(productionSettlementConsumptionRepository, never()).save(cons);
        verify(productionSettlementRepository, never()).save(settlement);
    }

    @Test
    @DisplayName("修2: 半成品恢复失败 (库存不存在) → 抛异常, 不软删 (fail-closed)")
    void executeReversal_semiFinishedRestoreFails_throws() {
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(SETTLEMENT_ID);
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));

        ProductionSettlementConsumption sfLine = new ProductionSettlementConsumption();
        sfLine.setId(1L);
        sfLine.setSettlementId(SETTLEMENT_ID);
        sfLine.setSourceType("SEMI_FINISHED");
        sfLine.setSemiFinishedInventoryId(88L);
        sfLine.setQuantity(new BigDecimal("15.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(sfLine));

        // 半成品库存不存在 → restoreSemiFinishedConsumption 返 false → fail-closed
        when(wipRepo.findByIdForUpdate(88L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.executeReversal(LOG_ID, FACTORY_ID))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class);

        assertThat(sfLine.getDeletedAt()).isNull();
        assertThat(settlement.getDeletedAt()).isNull();
        verify(productionSettlementConsumptionRepository, never()).save(sfLine);
        verify(productionSettlementRepository, never()).save(settlement);
    }

    @Test
    @DisplayName("修2: 全部恢复成功 → 正常软删 + DONE (回归保护)")
    void executeReversal_allRestoreSucceed_softDeletesAndDone() {
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.emptyList());

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(SETTLEMENT_ID);
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.of(settlement));

        ProductionSettlementConsumption cons = new ProductionSettlementConsumption();
        cons.setId(1L);
        cons.setSettlementId(SETTLEMENT_ID);
        cons.setSourceType("MATERIAL_BATCH");
        cons.setMaterialBatchId("MB-1");
        cons.setQuantity(new BigDecimal("30.00"));
        when(productionSettlementConsumptionRepository.findBySettlementIdAndDeletedAtIsNull(SETTLEMENT_ID))
                .thenReturn(List.of(cons));

        MaterialBatch batch = new MaterialBatch();
        batch.setId("MB-1");
        batch.setFactoryId(FACTORY_ID);
        batch.setReceiptQuantity(new BigDecimal("100.00"));
        batch.setUsedQuantity(new BigDecimal("30.00"));
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.USED_UP);
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("MB-1", FACTORY_ID))
                .thenReturn(Optional.of(batch));

        service.executeReversal(LOG_ID, FACTORY_ID);

        // 恢复成功 → 正常软删 + DONE
        assertThat(cons.getDeletedAt()).isNotNull();
        assertThat(settlement.getDeletedAt()).isNotNull();
        ArgumentCaptor<ReportReversalLog> captor = ArgumentCaptor.forClass(ReportReversalLog.class);
        verify(reversalLogRepo, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReportReversalLog.ReversalStatus.DONE);
    }
}
