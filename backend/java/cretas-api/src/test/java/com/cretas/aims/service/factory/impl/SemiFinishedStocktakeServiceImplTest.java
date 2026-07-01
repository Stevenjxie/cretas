package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateSemiFinishedStocktakeRequest;
import com.cretas.aims.dto.factory.SemiFinishedStocktakeItemUpdateDTO;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.factory.SemiFinishedStocktake;
import com.cretas.aims.entity.factory.SemiFinishedStocktakeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeItemRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeRepository;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SemiFinishedStocktakeServiceImpl 单元测试 (镜像 SP7 FactoryStocktakeServiceImplTest)。
 *
 * <p>覆盖场景:
 * <ol>
 *   <li>月底约束 threshold=29 day&lt;29 → 409 / day&gt;=29 放行</li>
 *   <li>threshold=1 放行 + 快照 AVAILABLE 半成品行</li>
 *   <li>duplicate check → 409</li>
 *   <li>updateItems SHORTAGE / SURPLUS 差异计算</li>
 *   <li>apply 幂等: 已 APPLIED → 409</li>
 *   <li>apply APPROVED → 写 ADJUST/STOCKTAKE 流水 + availableQuantity=actualQty + produced=actual+consumed</li>
 *   <li>apply 非 APPROVED (PENDING) → 409</li>
 *   <li>apply 跨租户 factoryId 不符 → 403</li>
 *   <li>approve 角色: 错误/operator → 403; finance_manager/factory_super_admin/大写 → APPROVED</li>
 * </ol>
 */
@DisplayName("SemiFinishedStocktakeServiceImpl 单元测试 (半成品盘点)")
@ExtendWith(MockitoExtension.class)
class SemiFinishedStocktakeServiceImplTest {

    @Mock private SemiFinishedStocktakeRepository stocktakeRepo;
    @Mock private SemiFinishedStocktakeItemRepository stocktakeItemRepo;
    @Mock private SemiFinishedInventoryRepository sfiRepo;
    @Mock private SemiFinishedInventoryTransactionRepository sfiTxnRepo;
    @Mock private VoucherService voucherService;

    @InjectMocks private SemiFinishedStocktakeServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String OTHER_FACTORY = "F999";
    private static final Long USER_ID = 42L;
    private static final String BATCH_NO = "SEMI-2026-0001";
    private static final String BATCH_NO_2 = "SEMI-2026-0002";

