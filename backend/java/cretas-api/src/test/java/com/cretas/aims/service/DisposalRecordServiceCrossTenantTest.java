package com.cretas.aims.service;

import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.DisposalRecordRepository;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计 round2 多租户隔离: 报废记录按 id 加载的写/读方法必须校验记录归属工厂。
 * 此前 approveDisposal/updateDisposalRecord/deleteDisposalRecord/submitForApproval/getById
 * 用 findById(id) 不校验 factoryId → F006 用户可操作其它工厂的报废记录 (id 可枚举)。
 */
class DisposalRecordServiceCrossTenantTest {

    private DisposalRecordRepository repo;
    private MaterialBatchRepository materialBatchRepo;
    private MaterialBatchAdjustmentRepository adjustmentRepo;
    private ProductionBatchRepository productionBatchRepo;
    private FinishedGoodsBatchRepository fgBatchRepo;
    private FinishedGoodsAdjustmentLogRepository fgAdjustmentLogRepo;
    private DisposalRecordService service;

    @BeforeEach
    void setUp() {
        repo = mock(DisposalRecordRepository.class);
        materialBatchRepo = mock(MaterialBatchRepository.class);
        adjustmentRepo = mock(MaterialBatchAdjustmentRepository.class);
        productionBatchRepo = mock(ProductionBatchRepository.class);
        fgBatchRepo = mock(FinishedGoodsBatchRepository.class);
        fgAdjustmentLogRepo = mock(FinishedGoodsAdjustmentLogRepository.class);
        service = new DisposalRecordService(repo, materialBatchRepo, adjustmentRepo, productionBatchRepo,
                fgBatchRepo, fgAdjustmentLogRepo);
    }

    private DisposalRecord record(long id, String factoryId) {
        DisposalRecord r = new DisposalRecord();
        r.setId(id);
        r.setFactoryId(factoryId);
        r.setIsApproved(false);
        return r;
    }

    /** 财务角色 — 通过 F-BUG-5 角色守卫, 以验证后续的工厂归属守卫。 */
    private static final String FINANCE_ROLE = "finance_manager";

