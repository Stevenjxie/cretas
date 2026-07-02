package com.cretas.aims.service.wip;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
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
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
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

        service.postClerkOutput(FACTORY, ANCHOR, "PT1", new BigDecimal("60"), "kg", null, null, 3);
        assertThat(row.getProducedQuantity()).isEqualByComparingTo("60");
        assertThat(row.getAvailableQuantity()).isEqualByComparingTo("60");
        assertThat(row.getProcessOrder()).isEqualTo(3); // 首次落道序 → picker 可见性锚

        service.postClerkOutput(FACTORY, ANCHOR, "PT1", new BigDecimal("40"), "kg", null, null, 2);
        assertThat(row.getProducedQuantity()).isEqualByComparingTo("100");
        assertThat(row.getAvailableQuantity()).isEqualByComparingTo("100");
        assertThat(row.getProcessOrder()).isEqualTo(2); // 取 MIN (最早道) → 靠后复用道仍可见
        verify(wipRepo, never()).saveAndFlush(any()); // 既有行不新建占位行
    }

    @Test
    @DisplayName("postClerkOutput: inQty<=0 → no-op")
    void postClerkOutputZeroNoop() {
        service.postClerkOutput(FACTORY, ANCHOR, "PT1", BigDecimal.ZERO, "kg", null, null, 1);
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

    // ── 撤销小结: reverseClerkOutput (SFI IN un-stock, 下游守卫) ──

    @Test
    @DisplayName("reverseClerkOutput: 冲销入库净结余 (produced 100 −60 → 40; accumulatedCost 1500 −900 → 600; unitCost 15)")
    void reverseClerkOutputUnstocks() {
        SemiFinishedInventory row = freshRow();
        row.setProducedQuantity(new BigDecimal("100"));
        row.setConsumedQuantity(BigDecimal.ZERO);
        row.setAvailableQuantity(new BigDecimal("100"));
        row.setAccumulatedCost(new BigDecimal("1500"));
        row.setUnitCost(new BigDecimal("15"));
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));

        service.reverseClerkOutput(FACTORY, ANCHOR, new BigDecimal("60"), new BigDecimal("900"), 7L);

        assertThat(row.getProducedQuantity()).isEqualByComparingTo("40");
        assertThat(row.getAvailableQuantity()).isEqualByComparingTo("40");
        assertThat(row.getAccumulatedCost()).isEqualByComparingTo("600");
        assertThat(row.getUnitCost()).isEqualByComparingTo("15"); // 600 / 40
        assertThat(row.getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);
    }

    @Test
    @DisplayName("reverseClerkOutput: totalCost null (当时成本未知) → accumulatedCost 不变, unitCost 按 accumulated/produced 重算")
    void reverseClerkOutputNullTotalCost() {
        SemiFinishedInventory row = freshRow();
        row.setProducedQuantity(new BigDecimal("100"));
        row.setAvailableQuantity(new BigDecimal("100"));
        row.setAccumulatedCost(new BigDecimal("600"));
        row.setUnitCost(null); // 已被 null 成本段 poison
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));

        service.reverseClerkOutput(FACTORY, ANCHOR, new BigDecimal("60"), null, 7L);

        assertThat(row.getProducedQuantity()).isEqualByComparingTo("40");
        assertThat(row.getAccumulatedCost()).isEqualByComparingTo("600"); // 不减 (totalCost null)
        assertThat(row.getUnitCost()).isEqualByComparingTo("15"); // 600 / 40 (accumulatedCost 已知 → 重算)
    }

    @Test
    @DisplayName("reverseClerkOutput: 下游已消耗 (available_after<0) → 抛 SFI_DOWNSTREAM_CONSUMED, 不改库存")
    void reverseClerkOutputDownstreamConsumedThrows() {
        SemiFinishedInventory row = freshRow();
        row.setProducedQuantity(new BigDecimal("60"));
        row.setConsumedQuantity(new BigDecimal("40")); // 下游已吃 40, 余 20
        row.setAvailableQuantity(new BigDecimal("20"));
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.reverseClerkOutput(FACTORY, ANCHOR, new BigDecimal("60"), new BigDecimal("900"), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(409);
                    assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("SFI_DOWNSTREAM_CONSUMED");
                    assertThat(ex.getMessage()).contains("已被下游消耗");
                });
        // 未改库存 (抛前不动)
        assertThat(row.getProducedQuantity()).isEqualByComparingTo("60");
        verify(wipRepo, never()).save(any());
    }

    @Test
    @DisplayName("reverseClerkOutput: 行缺失 → 抛 SFI_NOT_FOUND")
    void reverseClerkOutputMissingThrows() {
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reverseClerkOutput(FACTORY, ANCHOR, new BigDecimal("60"), new BigDecimal("900"), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("SFI_NOT_FOUND"));
    }

    // ── 撤销小结: restoreClerkSemi (SFI OUT restore, 还回消耗) ──

    @Test
    @DisplayName("restoreClerkSemi: 还回消耗 (consumed 60 → 0; available 0 → 60; DEPLETED → AVAILABLE)")
    void restoreClerkSemiRestores() {
        SemiFinishedInventory row = freshRow();
        row.setProducedQuantity(new BigDecimal("60"));
        row.setConsumedQuantity(new BigDecimal("60"));
        row.setAvailableQuantity(BigDecimal.ZERO);
        row.setStatus(SemiFinishedInventory.Status.DEPLETED);
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));

        service.restoreClerkSemi(FACTORY, ANCHOR, new BigDecimal("60"), 7L);

        assertThat(row.getConsumedQuantity()).isEqualByComparingTo("0");
        assertThat(row.getAvailableQuantity()).isEqualByComparingTo("60");
        assertThat(row.getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);
    }

    @Test
    @DisplayName("restoreClerkSemi: 还量超过已消耗 → 抛 SFI_REVERSE_OVER_CONSUMED (诚实, 不静默置 0)")
    void restoreClerkSemiOverConsumedThrows() {
        SemiFinishedInventory row = freshRow();
        row.setProducedQuantity(new BigDecimal("60"));
        row.setConsumedQuantity(new BigDecimal("40"));
        row.setAvailableQuantity(new BigDecimal("20"));
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.restoreClerkSemi(FACTORY, ANCHOR, new BigDecimal("60"), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("SFI_REVERSE_OVER_CONSUMED"));
        verify(wipRepo, never()).save(any());
    }

    @Test
    @DisplayName("restoreClerkSemi: 行缺失 → 容忍 no-op (无处可还, 不产负行, 不阻塞撤销)")
    void restoreClerkSemiMissingRowNoop() {
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.empty());
        service.restoreClerkSemi(FACTORY, ANCHOR, new BigDecimal("50"), 7L);
        verify(wipRepo, never()).save(any());
    }
}