    // -------------------------------------------------------
    // 1. 任意时间可发起 (去月底限制) + 快照 AVAILABLE 半成品行
    // -------------------------------------------------------
    @Test
    @DisplayName("T1: 任意日期(含月初1号)均可发起 — 无月底限制; 快照 AVAILABLE 行 systemQty=availableQuantity")
    void initiate_anyTime_noMonthEndBlock_snapshotsAvailableRows() {
        CreateSemiFinishedStocktakeRequest req = new CreateSemiFinishedStocktakeRequest();
        req.setPeriodMonth("2026-06");

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(100L).factoryId(FACTORY_ID).intermediateBatchNo(BATCH_NO)
                .productTypeId("PT-1").unit("kg")
                .producedQuantity(new BigDecimal("50.00")).consumedQuantity(new BigDecimal("10.00"))
                .availableQuantity(new BigDecimal("40.00")).status(SemiFinishedInventory.Status.AVAILABLE)
                .build();

        when(stocktakeRepo.countActiveStocktake(FACTORY_ID)).thenReturn(0L);
        when(sfiRepo.findByFactoryIdAndStatusForStocktake(FACTORY_ID, SemiFinishedInventory.Status.AVAILABLE))
                .thenReturn(List.of(sfi));
        when(stocktakeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 无论今天几号都放行 (删除了 monthEndThreshold 逻辑)
        var dto = service.initiate(FACTORY_ID, req, USER_ID);

        ArgumentCaptor<SemiFinishedStocktake> captor = ArgumentCaptor.forClass(SemiFinishedStocktake.class);
        verify(stocktakeRepo).save(captor.capture());
        SemiFinishedStocktake saved = captor.getValue();
        assertThat(saved.getItems()).hasSize(1);
        SemiFinishedStocktakeItem item = saved.getItems().get(0);
        assertThat(item.getSemiFinishedId()).isEqualTo(100L);
        assertThat(item.getIntermediateBatchNo()).isEqualTo(BATCH_NO);
        assertThat(item.getSystemQty().compareTo(new BigDecimal("40.0000"))).isEqualTo(0);
        assertThat(dto.getStocktakeNo()).startsWith("SFST-");
    }

    // -------------------------------------------------------
    // 2. 进行中去重 (唯一 guard) — 允许同月多次 (周复盘)
    // -------------------------------------------------------
    @Test
    @DisplayName("T2: 同工厂已有进行中(非终态)盘点 → 409 '已有进行中'")
    void initiate_inProgress_throws409() {
        CreateSemiFinishedStocktakeRequest req = new CreateSemiFinishedStocktakeRequest();
        req.setPeriodMonth("2026-06");
        when(stocktakeRepo.countActiveStocktake(FACTORY_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.initiate(FACTORY_ID, req, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getMessage()).contains("已有进行中");
                });
        verify(stocktakeRepo, never()).save(any());
    }

    @Test
    @DisplayName("T2b: 上一个盘点已终态(APPLIED/REJECTED) → countActiveStocktake=0 → 放行 (支持周复盘节奏)")
    void initiate_priorTerminal_allowsNew() {
        CreateSemiFinishedStocktakeRequest req = new CreateSemiFinishedStocktakeRequest();
        req.setPeriodMonth("2026-06");
        when(stocktakeRepo.countActiveStocktake(FACTORY_ID)).thenReturn(0L);
        when(sfiRepo.findByFactoryIdAndStatusForStocktake(any(), any())).thenReturn(Collections.emptyList());
        when(stocktakeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.initiate(FACTORY_ID, req, USER_ID);
        assertThat(dto.getStocktakeNo()).startsWith("SFST-");
    }

    // -------------------------------------------------------
    // 3. updateItems 差异计算
    // -------------------------------------------------------
    @Test
    @DisplayName("T3: 实盘 < 系统 → SHORTAGE")
    void updateItems_shortage() {
        SemiFinishedStocktakeItem item = buildItem("100.0000");
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.INITIATED, item);
        when(stocktakeRepo.findById(st.getId())).thenReturn(Optional.of(st));
        when(stocktakeRepo.save(any())).thenReturn(st);

        SemiFinishedStocktakeItemUpdateDTO u = new SemiFinishedStocktakeItemUpdateDTO();
        u.setItemId(item.getId());
        u.setActualQty(new BigDecimal("80.0000"));
        service.updateItems(st.getId(), FACTORY_ID, List.of(u), USER_ID);

        assertThat(item.getDifferenceType()).isEqualTo(SemiFinishedStocktakeItem.DifferenceType.SHORTAGE);
        assertThat(item.getDifferenceQty().compareTo(new BigDecimal("-20.0000"))).isEqualTo(0);
        assertThat(st.getStatus()).isEqualTo(SemiFinishedStocktake.Status.COUNTING);
    }

    @Test
    @DisplayName("T4: 实盘 > 系统 → SURPLUS")
    void updateItems_surplus() {
        SemiFinishedStocktakeItem item = buildItem("100.0000");
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.INITIATED, item);
        when(stocktakeRepo.findById(st.getId())).thenReturn(Optional.of(st));
        when(stocktakeRepo.save(any())).thenReturn(st);

        SemiFinishedStocktakeItemUpdateDTO u = new SemiFinishedStocktakeItemUpdateDTO();
        u.setItemId(item.getId());
        u.setActualQty(new BigDecimal("120.0000"));
        service.updateItems(st.getId(), FACTORY_ID, List.of(u), USER_ID);

        assertThat(item.getDifferenceType()).isEqualTo(SemiFinishedStocktakeItem.DifferenceType.SURPLUS);
        assertThat(item.getDifferenceQty().compareTo(new BigDecimal("20.0000"))).isEqualTo(0);
    }

