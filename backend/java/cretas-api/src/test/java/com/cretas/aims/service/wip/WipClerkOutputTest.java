package com.cretas.aims.service.wip;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G3 小结底层原语单测: SFI IN (postClerkOutput) + SFI OUT (consumeClerkSemi).
 *
 * <p>self (REQUIRES_NEW 代理) 在纯 Mockito 下为 null → 走 fallback 直调 commitEmptySemiRow,
 * 这里用既有行路径 (findForUpdate 直接返回行) 验证 moving-average 累加 + 出库扣减 + not-below-zero 守卫。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WipClerkOutputTest - SFI IN/OUT 原语")
class WipClerkOutputTest {

    private static final String FACTORY = "F006";
    private static final String ANCHOR = "CLK-SEMI-PLAN1234-PT123456";

    @Mock private SemiFinishedInventoryRepository wipRepo;
    @InjectMocks private WipInventoryServiceImpl service;

    private SemiFinishedInventory freshRow() {
        return SemiFinishedInventory.builder()
                .factoryId(FACTORY)
                .intermediateBatchNo(ANCHOR)
                .productTypeId("PT1")
                .producedQuantity(BigDecimal.ZERO)
                .consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(BigDecimal.ZERO)
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
    }

    @Test
    @DisplayName("postClerkOutput: 既有行 moving-average 累加 (60 → +40 → produced 100)")
    void postClerkOutputAccumulates() {
        SemiFinishedInventory row = freshRow();
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));

        service.postClerkOutput(FACTORY, ANCHOR, "PT1", new BigDecimal("60"), "kg", null, null);
        assertThat(row.getProducedQuantity()).isEqualByComparingTo("60");
        assertThat(row.getAvailableQuantity()).isEqualByComparingTo("60");

        service.postClerkOutput(FACTORY, ANCHOR, "PT1", new BigDecimal("40"), "kg", null, null);
        assertThat(row.getProducedQuantity()).isEqualByComparingTo("100");
        assertThat(row.getAvailableQuantity()).isEqualByComparingTo("100");
        verify(wipRepo, never()).saveAndFlush(any()); // 既有行不新建占位行
    }

    @Test
    @DisplayName("postClerkOutput: inQty<=0 → no-op")
    void postClerkOutputZeroNoop() {
        service.postClerkOutput(FACTORY, ANCHOR, "PT1", BigDecimal.ZERO, "kg", null, null);
        verify(wipRepo, never()).save(any());
    }

    @Test
    @DisplayName("consumeClerkSemi: 扣减 + available 升降 + DEPLETED")
    void consumeClerkSemiDrawsDown() {
        SemiFinishedInventory row = freshRow();
        row.setProducedQuantity(new BigDecimal("60"));
        row.setAvailableQuantity(new BigDecimal("60"));
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));

        service.consumeClerkSemi(FACTORY, ANCHOR, new BigDecimal("60"));
        assertThat(row.getConsumedQuantity()).isEqualByComparingTo("60");
        assertThat(row.getAvailableQuantity()).isEqualByComparingTo("0");
        assertThat(row.getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);
    }

    @Test
    @DisplayName("consumeClerkSemi: 超扣 clamp 到 produced (not-below-zero 守卫)")
    void consumeClerkSemiOverDrawClamps() {
        SemiFinishedInventory row = freshRow();
        row.setProducedQuantity(new BigDecimal("60"));
        row.setAvailableQuantity(new BigDecimal("60"));
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));

        service.consumeClerkSemi(FACTORY, ANCHOR, new BigDecimal("100"));
        assertThat(row.getConsumedQuantity()).isEqualByComparingTo("60"); // clamp
        assertThat(row.getAvailableQuantity()).isEqualByComparingTo("0"); // 不为负
    }

    @Test
    @DisplayName("consumeClerkSemi: SFI 行缺失 → no-op (无库存可扣, 不报错)")
    void consumeClerkSemiMissingRowNoop() {
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.empty());
        service.consumeClerkSemi(FACTORY, ANCHOR, new BigDecimal("50"));
        verify(wipRepo, never()).save(any());
    }
}
