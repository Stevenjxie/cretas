package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
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
    @Mock private ProductTypeRepository productTypeRepo;
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

    /** 盒装半成品行 (计数单位), 供盒⇄kg 折算测试。 */
    private SemiFinishedInventory boxRow(String producedBoxes) {
        SemiFinishedInventory row = freshRow();
        row.setProducedQuantity(new BigDecimal(producedBoxes));
        row.setAvailableQuantity(new BigDecimal(producedBoxes));
        row.setUnit("盒");
        return row;
    }

    /** 桩: PT1 配了每盒克重。 */
    private void stubGrams(String grams) {
        ProductType pt = new ProductType();
        pt.setId("PT1");
        pt.setGramsPerUnit(new BigDecimal(grams));
        when(productTypeRepo.findById("PT1")).thenReturn(Optional.of(pt));
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

    // ── 盒⇄kg 折算 (计数单位半成品作 kg 道投料来源) ──

    @Test
    @DisplayName("🟢 盒装半成品 + 每盒克重 → kg 投料折算盒数扣减 (200g/盒, 投 2kg → 扣 10盒), consumed 盒数")
    void consumeClerkSemiStrictBoxConvertsToBoxes() {
        SemiFinishedInventory row = boxRow("50");   // 余 50 盒
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));
        stubGrams("200");

        BigDecimal drawn = service.consumeClerkSemiStrict(FACTORY, ANCHOR, new BigDecimal("2"));   // 2kg → 10盒

        assertThat(drawn).isEqualByComparingTo("10");             // 返回扣减盒数 (供撤销精确还回)
        assertThat(row.getConsumedQuantity()).isEqualByComparingTo("10");
        assertThat(row.getAvailableQuantity()).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("🔴 盒装半成品缺每盒克重 (honest-null): kg 投料 → SFI_NO_GRAMS_PER_UNIT (禁止臆造, 不扣)")
    void consumeClerkSemiStrictBoxWithoutGramsThrows() {
        SemiFinishedInventory row = boxRow("50");
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));
        when(productTypeRepo.findById("PT1")).thenReturn(Optional.empty());   // 未配每盒克重

        assertThatThrownBy(() -> service.consumeClerkSemiStrict(FACTORY, ANCHOR, new BigDecimal("2")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("SFI_NO_GRAMS_PER_UNIT"));
        assertThat(row.getConsumedQuantity()).isEqualByComparingTo("0");   // 未扣
        verify(wipRepo, never()).save(any());
    }

    @Test
    @DisplayName("🔴 盒装半成品可用量边界: 折算盒数 > 余盒 → SFI_INSUFFICIENT (余10盒=2kg, 投 2.2kg=11盒 → 超)")
    void consumeClerkSemiStrictBoxOverAvailabilityThrows() {
        SemiFinishedInventory row = boxRow("10");   // 余 10 盒 = 2kg
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));
        stubGrams("200");

        assertThatThrownBy(() -> service.consumeClerkSemiStrict(FACTORY, ANCHOR, new BigDecimal("2.2")))   // 11盒
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("SFI_INSUFFICIENT"));
        assertThat(row.getConsumedQuantity()).isEqualByComparingTo("0");   // 未扣
        verify(wipRepo, never()).save(any());
    }

    @Test
    @DisplayName("成本口径 resolveSemiFeedQtyInSourceUnit: 盒装→盒数(2kg→10盒); kg→原值; 缺克重→null; 缺失→null")
    void resolveSemiFeedQtyInSourceUnit() {
        // 盒装 + 克重: 2kg → 10 盒
        SemiFinishedInventory box = boxRow("50");
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR)).thenReturn(Optional.of(box));
        stubGrams("200");
        assertThat(service.resolveSemiFeedQtyInSourceUnit(FACTORY, ANCHOR, new BigDecimal("2")))
                .isEqualByComparingTo("10");

        // kg 源: 原样 (5kg → 5)
        SemiFinishedInventory kg = freshRow();   // unit null → 非计数
        kg.setProducedQuantity(new BigDecimal("50"));
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, "KGSFI")).thenReturn(Optional.of(kg));
        assertThat(service.resolveSemiFeedQtyInSourceUnit(FACTORY, "KGSFI", new BigDecimal("5")))
                .isEqualByComparingTo("5");

        // 盒装缺克重: 诚实 null
        SemiFinishedInventory boxNoGrams = boxRow("50");
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, "BOXNG")).thenReturn(Optional.of(boxNoGrams));
        when(productTypeRepo.findById("PT1")).thenReturn(Optional.empty());
        assertThat(service.resolveSemiFeedQtyInSourceUnit(FACTORY, "BOXNG", new BigDecimal("2"))).isNull();

        // 批次缺失: 诚实 null
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, "MISS")).thenReturn(Optional.empty());
        assertThat(service.resolveSemiFeedQtyInSourceUnit(FACTORY, "MISS", new BigDecimal("2"))).isNull();
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
    @DisplayName("reverseClerkOutput: 盘亏(adjustmentQuantity<0, consumed=0) → 消息点名'盘亏'而非'下游消耗', 数字对齐 (produced3-adj盘亏2=可撤销1)")
    void reverseClerkOutputStocktakeLossNamesRealCause() {
        SemiFinishedInventory row = freshRow();
        row.setProducedQuantity(new BigDecimal("3"));
        row.setConsumedQuantity(BigDecimal.ZERO);       // 未被下游消耗
        row.setAdjustmentQuantity(new BigDecimal("-2")); // 半成品盘点盘亏 2
        row.setAvailableQuantity(new BigDecimal("1"));   // 3 - 0 - 2 = 1
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.reverseClerkOutput(FACTORY, ANCHOR, new BigDecimal("3"), null, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(409);
                    // 真实原因是盘亏, 消息必须点名"盘亏", 不能再误报"已被下游消耗 0"(consumed 恒为 0 时对用户毫无意义)
                    assertThat(ex.getMessage()).contains("已发生半成品盘亏 2").contains("盘点损耗");
                    assertThat(ex.getMessage()).doesNotContain("已被下游消耗");
                    // 数字必须对齐: 可撤销上限 = produced - consumed + adj = 3 - 0 - 2 = 1 (旧版错误算成 produced-consumed=3)
                    assertThat(ex.getMessage()).contains("当前可撤销入库仅 1");
                    // 不能再给"请先撤销下游消耗"这个不存在的补救路径 (dead-end)
                    assertThat(ex.getMessage()).doesNotContain("请先撤销下游消耗");
                    assertThat(ex.getMessage()).contains("盘亏已过账");
                });
        // 未改库存 (抛前不动)
        assertThat(row.getProducedQuantity()).isEqualByComparingTo("3");
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
