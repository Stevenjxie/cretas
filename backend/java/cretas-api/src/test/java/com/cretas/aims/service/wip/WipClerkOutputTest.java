package com.cretas.aims.service.wip;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.exception.BusinessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    // ── 严格版 consumeClerkSemiStrict (SFI 投料, 禁止降级) ──

    @Test
    @DisplayName("consumeClerkSemiStrict: 足量 → 扣减 + 返回实际出库量 (= qty)")
    void consumeClerkSemiStrictDrawsDownAndReturnsQty() {
        SemiFinishedInventory row = freshRow();
        row.setProducedQuantity(new BigDecimal("60"));
        row.setAvailableQuantity(new BigDecimal("60"));
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));

        BigDecimal drawn = service.consumeClerkSemiStrict(FACTORY, ANCHOR, new BigDecimal("40"));
        assertThat(drawn).isEqualByComparingTo("40");
        assertThat(row.getConsumedQuantity()).isEqualByComparingTo("40");
        assertThat(row.getAvailableQuantity()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("consumeClerkSemiStrict: 行缺失 → 抛 SFI_NOT_FOUND (不 no-op)")
    void consumeClerkSemiStrictMissingThrows() {
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.consumeClerkSemiStrict(FACTORY, ANCHOR, new BigDecimal("50")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(409);
                    assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("SFI_NOT_FOUND");
                    assertThat(ex.getMessage()).contains("半成品库存不存在");
                });
        verify(wipRepo, never()).save(any());
    }

    @Test
    @DisplayName("consumeClerkSemiStrict: 不足 → 抛 SFI_INSUFFICIENT (不 clamp), 不改库存")
    void consumeClerkSemiStrictInsufficientThrows() {
        SemiFinishedInventory row = freshRow();
        row.setProducedQuantity(new BigDecimal("60"));
        row.setConsumedQuantity(new BigDecimal("40"));
        row.setAvailableQuantity(new BigDecimal("20"));
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.consumeClerkSemiStrict(FACTORY, ANCHOR, new BigDecimal("30")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(409);
                    assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("SFI_INSUFFICIENT");
                    assertThat(ex.getMessage()).contains("不足");
                });
        // 未扣减 (抛前不改)
        assertThat(row.getConsumedQuantity()).isEqualByComparingTo("40");
        verify(wipRepo, never()).save(any());
    }

    // ── getSemiUnitCost (成本传导基准, 诚实 null) ──

    @Test
    @DisplayName("getSemiUnitCost: 行存在且有成本 → 返 unitCost")
    void getSemiUnitCostReturnsUnitCost() {
        SemiFinishedInventory row = freshRow();
        row.setUnitCost(new BigDecimal("12.5000"));
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));
        assertThat(service.getSemiUnitCost(FACTORY, ANCHOR)).isEqualByComparingTo("12.5");
    }

    @Test
    @DisplayName("getSemiUnitCost: 诚实 null — 行缺失 → null")
    void getSemiUnitCostMissingRowNull() {
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.empty());
        assertThat(service.getSemiUnitCost(FACTORY, ANCHOR)).isNull();
    }

    @Test
    @DisplayName("getSemiUnitCost: 诚实 null — 行存在但 unitCost 为 null (旧库存无成本) → null (不伪造 0)")
    void getSemiUnitCostNullCostRowNull() {
        SemiFinishedInventory row = freshRow(); // unitCost 默认 null
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));
        assertThat(service.getSemiUnitCost(FACTORY, ANCHOR)).isNull();
    }
}