    @Test
    void approveDisposal_crossTenant_throws403_noSave() {
        when(repo.findById(50L)).thenReturn(Optional.of(record(50L, "F999")));  // 别家工厂记录

        // 用合法财务角色, 确保 403 来自工厂归属守卫 (而非角色守卫)
        assertThatThrownBy(() -> service.approveDisposal("F006", 50L, 1, "审批人", FINANCE_ROLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(repo, never()).save(any());
    }

    @Test
    void approveDisposal_nonFinanceRole_throws403_noSave() {
        // F-BUG-5: 仓管员 (warehouse_manager) 不能自批报废
        when(repo.findById(56L)).thenReturn(Optional.of(record(56L, "F006")));

        assertThatThrownBy(() -> service.approveDisposal("F006", 56L, 1, "仓管员", "warehouse_manager"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(repo, never()).save(any());
    }

    @Test
    void rejectDisposal_sameFactory_setsRejected_noStockDeduction() {
        DisposalRecord r = record(57L, "F006");
        when(repo.findById(57L)).thenReturn(Optional.of(r));
        when(repo.save(any(DisposalRecord.class))).thenAnswer(i -> i.getArgument(0));

        service.rejectDisposal("F006", 57L, 1, "财务", "证据不足", FINANCE_ROLE);

        verify(repo).save(any(DisposalRecord.class));
        assertThat(r.getIsApproved()).isFalse();              // 不扣库存
        assertThat(r.getStatus()).isEqualTo("REJECTED");
        assertThat(r.getRejectReason()).isEqualTo("证据不足");
    }

    @Test
    void updateDisposalRecord_crossTenant_throws403_noSave() {
        when(repo.findById(51L)).thenReturn(Optional.of(record(51L, "F999")));

        assertThatThrownBy(() -> service.updateDisposalRecord("F006", 51L, new DisposalRecord()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(repo, never()).save(any());
    }

    @Test
    void deleteDisposalRecord_crossTenant_throws403_noSave() {
        when(repo.findById(52L)).thenReturn(Optional.of(record(52L, "F999")));

        assertThatThrownBy(() -> service.deleteDisposalRecord("F006", 52L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(repo, never()).save(any());
    }

    @Test
    void submitForApproval_crossTenant_throws403() {
        when(repo.findById(53L)).thenReturn(Optional.of(record(53L, "F999")));

        assertThatThrownBy(() -> service.submitForApproval(53L, "F006", 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
    }

    @Test
    void getById_crossTenant_returnsEmpty() {
        when(repo.findById(54L)).thenReturn(Optional.of(record(54L, "F999")));

        assertThat(service.getById("F006", 54L)).isEmpty();          // 跨租户 → 空 (404)
        assertThat(service.getById("F999", 54L)).isPresent();        // 本厂 → 可见
    }

    @Test
    void approveDisposal_sameFactory_noBatch_proceeds_usesEstimatedLoss() {
        DisposalRecord r = record(55L, "F006");
        r.setDisposalQuantity(new BigDecimal("5.00"));
        r.setEstimatedLoss(new BigDecimal("120.00"));   // 无关联批次 → actualLoss 取估损兜底
        when(repo.findById(55L)).thenReturn(Optional.of(r));
        when(repo.save(any(DisposalRecord.class))).thenAnswer(i -> i.getArgument(0));

        // 本厂记录 + 财务角色 → 正常审批 (两道守卫放行)
        service.approveDisposal("F006", 55L, 1, "审批人", FINANCE_ROLE);
        verify(repo).save(any(DisposalRecord.class));
        assertThat(r.getIsApproved()).isTrue();
        assertThat(r.getActualLoss()).isEqualByComparingTo("120.00");
        verify(materialBatchRepo, never()).save(any());      // 无批次 → 不扣库存
    }

    // ===================================================================
    // Feature #8: 报损审批通过 → 真实扣减库存 + 记损失金额
    // ===================================================================

    private MaterialBatch materialBatch(String id, String factoryId, BigDecimal receiptQty,
            BigDecimal unitPrice) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(factoryId);
        b.setReceiptQuantity(receiptQty);
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setUnitPrice(unitPrice);
        return b;
    }

    @Test
    void approveDisposal_materialBatch_deductsInventory_writesAdjustment_andActualLoss() {
        DisposalRecord r = record(60L, "F006");
        r.setMaterialBatchId("MB-1");
        r.setDisposalQuantity(new BigDecimal("10.00"));
        r.setDisposalType("SCRAP");
        MaterialBatch batch = materialBatch("MB-1", "F006", new BigDecimal("100.00"), new BigDecimal("8.00"));

        when(repo.findById(60L)).thenReturn(Optional.of(r));
        when(materialBatchRepo.findById("MB-1")).thenReturn(Optional.of(batch));
        when(repo.save(any(DisposalRecord.class))).thenAnswer(i -> i.getArgument(0));

        service.approveDisposal("F006", 60L, 7, "财务", FINANCE_ROLE);

        // H1: 扣减增 usedQuantity, receiptQuantity 不变 → currentQuantity = 100 - 10 = 90
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("10.00");
        assertThat(batch.getReceiptQuantity()).isEqualByComparingTo("100.00");  // receipt 不动
        assertThat(batch.getCurrentQuantity()).isEqualByComparingTo("90.00");
        verify(materialBatchRepo).save(batch);
        // 写 adjustment 负数留痕 (before/after 取可用量)
        verify(adjustmentRepo).save(argThat((MaterialBatchAdjustment a) ->
                a.getAdjustmentQuantity().compareTo(new BigDecimal("-10.00")) == 0
                        && "DISPOSAL".equals(a.getAdjustmentType())
                        && a.getQuantityBefore().compareTo(new BigDecimal("100.00")) == 0
                        && a.getQuantityAfter().compareTo(new BigDecimal("90.00")) == 0));
        // actualLoss = 10 × 8 = 80
        assertThat(r.getActualLoss()).isEqualByComparingTo("80.00");
        assertThat(r.getStatus()).isEqualTo("APPROVED");
    }

    // H1: 库存不足判定必须用 currentQuantity (receipt - used - reserved), 不能只看 receiptQuantity.
    // 否则会扣进别人已领用(usedQuantity)的量, 把 currentQuantity 扣成负数.
    @Test
    void approveDisposal_materialBatch_usedQty_overDeductBlocked_throws409() {
        DisposalRecord r = record(66L, "F006");
        r.setMaterialBatchId("MB-6");
        r.setDisposalQuantity(new BigDecimal("50.00"));    // receipt=100 used=80 → 可用仅 20, 报损 50 应 409
        r.setDisposalType("SCRAP");
        MaterialBatch batch = materialBatch("MB-6", "F006", new BigDecimal("100.00"), new BigDecimal("8.00"));
        batch.setUsedQuantity(new BigDecimal("80.00"));    // 别人已领 80, 真实可用 20

        when(repo.findById(66L)).thenReturn(Optional.of(r));
        when(materialBatchRepo.findById("MB-6")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.approveDisposal("F006", 66L, 7, "财务", FINANCE_ROLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409));
        verify(materialBatchRepo, never()).save(any());
        verify(adjustmentRepo, never()).save(any());
        verify(repo, never()).save(any());
        // 库存不变 (旧 bug: 100-50=50>=0 会通过并扣进已领量, currentQuantity 变 -30)
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("80.00");
        assertThat(batch.getCurrentQuantity()).isEqualByComparingTo("20.00");
    }

    // H1: receipt=100 used=80 → 可用 20, 报损 20 (恰好可用) → 成功, currentQuantity = 0.
    @Test
    void approveDisposal_materialBatch_usedQty_exactAvailable_succeeds_currentZero() {
        DisposalRecord r = record(67L, "F006");
        r.setMaterialBatchId("MB-7");
        r.setDisposalQuantity(new BigDecimal("20.00"));
        r.setDisposalType("SCRAP");
        MaterialBatch batch = materialBatch("MB-7", "F006", new BigDecimal("100.00"), new BigDecimal("8.00"));
        batch.setUsedQuantity(new BigDecimal("80.00"));

        when(repo.findById(67L)).thenReturn(Optional.of(r));
        when(materialBatchRepo.findById("MB-7")).thenReturn(Optional.of(batch));
        when(repo.save(any(DisposalRecord.class))).thenAnswer(i -> i.getArgument(0));

        service.approveDisposal("F006", 67L, 7, "财务", FINANCE_ROLE);

        // used: 80 + 20 = 100, receipt 不变 → currentQuantity = 0
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("100.00");
        assertThat(batch.getReceiptQuantity()).isEqualByComparingTo("100.00");
        assertThat(batch.getCurrentQuantity()).isEqualByComparingTo("0.00");
        verify(materialBatchRepo).save(batch);
        verify(adjustmentRepo).save(argThat((MaterialBatchAdjustment a) ->
                a.getQuantityBefore().compareTo(new BigDecimal("20.00")) == 0
                        && a.getQuantityAfter().compareTo(new BigDecimal("0.00")) == 0));
        assertThat(r.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void approveDisposal_materialBatch_insufficientStock_throws409_noDeduct() {
        DisposalRecord r = record(61L, "F006");
        r.setMaterialBatchId("MB-2");
        r.setDisposalQuantity(new BigDecimal("150.00"));   // 超过库存 100
        r.setDisposalType("SCRAP");
        MaterialBatch batch = materialBatch("MB-2", "F006", new BigDecimal("100.00"), new BigDecimal("8.00"));

        when(repo.findById(61L)).thenReturn(Optional.of(r));
        when(materialBatchRepo.findById("MB-2")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.approveDisposal("F006", 61L, 7, "财务", FINANCE_ROLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409));
        verify(materialBatchRepo, never()).save(any());
        verify(adjustmentRepo, never()).save(any());
        verify(repo, never()).save(any());                 // 审批不落库
        assertThat(batch.getReceiptQuantity()).isEqualByComparingTo("100.00");  // 库存不变
    }

    @Test
    void approveDisposal_materialBatch_crossTenantBatch_throws403() {
        DisposalRecord r = record(62L, "F006");
        r.setMaterialBatchId("MB-3");
        r.setDisposalQuantity(new BigDecimal("5.00"));
        MaterialBatch batch = materialBatch("MB-3", "F999", new BigDecimal("100.00"), new BigDecimal("8.00"));

        when(repo.findById(62L)).thenReturn(Optional.of(r));
        when(materialBatchRepo.findById("MB-3")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.approveDisposal("F006", 62L, 7, "财务", FINANCE_ROLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(materialBatchRepo, never()).save(any());
    }

    @Test
    void approveDisposal_alreadyApproved_throws409_noSecondDeduct() {
        DisposalRecord r = record(63L, "F006");
        r.setMaterialBatchId("MB-4");
        r.setDisposalQuantity(new BigDecimal("10.00"));
        r.setApprovalStatus("APPROVED");                   // 已审批 → 幂等守卫
        when(repo.findById(63L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.approveDisposal("F006", 63L, 7, "财务", FINANCE_ROLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409));
        verify(materialBatchRepo, never()).save(any());
        verify(adjustmentRepo, never()).save(any());
    }

    @Test
    void rejectDisposal_materialBatch_doesNotDeductInventory() {
        DisposalRecord r = record(64L, "F006");
        r.setMaterialBatchId("MB-5");
        r.setDisposalQuantity(new BigDecimal("10.00"));
        when(repo.findById(64L)).thenReturn(Optional.of(r));
        when(repo.save(any(DisposalRecord.class))).thenAnswer(i -> i.getArgument(0));

        service.rejectDisposal("F006", 64L, 7, "财务", "证据不足", FINANCE_ROLE);

        assertThat(r.getStatus()).isEqualTo("REJECTED");
        verify(materialBatchRepo, never()).findById(any());   // reject 完全不碰库存
        verify(materialBatchRepo, never()).save(any());
        verify(adjustmentRepo, never()).save(any());
    }

    // H2: 成品报损安全阻止 (422). 不扣 ProductionBatch.quantity (那是产出额定值非可售库存),
    // 不污染生产分析. 真正可售库存在 FinishedGoodsBatch, 但 DisposalRecord 无法可靠定位 FG 批次,
    // 故当前路径明确阻止 (不静默扣错).
    @Test
    void approveDisposal_productionBatch_safelyBlocked_throws422_noQuantityDeduct() {
        DisposalRecord r = record(65L, "F006");
        r.setProductionBatchId("200");
        r.setDisposalQuantity(new BigDecimal("4.00"));
        r.setDisposalType("DESTROY");
        ProductionBatch pb = new ProductionBatch();
        pb.setId(200L);
        pb.setFactoryId("F006");
        pb.setQuantity(new BigDecimal("50.00"));
        pb.setUnitCost(new BigDecimal("12.5000"));

        when(repo.findById(65L)).thenReturn(Optional.of(r));
        when(productionBatchRepo.findById(200L)).thenReturn(Optional.of(pb));

        assertThatThrownBy(() -> service.approveDisposal("F006", 65L, 7, "财务", FINANCE_ROLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(422));

        // 不扣生产批次产出额定值 (旧 bug: 50 - 4 = 46 会污染 yield-rate 分母)
        assertThat(pb.getQuantity()).isEqualByComparingTo("50.00");
        verify(productionBatchRepo, never()).save(any());
        verify(repo, never()).save(any());                 // 审批不落库
    }

    // H2: 成品报损跨租户生产批次 → 403 (在 422 安全阻止前先校验归属)
    @Test
    void approveDisposal_productionBatch_crossTenant_throws403() {
        DisposalRecord r = record(68L, "F006");
        r.setProductionBatchId("201");
        r.setDisposalQuantity(new BigDecimal("4.00"));
        ProductionBatch pb = new ProductionBatch();
        pb.setId(201L);
        pb.setFactoryId("F999");                            // 别家工厂生产批次
        pb.setQuantity(new BigDecimal("50.00"));

        when(repo.findById(68L)).thenReturn(Optional.of(r));
        when(productionBatchRepo.findById(201L)).thenReturn(Optional.of(pb));

        assertThatThrownBy(() -> service.approveDisposal("F006", 68L, 7, "财务", FINANCE_ROLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(productionBatchRepo, never()).save(any());
    }

    // ===================================================================
    // R12: 成品报损 (finishedGoodsBatchId) → 真扣 FinishedGoodsBatch 可用量
    // ===================================================================

    private FinishedGoodsBatch fgBatch(String id, String factoryId, BigDecimal produced,
            BigDecimal shipped, BigDecimal reserved, BigDecimal unitPrice) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId(id);
        b.setFactoryId(factoryId);
        b.setProducedQuantity(produced);
        b.setShippedQuantity(shipped);
        b.setReservedQuantity(reserved);
        b.setUnitPrice(unitPrice);
        b.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        return b;
    }

    @Test
    void approveDisposal_finishedGoodsBatch_deductsAvailable_writesLog_andActualLoss() {
        DisposalRecord r = record(70L, "F006");
        r.setFinishedGoodsBatchId("FG-1");
        r.setDisposalQuantity(new BigDecimal("10.00"));
        r.setDisposalType("DESTROY");
        // produced=100, shipped=20, reserved=10 → available = 70
        FinishedGoodsBatch batch = fgBatch("FG-1", "F006",
                new BigDecimal("100.0000"), new BigDecimal("20.0000"),
                new BigDecimal("10.0000"), new BigDecimal("12.5000"));

        when(repo.findById(70L)).thenReturn(Optional.of(r));
        when(fgBatchRepo.findById("FG-1")).thenReturn(Optional.of(batch));
        when(repo.save(any(DisposalRecord.class))).thenAnswer(i -> i.getArgument(0));

        service.approveDisposal("F006", 70L, 7, "财务", FINANCE_ROLE);

        // 减 producedQuantity: 100 - 10 = 90; shipped/reserved 不动 → available 90-20-10=60
        assertThat(batch.getProducedQuantity()).isEqualByComparingTo("90.0000");
        assertThat(batch.getShippedQuantity()).isEqualByComparingTo("20.0000");   // 不动已发
        assertThat(batch.getReservedQuantity()).isEqualByComparingTo("10.0000");  // 不动预留
        assertThat(batch.getAvailableQuantity()).isEqualByComparingTo("60.0000");
        assertThat(batch.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        verify(fgBatchRepo).save(batch);
        // 写 SCRAP 调整日志, 负数变更, before/after 取 producedQuantity
        verify(fgAdjustmentLogRepo).save(argThat((FinishedGoodsAdjustmentLog a) ->
                a.getAdjustmentQuantity().compareTo(new BigDecimal("-10.00")) == 0
                        && "SCRAP".equals(a.getReferenceType())
                        && a.getBeforeProduced().compareTo(new BigDecimal("100.00")) == 0
                        && a.getAfterProduced().compareTo(new BigDecimal("90.00")) == 0
                        && "FG-1".equals(a.getBatchId())));
        // actualLoss = 10 × 12.5 = 125.00
        assertThat(r.getActualLoss()).isEqualByComparingTo("125.00");
        assertThat(r.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void approveDisposal_finishedGoodsBatch_exactAvailable_succeeds_marksDepleted() {
        DisposalRecord r = record(71L, "F006");
        r.setFinishedGoodsBatchId("FG-2");
        r.setDisposalQuantity(new BigDecimal("70.00"));    // 恰好等于 available(70) → 扣空
        r.setDisposalType("SCRAP");
        FinishedGoodsBatch batch = fgBatch("FG-2", "F006",
                new BigDecimal("100.0000"), new BigDecimal("20.0000"),
                new BigDecimal("10.0000"), new BigDecimal("8.0000"));

        when(repo.findById(71L)).thenReturn(Optional.of(r));
        when(fgBatchRepo.findById("FG-2")).thenReturn(Optional.of(batch));
        when(repo.save(any(DisposalRecord.class))).thenAnswer(i -> i.getArgument(0));

        service.approveDisposal("F006", 71L, 7, "财务", FINANCE_ROLE);

        // produced: 100 - 70 = 30; available = 30 - 20 - 10 = 0 → DEPLETED
        assertThat(batch.getProducedQuantity()).isEqualByComparingTo("30.0000");
        assertThat(batch.getAvailableQuantity()).isEqualByComparingTo("0.0000");
        assertThat(batch.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEPLETED);
        verify(fgBatchRepo).save(batch);
        verify(fgAdjustmentLogRepo).save(any());
        assertThat(r.getActualLoss()).isEqualByComparingTo("560.00");   // 70 × 8
        assertThat(r.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void approveDisposal_finishedGoodsBatch_insufficientAvailable_throws409_noDeduct() {
        DisposalRecord r = record(72L, "F006");
        r.setFinishedGoodsBatchId("FG-3");
        r.setDisposalQuantity(new BigDecimal("80.00"));    // available 仅 70 → 409
        r.setDisposalType("SCRAP");
        FinishedGoodsBatch batch = fgBatch("FG-3", "F006",
                new BigDecimal("100.0000"), new BigDecimal("20.0000"),
                new BigDecimal("10.0000"), new BigDecimal("8.0000"));

        when(repo.findById(72L)).thenReturn(Optional.of(r));
        when(fgBatchRepo.findById("FG-3")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.approveDisposal("F006", 72L, 7, "财务", FINANCE_ROLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409));
        verify(fgBatchRepo, never()).save(any());
        verify(fgAdjustmentLogRepo, never()).save(any());
        verify(repo, never()).save(any());
        // 库存不变
        assertThat(batch.getProducedQuantity()).isEqualByComparingTo("100.0000");
        assertThat(batch.getAvailableQuantity()).isEqualByComparingTo("70.0000");
    }

    @Test
    void approveDisposal_finishedGoodsBatch_crossTenant_throws403() {
        DisposalRecord r = record(73L, "F006");
        r.setFinishedGoodsBatchId("FG-4");
        r.setDisposalQuantity(new BigDecimal("5.00"));
        FinishedGoodsBatch batch = fgBatch("FG-4", "F999",       // 别家工厂成品批次
                new BigDecimal("100.0000"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("8.0000"));

        when(repo.findById(73L)).thenReturn(Optional.of(r));
        when(fgBatchRepo.findById("FG-4")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.approveDisposal("F006", 73L, 7, "财务", FINANCE_ROLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(fgBatchRepo, never()).save(any());
        verify(fgAdjustmentLogRepo, never()).save(any());
    }

    @Test
    void approveDisposal_finishedGoodsBatch_notFound_throws404() {
        DisposalRecord r = record(74L, "F006");
        r.setFinishedGoodsBatchId("FG-MISSING");
        r.setDisposalQuantity(new BigDecimal("5.00"));

        when(repo.findById(74L)).thenReturn(Optional.of(r));
        when(fgBatchRepo.findById("FG-MISSING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveDisposal("F006", 74L, 7, "财务", FINANCE_ROLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(404));
        verify(fgBatchRepo, never()).save(any());
    }

    @Test
    void rejectDisposal_finishedGoodsBatch_doesNotDeductInventory() {
        DisposalRecord r = record(75L, "F006");
        r.setFinishedGoodsBatchId("FG-5");
        r.setDisposalQuantity(new BigDecimal("10.00"));
        when(repo.findById(75L)).thenReturn(Optional.of(r));
        when(repo.save(any(DisposalRecord.class))).thenAnswer(i -> i.getArgument(0));

        service.rejectDisposal("F006", 75L, 7, "财务", "证据不足", FINANCE_ROLE);

        assertThat(r.getStatus()).isEqualTo("REJECTED");
        verify(fgBatchRepo, never()).findById(any());   // reject 完全不碰成品库存
        verify(fgBatchRepo, never()).save(any());
        verify(fgAdjustmentLogRepo, never()).save(any());
    }

    // R12 向后兼容: 仅有 productionBatchId 无 finishedGoodsBatchId (旧数据) → 仍走 422 安全阻止.
    @Test
    void approveDisposal_productionBatchOnly_noFinishedGoodsBatchId_stillBlocked422() {
        DisposalRecord r = record(76L, "F006");
        r.setProductionBatchId("300");
        // finishedGoodsBatchId 为 null (旧数据)
        r.setDisposalQuantity(new BigDecimal("4.00"));
        r.setDisposalType("DESTROY");
        ProductionBatch pb = new ProductionBatch();
        pb.setId(300L);
        pb.setFactoryId("F006");
        pb.setQuantity(new BigDecimal("50.00"));

        when(repo.findById(76L)).thenReturn(Optional.of(r));
        when(productionBatchRepo.findById(300L)).thenReturn(Optional.of(pb));

        assertThatThrownBy(() -> service.approveDisposal("F006", 76L, 7, "财务", FINANCE_ROLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(422));
        // 不扣任何库存
        assertThat(pb.getQuantity()).isEqualByComparingTo("50.00");
        verify(productionBatchRepo, never()).save(any());
        verify(fgBatchRepo, never()).save(any());
        verify(repo, never()).save(any());
    }
}
