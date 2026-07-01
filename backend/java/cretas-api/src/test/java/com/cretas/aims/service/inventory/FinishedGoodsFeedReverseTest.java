package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
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
