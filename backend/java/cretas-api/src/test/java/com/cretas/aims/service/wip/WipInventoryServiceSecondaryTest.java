package com.cretas.aims.service.wip;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SP2 WipInventoryService — deductForSecondaryPlan + listAvailableWip 单元测试。
 *
 * @since SP2 (2026-06-10, feat/liushanmen-sp2-reversal)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SP2: WipInventoryService — 二次加工 WIP 扣减 + 列表")
class WipInventoryServiceSecondaryTest {

    private static final String FACTORY_ID = "F006";
    private static final Long WIP_ID = 55L;

    @InjectMocks
    private WipInventoryServiceImpl service;

    @Mock
    private SemiFinishedInventoryRepository wipRepo;
    @Mock
    private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock
    private ProductionReportRepository reportRepo;
    @Mock
    private BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock
    private WorkProcessTaskRepository taskRepo;
    @Mock
    private WorkProcessRepository workProcessRepo;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    // ==================== deductForSecondaryPlan ====================

    @Nested
    @DisplayName("deductForSecondaryPlan")
    class DeductForSecondaryPlan {

        private SemiFinishedInventory buildWip(BigDecimal available) {
            SemiFinishedInventory wip = new SemiFinishedInventory();
            wip.setId(WIP_ID);
            wip.setFactoryId(FACTORY_ID);
            wip.setIntermediateBatchNo("WIP-001");
            wip.setProducedQuantity(new BigDecimal("100"));
            wip.setConsumedQuantity(new BigDecimal("100").subtract(available));
            wip.setAvailableQuantity(available);
            wip.setStatus(SemiFinishedInventory.Status.AVAILABLE);
            wip.setUnitCost(new BigDecimal("10.00"));
            return wip;
        }

        @Test
        @DisplayName("正常扣减 — availableQuantity 减少，OUT/SECONDARY_CONSUME 流水已写入")
        void normalDeduct_updatesBalanceAndWritesTxn() {
            SemiFinishedInventory wip = buildWip(new BigDecimal("80"));
            when(wipRepo.findByIdForUpdate(WIP_ID)).thenReturn(Optional.of(wip));
            when(wipRepo.save(any())).thenReturn(wip);

            service.deductForSecondaryPlan(WIP_ID, new BigDecimal("30"), FACTORY_ID, 7L);

            // availableQuantity 应为 50
            ArgumentCaptor<SemiFinishedInventory> wipCaptor = ArgumentCaptor.forClass(SemiFinishedInventory.class);
            verify(wipRepo).save(wipCaptor.capture());
            assertThat(wipCaptor.getValue().getAvailableQuantity()).isEqualByComparingTo("50");

            // 写 OUT 流水
            ArgumentCaptor<SemiFinishedInventoryTransaction> txnCaptor =
                    ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
            verify(txnRepo).save(txnCaptor.capture());
            assertThat(txnCaptor.getValue().getTxnType())
                    .isEqualTo(SemiFinishedInventoryTransaction.TxnType.OUT);
            assertThat(txnCaptor.getValue().getSourceType())
                    .isEqualTo(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME);
            // OUT quantity should be negative
            assertThat(txnCaptor.getValue().getQuantity()).isNegative();
        }

        @Test
        @DisplayName("扣减恰好全部 — 状态变 DEPLETED")
        void exactDeductAll_statusBecomeDepleted() {
            SemiFinishedInventory wip = buildWip(new BigDecimal("50"));
            when(wipRepo.findByIdForUpdate(WIP_ID)).thenReturn(Optional.of(wip));
            when(wipRepo.save(any())).thenReturn(wip);

            service.deductForSecondaryPlan(WIP_ID, new BigDecimal("50"), FACTORY_ID, null);

            ArgumentCaptor<SemiFinishedInventory> captor = ArgumentCaptor.forClass(SemiFinishedInventory.class);
            verify(wipRepo).save(captor.capture());
            assertThat(captor.getValue().getAvailableQuantity()).isEqualByComparingTo("0");
            assertThat(captor.getValue().getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);
        }

        @Test
        @DisplayName("WIP 不存在 → 404")
        void wipNotFound_throws404() {
            when(wipRepo.findByIdForUpdate(WIP_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deductForSecondaryPlan(WIP_ID, new BigDecimal("10"), FACTORY_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("半成品库存行不存在");
        }

        @Test
        @DisplayName("工厂 ID 不匹配 → 403")
        void factoryMismatch_throws403() {
            SemiFinishedInventory wip = buildWip(new BigDecimal("80"));
            wip.setFactoryId("DIFFERENT_FACTORY");
            when(wipRepo.findByIdForUpdate(WIP_ID)).thenReturn(Optional.of(wip));

            assertThatThrownBy(() -> service.deductForSecondaryPlan(WIP_ID, new BigDecimal("10"), FACTORY_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("工厂 ID 不匹配");
        }

        @Test
        @DisplayName("扣减量超出余量 → 409 WIP_INSUFFICIENT")
        void exceedsAvailable_throws409() {
            SemiFinishedInventory wip = buildWip(new BigDecimal("20"));
            when(wipRepo.findByIdForUpdate(WIP_ID)).thenReturn(Optional.of(wip));

            assertThatThrownBy(() -> service.deductForSecondaryPlan(WIP_ID, new BigDecimal("30"), FACTORY_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("WIP 余量不足");
        }

        @Test
        @DisplayName("扣减量为 0 → 400")
        void zeroQty_throws400() {
            assertThatThrownBy(() -> service.deductForSecondaryPlan(WIP_ID, BigDecimal.ZERO, FACTORY_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("扣减数量必须大于 0");
            verify(wipRepo, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("扣减量为 null → 400")
        void nullQty_throws400() {
            assertThatThrownBy(() -> service.deductForSecondaryPlan(WIP_ID, null, FACTORY_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("扣减数量必须大于 0");
        }
    }

    // ==================== listAvailableWip ====================

    @Nested
    @DisplayName("listAvailableWip")
    class ListAvailableWip {

        @Test
        @DisplayName("返回 repo 结果列表")
        void returnsRepoResult() {
            SemiFinishedInventory wip1 = new SemiFinishedInventory();
            SemiFinishedInventory wip2 = new SemiFinishedInventory();
            when(wipRepo.findAvailableByFactory(FACTORY_ID)).thenReturn(List.of(wip1, wip2));

            List<SemiFinishedInventory> result = service.listAvailableWip(FACTORY_ID);
            assertThat(result).hasSize(2);
            verify(wipRepo).findAvailableByFactory(FACTORY_ID);
        }

        @Test
        @DisplayName("无可用 WIP → 返回空 list (不抛异常)")
        void noWip_returnsEmptyList() {
            when(wipRepo.findAvailableByFactory(FACTORY_ID)).thenReturn(List.of());

            List<SemiFinishedInventory> result = service.listAvailableWip(FACTORY_ID);
            assertThat(result).isEmpty();
        }
    }
}
