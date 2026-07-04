package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.InternalTransferItemRepository;
import com.cretas.aims.service.inventory.impl.FinishedGoodsFeedServiceImpl;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 撤销小结 FG 原语单测: reverseInterimCreate (成品入库冲销 + 下游守卫) + restoreForFeed (成品投料还回)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FinishedGoodsFeedReverseTest - FG 入库冲销 / 投料还回")
class FinishedGoodsFeedReverseTest {

    private static final String FACTORY = "F006";
    private static final String BATCH = "FG-PP-001-S2";

    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ProductFamilyResolver productFamilyResolver;
    @Mock private FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;
    @Mock private InternalTransferItemRepository internalTransferItemRepository;
    @InjectMocks private FinishedGoodsFeedServiceImpl service;

    private FinishedGoodsBatch fg(BigDecimal produced, BigDecimal shipped, BigDecimal reserved) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId("fg-id");
        b.setFactoryId(FACTORY);
        b.setBatchNumber(BATCH);
        b.setProductTypeId("PT1");
        b.setProducedQuantity(produced);
        b.setShippedQuantity(shipped);
        b.setReservedQuantity(reserved);
        b.setUnit("kg");
        b.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        return b;
    }

    private FinishedGoodsBatch childFg(String id, BigDecimal produced, BigDecimal shipped, BigDecimal reserved) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId(id);
        b.setFactoryId(FACTORY);
        b.setBatchNumber("TRF-FG-" + id);
        b.setProductTypeId("PT1");
        b.setProducedQuantity(produced);
        b.setShippedQuantity(shipped);
        b.setReservedQuantity(reserved);
        b.setUnit("kg");
        b.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        return b;
    }

    /** 同厂调拨行 (sourceFactory == targetFactory), 消费源批次 fg-id. */
    private InternalTransferItem intraItem(TransferStatus status, String transferNumber,
                                           String targetBatchId, BigDecimal qty) {
        InternalTransfer t = new InternalTransfer();
        t.setId("trf-" + transferNumber);
        t.setTransferNumber(transferNumber);
        t.setSourceFactoryId(FACTORY);
        t.setTargetFactoryId(FACTORY);   // 同厂
        t.setStatus(status);
        InternalTransferItem it = new InternalTransferItem();
        it.setItemType(TransferItemType.FINISHED_GOODS);
        it.setSourceBatchId("fg-id");
        it.setTargetBatchId(targetBatchId);
        it.setQuantity(qty);
        it.setTransfer(t);
        return it;
    }

    // ── reverseInterimCreate (成品入库冲销) ──

    @Test
    @DisplayName("reverseInterimCreate: 未被动过的批次 → producedQuantity 冲销至 0, 状态 REVERSED, 写调整日志")
    void reverseInterimCreateVoidsBatch() {
        FinishedGoodsBatch b = fg(new BigDecimal("9"), BigDecimal.ZERO, BigDecimal.ZERO);
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, BATCH))
                .thenReturn(Optional.of(b));

        service.reverseInterimCreate(FACTORY, BATCH, new BigDecimal("9"), 7L);

        assertThat(b.getProducedQuantity()).isEqualByComparingTo("0");
        assertThat(b.getStatus()).isEqualTo(FinishedGoodsBatch.Status.REVERSED);
        verify(finishedGoodsAdjustmentLogRepository).save(any());
    }

    @Test
    @DisplayName("reverseInterimCreate: 已发货 (shipped>0) → available_after<0 → 抛 FG_DOWNSTREAM_CONSUMED, 不改")
    void reverseInterimCreateDownstreamShippedThrows() {
        FinishedGoodsBatch b = fg(new BigDecimal("9"), new BigDecimal("5"), BigDecimal.ZERO);
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, BATCH))
                .thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.reverseInterimCreate(FACTORY, BATCH, new BigDecimal("9"), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("FG_DOWNSTREAM_CONSUMED");
                    assertThat(ex.getMessage()).contains("已发货");
                });
        assertThat(b.getProducedQuantity()).isEqualByComparingTo("9"); // 未改
        verify(finishedGoodsBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("reverseInterimCreate: 批次不存在 (已撤销/删除) → 抛 FG_NOT_FOUND")
    void reverseInterimCreateMissingThrows() {
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, BATCH))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reverseInterimCreate(FACTORY, BATCH, new BigDecimal("9"), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("FG_NOT_FOUND"));
    }

    // ── reverseInterimCreate: 同厂调拨搬出的成品仍可回收 (核心 bug 修复) ──

    @Test
    @DisplayName("reverseInterimCreate: 同厂调拨 40 至物流仓 (主批 100→60) → 撤销 100 成功: 主批退 60→0 REVERSED + 子批退 40→0 REVERSED")
    void reverseInterimCreateReclaimsIntraTransferred() {
        // 小结产 100, 同厂调拨 40 → 主批 producedQuantity=60, 物流仓 TRF-child producedQuantity=40。
        FinishedGoodsBatch main = fg(new BigDecimal("60"), BigDecimal.ZERO, BigDecimal.ZERO);
        FinishedGoodsBatch child = childFg("child-1", new BigDecimal("40"), BigDecimal.ZERO, BigDecimal.ZERO);
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, BATCH))
                .thenReturn(Optional.of(main));
        when(internalTransferItemRepository.findIntraFactoryFinishedGoodsConsumers("fg-id", FACTORY))
                .thenReturn(List.of(intraItem(TransferStatus.CONFIRMED, "TRF-1", "child-1", new BigDecimal("40"))));
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("child-1", FACTORY))
                .thenReturn(Optional.of(child));

        service.reverseInterimCreate(FACTORY, BATCH, new BigDecimal("100"), 7L);

        // 主批退回 60 (剩余量 = 100 − 子批 40), 子批退回 40, 合计 100 全撤; 无孤儿库存。
        assertThat(child.getProducedQuantity()).isEqualByComparingTo("0");
        assertThat(child.getStatus()).isEqualTo(FinishedGoodsBatch.Status.REVERSED);
        assertThat(main.getProducedQuantity()).isEqualByComparingTo("0");
        assertThat(main.getStatus()).isEqualTo(FinishedGoodsBatch.Status.REVERSED);
        // 两条调整日志 (主批 + 子批)。
        verify(finishedGoodsAdjustmentLogRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    @DisplayName("reverseInterimCreate: 全部 100 同厂调拨走 (主批 100→0) → 撤销 100 成功: 全从子批退, 主批不动")
    void reverseInterimCreateReclaimsWhenAllTransferred() {
        FinishedGoodsBatch main = fg(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        FinishedGoodsBatch child = childFg("child-2", new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO);
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, BATCH))
                .thenReturn(Optional.of(main));
        when(internalTransferItemRepository.findIntraFactoryFinishedGoodsConsumers("fg-id", FACTORY))
                .thenReturn(List.of(intraItem(TransferStatus.CONFIRMED, "TRF-2", "child-2", new BigDecimal("100"))));
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("child-2", FACTORY))
                .thenReturn(Optional.of(child));

        service.reverseInterimCreate(FACTORY, BATCH, new BigDecimal("100"), 7L);

        assertThat(child.getProducedQuantity()).isEqualByComparingTo("0");
        assertThat(child.getStatus()).isEqualTo(FinishedGoodsBatch.Status.REVERSED);
        assertThat(main.getProducedQuantity()).isEqualByComparingTo("0");  // 主批未动 (退量 = 0)
    }

    @Test
    @DisplayName("reverseInterimCreate: 子批被真实发货 10 (child.shipped>0) → 30 可回收, 主批 60 → 共 90<100 → 仍 FG_DOWNSTREAM_CONSUMED")
    void reverseInterimCreateBlocksWhenChildTrulyShipped() {
        FinishedGoodsBatch main = fg(new BigDecimal("60"), BigDecimal.ZERO, BigDecimal.ZERO);
        FinishedGoodsBatch child = childFg("child-3", new BigDecimal("40"), new BigDecimal("10"), BigDecimal.ZERO);
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, BATCH))
                .thenReturn(Optional.of(main));
        when(internalTransferItemRepository.findIntraFactoryFinishedGoodsConsumers("fg-id", FACTORY))
                .thenReturn(List.of(intraItem(TransferStatus.CONFIRMED, "TRF-3", "child-3", new BigDecimal("40"))));
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate("child-3", FACTORY))
                .thenReturn(Optional.of(child));

        assertThatThrownBy(() -> service.reverseInterimCreate(FACTORY, BATCH, new BigDecimal("100"), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("FG_DOWNSTREAM_CONSUMED");
                    assertThat(ex.getMessage()).contains("已发货");
                });
        assertThat(main.getProducedQuantity()).isEqualByComparingTo("60"); // 未改
        assertThat(child.getProducedQuantity()).isEqualByComparingTo("40"); // 未改
    }

    @Test
    @DisplayName("reverseInterimCreate: 调拨在途 (SHIPPED 未确认, 子批未建) → 主批 60<100 → block, 报错命名调拨单号")
    void reverseInterimCreateBlocksWhenInTransit() {
        FinishedGoodsBatch main = fg(new BigDecimal("60"), BigDecimal.ZERO, BigDecimal.ZERO);
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, BATCH))
                .thenReturn(Optional.of(main));
        when(internalTransferItemRepository.findIntraFactoryFinishedGoodsConsumers("fg-id", FACTORY))
                .thenReturn(List.of(intraItem(TransferStatus.SHIPPED, "TRF-INFLIGHT", null, new BigDecimal("40"))));

        assertThatThrownBy(() -> service.reverseInterimCreate(FACTORY, BATCH, new BigDecimal("100"), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("FG_DOWNSTREAM_CONSUMED");
                    assertThat(ex.getMessage()).contains("调拨在途");
                    assertThat(ex.getMessage()).contains("TRF-INFLIGHT");
                });
        assertThat(main.getProducedQuantity()).isEqualByComparingTo("60"); // 未改
    }

    // ── restoreForFeed (成品投料还回) ──

    @Test
    @DisplayName("restoreForFeed: producedQuantity 还回 (70 +30 → 100), DEPLETED → AVAILABLE, 写调整日志")
    void restoreForFeedAddsBackProduced() {
        // 小结投料时 producedQuantity 从 100 减到 70; 撤销还回 30。
        FinishedGoodsBatch b = fg(new BigDecimal("70"), BigDecimal.ZERO, BigDecimal.ZERO);
        b.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, BATCH))
                .thenReturn(Optional.of(b));

        service.restoreForFeed(FACTORY, BATCH, new BigDecimal("30"), 7L);

        assertThat(b.getProducedQuantity()).isEqualByComparingTo("100");
        assertThat(b.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        verify(finishedGoodsAdjustmentLogRepository).save(any());
    }

    @Test
    @DisplayName("restoreForFeed: 批次不存在 → 抛 FG_NOT_FOUND")
    void restoreForFeedMissingThrows() {
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, BATCH))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.restoreForFeed(FACTORY, BATCH, new BigDecimal("30"), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("FG_NOT_FOUND"));
    }
}