    // -------------------------------------------------------
    // 5. apply 幂等
    // -------------------------------------------------------
    @Test
    @DisplayName("T5: apply 幂等 — 已 APPLIED → 409")
    void apply_alreadyApplied_throws409() {
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.APPLIED);
        when(stocktakeRepo.findById(any())).thenReturn(Optional.of(st));
        assertThatThrownBy(() -> service.apply(st.getId(), FACTORY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(409);
        verify(sfiTxnRepo, never()).save(any());
    }

    // -------------------------------------------------------
    // 6. apply APPROVED → ADJUST txn + availableQuantity=actualQty
    // -------------------------------------------------------
    @Test
    @DisplayName("T6: apply APPROVED → ADJUST 流水 + available 校准; producedQuantity/consumedQuantity/成本 不变 (保凭证)")
    void apply_approved_writesAdjustTxn_andDoesNotTouchProducedOrCost() {
        SemiFinishedStocktakeItem item = buildItem("40.0000");
        item.setActualQty(new BigDecimal("30.0000"));
        item.setDifferenceQty(new BigDecimal("-10.0000"));
        item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SHORTAGE);
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.APPROVED, item);
        when(stocktakeRepo.findById(st.getId())).thenReturn(Optional.of(st));

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(100L).factoryId(FACTORY_ID).intermediateBatchNo(BATCH_NO)
                .producedQuantity(new BigDecimal("50.00")).consumedQuantity(new BigDecimal("10.00"))
                .availableQuantity(new BigDecimal("40.00")).unitCost(new BigDecimal("12.5000"))
                .accumulatedCost(new BigDecimal("625.00")).adjustmentQuantity(BigDecimal.ZERO)
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(sfiRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, BATCH_NO))
                .thenReturn(Optional.of(sfi));
        when(sfiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sfiTxnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stocktakeRepo.save(any())).thenReturn(st);

        service.apply(st.getId(), FACTORY_ID, USER_ID);

        // ADJUST/STOCKTAKE 流水: quantity = 真实 delta (-10), balanceAfter = 30
        ArgumentCaptor<SemiFinishedInventoryTransaction> txnCaptor =
                ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
        verify(sfiTxnRepo).save(txnCaptor.capture());
        SemiFinishedInventoryTransaction txn = txnCaptor.getValue();
        assertThat(txn.getTxnType()).isEqualTo(SemiFinishedInventoryTransaction.TxnType.ADJUST);
        assertThat(txn.getSourceType()).isEqualTo(SemiFinishedInventoryTransaction.SourceType.STOCKTAKE);
        assertThat(txn.getSourceRef()).isEqualTo(st.getId());
        assertThat(txn.getQuantity().compareTo(new BigDecimal("-10.0000"))).isEqualTo(0);
        assertThat(txn.getBalanceAfter().compareTo(new BigDecimal("30.00"))).isEqualTo(0);
        assertThat(txn.getUnitCostAtTxn().compareTo(new BigDecimal("12.5000"))).isEqualTo(0);
        assertThat(txn.getOperatorId()).isEqualTo(USER_ID);

        // 🔴 Fix 1: available=30 (40+delta), adjustment=-10; produced/consumed/成本 全部不变
        ArgumentCaptor<SemiFinishedInventory> sfiCaptor = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(sfiRepo).save(sfiCaptor.capture());
        SemiFinishedInventory saved = sfiCaptor.getValue();
        assertThat(saved.getAvailableQuantity().compareTo(new BigDecimal("30.00"))).isEqualTo(0);
        assertThat(saved.getAdjustmentQuantity().compareTo(new BigDecimal("-10.00"))).isEqualTo(0);
        // 不变式: produced − consumed + adjustment == available
        assertThat(saved.getProducedQuantity().subtract(saved.getConsumedQuantity())
                .add(saved.getAdjustmentQuantity()).compareTo(new BigDecimal("30.00"))).isEqualTo(0);
        // producedQuantity/consumedQuantity/accumulatedCost/unitCost UNCHANGED (喂凭证+成本)
        assertThat(saved.getProducedQuantity().compareTo(new BigDecimal("50.00"))).isEqualTo(0);
        assertThat(saved.getConsumedQuantity().compareTo(new BigDecimal("10.00"))).isEqualTo(0);
        assertThat(saved.getAccumulatedCost().compareTo(new BigDecimal("625.00"))).isEqualTo(0);
        assertThat(saved.getUnitCost().compareTo(new BigDecimal("12.5000"))).isEqualTo(0);
        assertThat(saved.getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);

        // 🔴 成本传导 item(d): 在手 VALUE = availableQuantity × unitCost 随盘亏自动下降 (40×12.5=500 → 30×12.5=375);
        //   而 accumulatedCost (=producedQuantity×unitCost 口径, 625) 保持不变 (历史入库值, 非在手价值)。
        //   → 消费者以 available×unitCost 估在手价值即正确反映盘亏; 直接读 accumulatedCost 会高估 (over-value)。
        BigDecimal onHandValue = saved.getAvailableQuantity().multiply(saved.getUnitCost());
        assertThat(onHandValue.compareTo(new BigDecimal("375.0000"))).isEqualTo(0);   // 盘亏后在手价值下降
        assertThat(saved.getAccumulatedCost().compareTo(new BigDecimal("375.00"))).isNotEqualTo(0); // ≠ 在手价值
        assertThat(saved.getProducedQuantity().multiply(saved.getUnitCost())
                .compareTo(new BigDecimal("625.0000"))).isEqualTo(0); // produced×unitCost 不变 (盘亏不动 produced)

        // stocktake → APPLIED
        ArgumentCaptor<SemiFinishedStocktake> stCaptor = ArgumentCaptor.forClass(SemiFinishedStocktake.class);
        verify(stocktakeRepo, atLeastOnce()).save(stCaptor.capture());
        assertThat(stCaptor.getAllValues().stream()
                .anyMatch(s -> s.getStatus() == SemiFinishedStocktake.Status.APPLIED)).isTrue();
    }

    @Test
    @DisplayName("T6b: apply available 归零→DEPLETED (actualQty=0); produced 不变")
    void apply_zeroActual_setsDepleted() {
        SemiFinishedStocktakeItem item = buildItem("40.0000");
        item.setActualQty(new BigDecimal("0.0000"));
        item.setDifferenceQty(new BigDecimal("-40.0000"));
        item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SHORTAGE);
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.APPROVED, item);
        when(stocktakeRepo.findById(st.getId())).thenReturn(Optional.of(st));

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(100L).factoryId(FACTORY_ID).intermediateBatchNo(BATCH_NO)
                .producedQuantity(new BigDecimal("40.00")).consumedQuantity(new BigDecimal("0.00"))
                .availableQuantity(new BigDecimal("40.00")).adjustmentQuantity(BigDecimal.ZERO)
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(sfiRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, BATCH_NO))
                .thenReturn(Optional.of(sfi));
        when(sfiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sfiTxnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stocktakeRepo.save(any())).thenReturn(st);

        service.apply(st.getId(), FACTORY_ID, USER_ID);

        ArgumentCaptor<SemiFinishedInventory> sfiCaptor = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(sfiRepo).save(sfiCaptor.capture());
        assertThat(sfiCaptor.getValue().getAvailableQuantity().compareTo(BigDecimal.ZERO)).isEqualTo(0);
        assertThat(sfiCaptor.getValue().getProducedQuantity().compareTo(new BigDecimal("40.00"))).isEqualTo(0); // 不变
        assertThat(sfiCaptor.getValue().getAdjustmentQuantity().compareTo(new BigDecimal("-40.00"))).isEqualTo(0);
        assertThat(sfiCaptor.getValue().getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);
    }

    @Test
    @DisplayName("T6c: delta 语义 — count 与 apply 之间的并发产出不丢 (available=current+delta, 非绝对-set)")
    void apply_deltaSemantics_preservesConcurrentProduction() {
        // 快照时 available=40 → systemQty=40; 仓管点数 35 → delta = -5
        SemiFinishedStocktakeItem item = buildItem("40.0000");
        item.setActualQty(new BigDecimal("35.0000"));
        item.setDifferenceQty(new BigDecimal("-5.0000"));
        item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SHORTAGE);
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.APPROVED, item);
        when(stocktakeRepo.findById(st.getId())).thenReturn(Optional.of(st));

        // 审批期间又产出 +10 (produced 50→60, available 40→50)
        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(100L).factoryId(FACTORY_ID).intermediateBatchNo(BATCH_NO)
                .producedQuantity(new BigDecimal("60.00")).consumedQuantity(new BigDecimal("10.00"))
                .availableQuantity(new BigDecimal("50.00")).adjustmentQuantity(BigDecimal.ZERO)
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(sfiRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, BATCH_NO))
                .thenReturn(Optional.of(sfi));
        when(sfiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sfiTxnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stocktakeRepo.save(any())).thenReturn(st);

        service.apply(st.getId(), FACTORY_ID, USER_ID);

        ArgumentCaptor<SemiFinishedInventory> sfiCaptor = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(sfiRepo).save(sfiCaptor.capture());
        SemiFinishedInventory saved = sfiCaptor.getValue();
        // delta: 50 + (-5) = 45 (NOT 35 = 绝对-set to actualQty); 并发 +10 产出被保留
        assertThat(saved.getAvailableQuantity().compareTo(new BigDecimal("45.00"))).isEqualTo(0);
        assertThat(saved.getProducedQuantity().compareTo(new BigDecimal("60.00"))).isEqualTo(0); // 不变
        assertThat(saved.getAdjustmentQuantity().compareTo(new BigDecimal("-5.00"))).isEqualTo(0);
        // 不变式
        assertThat(saved.getProducedQuantity().subtract(saved.getConsumedQuantity())
                .add(saved.getAdjustmentQuantity()).compareTo(new BigDecimal("45.00"))).isEqualTo(0);

        ArgumentCaptor<SemiFinishedInventoryTransaction> txnCaptor =
                ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
        verify(sfiTxnRepo).save(txnCaptor.capture());
        assertThat(txnCaptor.getValue().getQuantity().compareTo(new BigDecimal("-5.0000"))).isEqualTo(0);
        assertThat(txnCaptor.getValue().getBalanceAfter().compareTo(new BigDecimal("45.00"))).isEqualTo(0);
    }

    @Test
    @DisplayName("T6d: 双次 apply — 第二次 409, ADJUST 流水恰好写一次 (幂等防重复过账)")
    void apply_doubleApply_writesExactlyOneAdjustTxn() {
        SemiFinishedStocktakeItem item = buildItem("40.0000");
        item.setActualQty(new BigDecimal("30.0000"));
        item.setDifferenceQty(new BigDecimal("-10.0000"));
        item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SHORTAGE);
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.APPROVED, item);
        when(stocktakeRepo.findById(st.getId())).thenReturn(Optional.of(st)); // 同对象 → 第一次后置 APPLIED

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(100L).factoryId(FACTORY_ID).intermediateBatchNo(BATCH_NO)
                .producedQuantity(new BigDecimal("50.00")).consumedQuantity(new BigDecimal("10.00"))
                .availableQuantity(new BigDecimal("40.00")).adjustmentQuantity(BigDecimal.ZERO)
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(sfiRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, BATCH_NO))
                .thenReturn(Optional.of(sfi));
        when(sfiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sfiTxnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stocktakeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.apply(st.getId(), FACTORY_ID, USER_ID); // 第一次成功 → APPLIED
        assertThatThrownBy(() -> service.apply(st.getId(), FACTORY_ID, USER_ID)) // 第二次
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(409);

        // ADJUST 流水恰好一次 (状态守卫 + @Version 兜真并发)
        verify(sfiTxnRepo, times(1)).save(any());
    }

    // -------------------------------------------------------
    // 6.5 apply 财务过账 (盘盈=收入 / 盘亏=损耗)
    // -------------------------------------------------------
    @Test
    @DisplayName("TV1: net 盘亏 (costed) → 过账盘亏损耗凭证 借6602.01管理费用-损耗/贷1405库存商品, value=Σ|diff×unitCost|")
    void apply_netShortage_postsLossVoucher() {
        SemiFinishedStocktakeItem item = buildItem("40.0000");
        item.setActualQty(new BigDecimal("30.0000"));
        item.setDifferenceQty(new BigDecimal("-10.0000"));
        item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SHORTAGE);
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.APPROVED, item);
        when(stocktakeRepo.findById(st.getId())).thenReturn(Optional.of(st));

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(100L).factoryId(FACTORY_ID).intermediateBatchNo(BATCH_NO)
                .producedQuantity(new BigDecimal("50.00")).consumedQuantity(new BigDecimal("10.00"))
                .availableQuantity(new BigDecimal("40.00")).unitCost(new BigDecimal("12.5000"))
                .adjustmentQuantity(BigDecimal.ZERO).status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(sfiRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, BATCH_NO))
                .thenReturn(Optional.of(sfi));
        when(sfiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sfiTxnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stocktakeRepo.save(any())).thenReturn(st);

        service.apply(st.getId(), FACTORY_ID, USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoucherEntrySpec>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createManual(eq(FACTORY_ID), eq(VoucherType.INVENTORY_STOCKTAKE),
                any(), entriesCaptor.capture(), eq("SEMI_FINISHED_STOCKTAKE"), eq(st.getId()),
                anyString(), eq(USER_ID));
        List<VoucherEntrySpec> entries = entriesCaptor.getValue();
        assertThat(entries).hasSize(2);
        // 借 6602.01 管理费用-损耗 = 125.00 (= |−10 × 12.5|)
        assertThat(entries.get(0).subjectCode()).isEqualTo("6602.01");
        assertThat(entries.get(0).subjectName()).isEqualTo("管理费用-损耗");
        assertThat(entries.get(0).debit().compareTo(new BigDecimal("125.00"))).isEqualTo(0);
        assertThat(entries.get(0).credit()).isNull();
        // 贷 1405 库存商品 = 125.00
        assertThat(entries.get(1).subjectCode()).isEqualTo("1405");
        assertThat(entries.get(1).subjectName()).isEqualTo("库存商品");
        assertThat(entries.get(1).credit().compareTo(new BigDecimal("125.00"))).isEqualTo(0);
        assertThat(entries.get(1).debit()).isNull();
    }

    @Test
    @DisplayName("TV2: net 盘盈 (costed) → 过账盘盈收入凭证 借1405库存商品/贷6301营业外收入")
    void apply_netSurplus_postsIncomeVoucher() {
        SemiFinishedStocktakeItem item = buildItem("40.0000");
        item.setActualQty(new BigDecimal("48.0000"));
        item.setDifferenceQty(new BigDecimal("8.0000"));
        item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SURPLUS);
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.APPROVED, item);
        when(stocktakeRepo.findById(st.getId())).thenReturn(Optional.of(st));

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(100L).factoryId(FACTORY_ID).intermediateBatchNo(BATCH_NO)
                .producedQuantity(new BigDecimal("50.00")).consumedQuantity(new BigDecimal("10.00"))
                .availableQuantity(new BigDecimal("40.00")).unitCost(new BigDecimal("10.0000"))
                .adjustmentQuantity(BigDecimal.ZERO).status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(sfiRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, BATCH_NO))
                .thenReturn(Optional.of(sfi));
        when(sfiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sfiTxnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stocktakeRepo.save(any())).thenReturn(st);

        service.apply(st.getId(), FACTORY_ID, USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoucherEntrySpec>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createManual(eq(FACTORY_ID), eq(VoucherType.INVENTORY_STOCKTAKE),
                any(), entriesCaptor.capture(), eq("SEMI_FINISHED_STOCKTAKE"), eq(st.getId()),
                anyString(), eq(USER_ID));
        List<VoucherEntrySpec> entries = entriesCaptor.getValue();
        assertThat(entries).hasSize(2);
        // 借 1405 库存商品 = 80.00 (= 8 × 10)
        assertThat(entries.get(0).subjectCode()).isEqualTo("1405");
        assertThat(entries.get(0).subjectName()).isEqualTo("库存商品");
        assertThat(entries.get(0).debit().compareTo(new BigDecimal("80.00"))).isEqualTo(0);
        assertThat(entries.get(0).credit()).isNull();
        // 贷 6301 营业外收入 = 80.00
        assertThat(entries.get(1).subjectCode()).isEqualTo("6301");
        assertThat(entries.get(1).subjectName()).isEqualTo("营业外收入");
        assertThat(entries.get(1).credit().compareTo(new BigDecimal("80.00"))).isEqualTo(0);
        assertThat(entries.get(1).debit()).isNull();
    }

    @Test
    @DisplayName("TV3: 未计成本(unitCost=null)差异行 — 排除出凭证但数量仍调整; 与已计成本行混合 → 凭证仅含已计成本")
    void apply_uncostedItem_excludedFromVoucher_butQtyStillAdjusted() {
        // item1: 已计成本盘亏 diff −4, unitCost 5 → 损耗 20.00
        SemiFinishedStocktakeItem costed = buildItem("40.0000");
        costed.setActualQty(new BigDecimal("36.0000"));
        costed.setDifferenceQty(new BigDecimal("-4.0000"));
        costed.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SHORTAGE);
        // item2: 未计成本 (unitCost=null) 盘亏 diff −3 → 排除出凭证, 但数量仍调整
        SemiFinishedStocktakeItem uncosted = new SemiFinishedStocktakeItem();
        uncosted.setId(UUID.randomUUID().toString());
        uncosted.setSemiFinishedId(101L);
        uncosted.setIntermediateBatchNo(BATCH_NO_2);
        uncosted.setSystemQty(new BigDecimal("20.0000"));
        uncosted.setActualQty(new BigDecimal("17.0000"));
        uncosted.setDifferenceQty(new BigDecimal("-3.0000"));
        uncosted.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SHORTAGE);

        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.APPROVED, costed, uncosted);
        when(stocktakeRepo.findById(st.getId())).thenReturn(Optional.of(st));

        SemiFinishedInventory sfiCosted = SemiFinishedInventory.builder()
                .id(100L).factoryId(FACTORY_ID).intermediateBatchNo(BATCH_NO)
                .producedQuantity(new BigDecimal("50.00")).consumedQuantity(new BigDecimal("10.00"))
                .availableQuantity(new BigDecimal("40.00")).unitCost(new BigDecimal("5.0000"))
                .adjustmentQuantity(BigDecimal.ZERO).status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        SemiFinishedInventory sfiUncosted = SemiFinishedInventory.builder()
                .id(101L).factoryId(FACTORY_ID).intermediateBatchNo(BATCH_NO_2)
                .producedQuantity(new BigDecimal("20.00")).consumedQuantity(new BigDecimal("0.00"))
                .availableQuantity(new BigDecimal("20.00")).unitCost(null)  // 未计成本
                .adjustmentQuantity(BigDecimal.ZERO).status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(sfiRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, BATCH_NO))
                .thenReturn(Optional.of(sfiCosted));
        when(sfiRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, BATCH_NO_2))
                .thenReturn(Optional.of(sfiUncosted));
        when(sfiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sfiTxnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stocktakeRepo.save(any())).thenReturn(st);

        service.apply(st.getId(), FACTORY_ID, USER_ID);

        // 两行数量都调整 (库存 save + ADJUST 流水 各 2 次) — 未计成本行数量不受影响
        verify(sfiRepo, times(2)).save(any());
        verify(sfiTxnRepo, times(2)).save(any());

        // 凭证仅含已计成本盘亏 20.00 (未计成本行被排除, 绝不臆造价值)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoucherEntrySpec>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createManual(eq(FACTORY_ID), eq(VoucherType.INVENTORY_STOCKTAKE),
                any(), entriesCaptor.capture(), eq("SEMI_FINISHED_STOCKTAKE"), eq(st.getId()),
                anyString(), eq(USER_ID));
        List<VoucherEntrySpec> entries = entriesCaptor.getValue();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).subjectCode()).isEqualTo("6602.01");
        assertThat(entries.get(0).debit().compareTo(new BigDecimal("20.00"))).isEqualTo(0);
        assertThat(entries.get(1).subjectCode()).isEqualTo("1405");
        assertThat(entries.get(1).credit().compareTo(new BigDecimal("20.00"))).isEqualTo(0);
    }

    @Test
    @DisplayName("TV4: 全部差异行未计成本 → 不过账凭证 (仅 warn), 但数量仍全部调整")
    void apply_allUncosted_noVoucher_butQtyAdjusted() {
        SemiFinishedStocktakeItem item = buildItem("40.0000");
        item.setActualQty(new BigDecimal("35.0000"));
        item.setDifferenceQty(new BigDecimal("-5.0000"));
        item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SHORTAGE);
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.APPROVED, item);
        when(stocktakeRepo.findById(st.getId())).thenReturn(Optional.of(st));

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(100L).factoryId(FACTORY_ID).intermediateBatchNo(BATCH_NO)
                .producedQuantity(new BigDecimal("50.00")).consumedQuantity(new BigDecimal("10.00"))
                .availableQuantity(new BigDecimal("40.00")).unitCost(null)  // 未计成本
                .adjustmentQuantity(BigDecimal.ZERO).status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(sfiRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, BATCH_NO))
                .thenReturn(Optional.of(sfi));
        when(sfiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sfiTxnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stocktakeRepo.save(any())).thenReturn(st);

        service.apply(st.getId(), FACTORY_ID, USER_ID);

        // 数量仍调整 (库存 save + ADJUST 流水)
        verify(sfiRepo).save(any());
        verify(sfiTxnRepo).save(any());
        // 无可估值差异 → 不过账凭证
        verify(voucherService, never()).createManual(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("TV5: 盘亏 |delta|>currentAvail (并发耗尽 → 重读余量已低于快照) — clamp 到 0; "
            + "ADJUST txn 与凭证按 realizedDelta(=−currentAvail) 过账, 不高估损耗, 借=贷平")
    void apply_shortageExceedsAvail_booksRealizedDelta_notRawDelta() {
        // 快照 systemQty=40, 仓管点数 5 → delta = −35 (盘亏 35)
        SemiFinishedStocktakeItem item = buildItem("40.0000");
        item.setActualQty(new BigDecimal("5.0000"));
        item.setDifferenceQty(new BigDecimal("-35.0000"));
        item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SHORTAGE);
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.APPROVED, item);
        when(stocktakeRepo.findById(st.getId())).thenReturn(Optional.of(st));

        // 审批期间被并发领用耗尽: 重读时 available 只剩 20 (< |delta|=35)。unitCost=8。
        //   currentAvail=20, delta=−35 → newAvail=max(0, 20−35)=0 → realizedDelta = 0−20 = −20。
        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(100L).factoryId(FACTORY_ID).intermediateBatchNo(BATCH_NO)
                .producedQuantity(new BigDecimal("60.00")).consumedQuantity(new BigDecimal("40.00"))
                .availableQuantity(new BigDecimal("20.00")).unitCost(new BigDecimal("8.0000"))
                .adjustmentQuantity(BigDecimal.ZERO).status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(sfiRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, BATCH_NO))
                .thenReturn(Optional.of(sfi));
        when(sfiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sfiTxnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stocktakeRepo.save(any())).thenReturn(st);

        service.apply(st.getId(), FACTORY_ID, USER_ID);

        // 库存 clamp 到 0 → DEPLETED
        ArgumentCaptor<SemiFinishedInventory> sfiCaptor = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(sfiRepo).save(sfiCaptor.capture());
        assertThat(sfiCaptor.getValue().getAvailableQuantity().compareTo(BigDecimal.ZERO)).isEqualTo(0);
        assertThat(sfiCaptor.getValue().getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);

        // ADJUST txn 按 realizedDelta=−20 (NOT raw −35); 台账不变式 balanceAfter=balanceBefore+quantity
        ArgumentCaptor<SemiFinishedInventoryTransaction> txnCaptor =
                ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
        verify(sfiTxnRepo).save(txnCaptor.capture());
        assertThat(txnCaptor.getValue().getQuantity().compareTo(new BigDecimal("-20.00"))).isEqualTo(0);
        assertThat(txnCaptor.getValue().getBalanceAfter().compareTo(BigDecimal.ZERO)).isEqualTo(0);

        // 凭证按 realizedDelta 估值: 盘亏 = |−20 × 8| = 160.00 (NOT |−35 × 8| = 280) → 不高估损耗
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoucherEntrySpec>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createManual(eq(FACTORY_ID), eq(VoucherType.INVENTORY_STOCKTAKE),
                any(), entriesCaptor.capture(), eq("SEMI_FINISHED_STOCKTAKE"), eq(st.getId()),
                anyString(), eq(USER_ID));
        List<VoucherEntrySpec> entries = entriesCaptor.getValue();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).subjectCode()).isEqualTo("6602.01");
        assertThat(entries.get(0).debit().compareTo(new BigDecimal("160.00"))).isEqualTo(0);
        assertThat(entries.get(1).subjectCode()).isEqualTo("1405");
        assertThat(entries.get(1).credit().compareTo(new BigDecimal("160.00"))).isEqualTo(0);
        // 借=贷平
        assertThat(entries.get(0).debit().compareTo(entries.get(1).credit())).isEqualTo(0);
    }

    // -------------------------------------------------------
    // 7. apply 非 APPROVED → 409
    // -------------------------------------------------------
    @Test
    @DisplayName("T7: apply 非 APPROVED (PENDING_APPROVAL) → 409, 不写库存")
    void apply_notApproved_throws409() {
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.PENDING_APPROVAL);
        when(stocktakeRepo.findById(any())).thenReturn(Optional.of(st));
        assertThatThrownBy(() -> service.apply(st.getId(), FACTORY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(409);
        verify(sfiTxnRepo, never()).save(any());
        verify(sfiRepo, never()).save(any());
    }

    // -------------------------------------------------------
    // 8. 跨租户隔离
    // -------------------------------------------------------
    @Test
    @DisplayName("T8: apply 跨租户 factoryId 不符 → 403")
    void apply_wrongFactory_throws403() {
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.APPROVED);
        when(stocktakeRepo.findById(any())).thenReturn(Optional.of(st));
        assertThatThrownBy(() -> service.apply(st.getId(), OTHER_FACTORY, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(403);
    }

    // -------------------------------------------------------
    // 9. approve 角色守卫
    // -------------------------------------------------------
    @Test
    @DisplayName("T9: approve 错误角色 → 403")
    void approve_wrongRole_throws403() {
        assertThatThrownBy(() -> service.approve("any-id", FACTORY_ID, USER_ID, "warehouse_worker"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("T10: approve operator → 403")
    void approve_operator_throws403() {
        assertThatThrownBy(() -> service.approve("any-id", FACTORY_ID, USER_ID, "operator"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("T11: approve finance_manager → APPROVED")
    void approve_financeManager_succeeds() {
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.PENDING_APPROVAL);
        when(stocktakeRepo.findById(any())).thenReturn(Optional.of(st));
        when(stocktakeRepo.save(any())).thenReturn(st);
        service.approve(st.getId(), FACTORY_ID, USER_ID, "finance_manager");
        assertThat(st.getStatus()).isEqualTo(SemiFinishedStocktake.Status.APPROVED);
    }

    @Test
    @DisplayName("T12: approve factory_super_admin → APPROVED")
    void approve_factorySuperAdmin_succeeds() {
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.PENDING_APPROVAL);
        when(stocktakeRepo.findById(any())).thenReturn(Optional.of(st));
        when(stocktakeRepo.save(any())).thenReturn(st);
        service.approve(st.getId(), FACTORY_ID, USER_ID, "factory_super_admin");
        assertThat(st.getStatus()).isEqualTo(SemiFinishedStocktake.Status.APPROVED);
    }

    @Test
    @DisplayName("T13: approve 大写兜底 FINANCE_MANAGER → APPROVED")
    void approve_uppercase_succeeds() {
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.PENDING_APPROVAL);
        when(stocktakeRepo.findById(any())).thenReturn(Optional.of(st));
        when(stocktakeRepo.save(any())).thenReturn(st);
        service.approve(st.getId(), FACTORY_ID, USER_ID, "FINANCE_MANAGER");
        assertThat(st.getStatus()).isEqualTo(SemiFinishedStocktake.Status.APPROVED);
    }

    @Test
    @DisplayName("T14: approve 跨租户 → 403 (角色对但 factory 不符)")
    void approve_wrongFactory_throws403() {
        SemiFinishedStocktake st = buildStocktake(SemiFinishedStocktake.Status.PENDING_APPROVAL);
        when(stocktakeRepo.findById(any())).thenReturn(Optional.of(st));
        assertThatThrownBy(() -> service.approve(st.getId(), OTHER_FACTORY, USER_ID, "finance_manager"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo(403);
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    private SemiFinishedStocktake buildStocktake(SemiFinishedStocktake.Status status, SemiFinishedStocktakeItem... items) {
        SemiFinishedStocktake st = new SemiFinishedStocktake();
        st.setId(UUID.randomUUID().toString());
        st.setFactoryId(FACTORY_ID);
        st.setStocktakeNo("SFST-202606-ABCD1234");
        st.setPeriodMonth("2026-06");
        st.setStatus(status);
        List<SemiFinishedStocktakeItem> list = new ArrayList<>();
        for (SemiFinishedStocktakeItem it : items) {
            it.setStocktake(st);
            list.add(it);
        }
        st.setItems(list);
        return st;
    }

    private SemiFinishedStocktakeItem buildItem(String systemQty) {
        SemiFinishedStocktakeItem item = new SemiFinishedStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setSemiFinishedId(100L);
        item.setIntermediateBatchNo(BATCH_NO);
        item.setSystemQty(new BigDecimal(systemQty));
        item.setActualQty(new BigDecimal(systemQty));
        item.setDifferenceQty(BigDecimal.ZERO);
        item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.MATCH);
        return item;
    }
}
