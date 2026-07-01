package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateSemiFinishedStocktakeRequest;
import com.cretas.aims.dto.factory.SemiFinishedStocktakeItemUpdateDTO;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.factory.SemiFinishedStocktake;
import com.cretas.aims.entity.factory.SemiFinishedStocktakeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeItemRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks private SemiFinishedStocktakeServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String OTHER_FACTORY = "F999";
    private static final Long USER_ID = 42L;
    private static final String BATCH_NO = "SEMI-2026-0001";

    // -------------------------------------------------------
    // 1. 月底约束
    // -------------------------------------------------------
    @Test
    @DisplayName("T1: threshold=29 时 day<29 → 409 含下次可发起日期; day>=29 放行")
    void initiate_threshold29_monthEndGuard() {
        ReflectionTestUtils.setField(service, "monthEndThreshold", 29);
        CreateSemiFinishedStocktakeRequest req = new CreateSemiFinishedStocktakeRequest();
        req.setPeriodMonth("2026-06");

        int todayDay = LocalDateTime.now().getDayOfMonth();
        if (todayDay >= 29) {
            when(stocktakeRepo.countActiveStocktakeForMonth(any(), any())).thenReturn(0L);
            when(sfiRepo.findByFactoryIdAndStatusForStocktake(any(), any())).thenReturn(Collections.emptyList());
            when(stocktakeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            service.initiate(FACTORY_ID, req, USER_ID);
            return;
        }
        assertThatThrownBy(() -> service.initiate(FACTORY_ID, req, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getMessage()).contains("月底");
                    assertThat(be.getMessage()).contains("下次可发起日期");
                });
    }

    @Test
    @DisplayName("T1b: threshold=1 → 放行, 快照 AVAILABLE 半成品行 (systemQty=availableQuantity)")
    void initiate_threshold1_snapshotsAvailableRows() {
        ReflectionTestUtils.setField(service, "monthEndThreshold", 1);
        CreateSemiFinishedStocktakeRequest req = new CreateSemiFinishedStocktakeRequest();
        req.setPeriodMonth("2026-06");

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(100L).factoryId(FACTORY_ID).intermediateBatchNo(BATCH_NO)
                .productTypeId("PT-1").unit("kg")
                .producedQuantity(new BigDecimal("50.00")).consumedQuantity(new BigDecimal("10.00"))
                .availableQuantity(new BigDecimal("40.00")).status(SemiFinishedInventory.Status.AVAILABLE)
                .build();

        when(stocktakeRepo.countActiveStocktakeForMonth(any(), any())).thenReturn(0L);
        when(sfiRepo.findByFactoryIdAndStatusForStocktake(FACTORY_ID, SemiFinishedInventory.Status.AVAILABLE))
                .thenReturn(List.of(sfi));
        when(stocktakeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

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
    // 2. duplicate check
    // -------------------------------------------------------
    @Test
    @DisplayName("T2: 同工厂同月已有活跃盘点 → 409")
    void initiate_duplicate_throws409() {
        ReflectionTestUtils.setField(service, "monthEndThreshold", 1);
        CreateSemiFinishedStocktakeRequest req = new CreateSemiFinishedStocktakeRequest();
        req.setPeriodMonth("2026-06");
        when(stocktakeRepo.countActiveStocktakeForMonth(eq(FACTORY_ID), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.initiate(FACTORY_ID, req, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有进行中");
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
    @DisplayName("T6: apply APPROVED → 写 ADJUST/STOCKTAKE 流水 + availableQuantity=actualQty + produced=actual+consumed")
    void apply_approved_writesAdjustTxn_andCalibratesSfi() {
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
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(sfiRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, BATCH_NO))
                .thenReturn(Optional.of(sfi));
        when(sfiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sfiTxnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stocktakeRepo.save(any())).thenReturn(st);

        service.apply(st.getId(), FACTORY_ID, USER_ID);

        // ADJUST/STOCKTAKE 流水
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

        // SFI 校准: available=actualQty(30), produced=actual+consumed(40)
        ArgumentCaptor<SemiFinishedInventory> sfiCaptor = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(sfiRepo).save(sfiCaptor.capture());
        SemiFinishedInventory saved = sfiCaptor.getValue();
        assertThat(saved.getAvailableQuantity().compareTo(new BigDecimal("30.00"))).isEqualTo(0);
        assertThat(saved.getProducedQuantity().compareTo(new BigDecimal("40.00"))).isEqualTo(0);
        assertThat(saved.getConsumedQuantity().compareTo(new BigDecimal("10.00"))).isEqualTo(0);
        assertThat(saved.getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);

        // stocktake → APPLIED
        ArgumentCaptor<SemiFinishedStocktake> stCaptor = ArgumentCaptor.forClass(SemiFinishedStocktake.class);
        verify(stocktakeRepo, atLeastOnce()).save(stCaptor.capture());
        assertThat(stCaptor.getAllValues().stream()
                .anyMatch(s -> s.getStatus() == SemiFinishedStocktake.Status.APPLIED)).isTrue();
    }

    @Test
    @DisplayName("T6b: apply SURPLUS 且 available 归零→DEPLETED 语义 (actualQty=0)")
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
                .availableQuantity(new BigDecimal("40.00")).status(SemiFinishedInventory.Status.AVAILABLE)
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
        assertThat(sfiCaptor.getValue().getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);
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
