package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.processentry.FinishedGoodsStockItem;
import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.inventory.impl.FinishedGoodsFeedServiceImpl;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ①c 成品作投料来源 — {@link FinishedGoodsFeedService} 服务级 TDD。
 *
 * <p>固化: (1) 可投料成品列表按产品族过滤 (猪蹄计划不显牛肉, 族未知放行);
 * (2) 严格扣减 loud-fail (缺失 FG_NOT_FOUND / 不足 FG_INSUFFICIENT, 禁止降级);
 * (3) 成本读取诚实 null。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FinishedGoodsFeedServiceTest - ①c 成品投料 列表族过滤 + 严格扣减 loud-fail")
class FinishedGoodsFeedServiceTest {

    private static final String FACTORY = "F006";
    private static final String PT_PIG = "PT-PIG";      // 卤猪蹄 (计划产品)
    private static final String PT_PIG2 = "PT-PIG2";    // 椒麻猪蹄 (兄弟猪蹄成品)
    private static final String PT_BEEF = "PT-BEEF";    // 牛肉 (异族)

    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ProductFamilyResolver productFamilyResolver;
    @Mock private FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;

    private FinishedGoodsFeedServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FinishedGoodsFeedServiceImpl(
                finishedGoodsBatchRepository, productFamilyResolver, finishedGoodsAdjustmentLogRepository);
    }

    @Test
    @DisplayName("列表族过滤: 猪蹄计划只见猪蹄族成品 (跨兄弟成品), 牛肉排除; 族未知放行")
    void listFiltersByFamilyHidesOtherFamily() {
        FinishedGoodsBatch pig = fg("FG-PIG-1", PT_PIG, "50");
        FinishedGoodsBatch pig2 = fg("FG-PIG2-1", PT_PIG2, "30");   // 兄弟猪蹄成品 (同族, 应保留)
        FinishedGoodsBatch beef = fg("FG-BEEF-1", PT_BEEF, "40");   // 异族, 应排除
        FinishedGoodsBatch unknown = fg("FG-UNK-1", "PT-UNK", "20"); // 族未知, 放行
        when(finishedGoodsBatchRepository.findAvailableForFeedByFactory(FACTORY))
                .thenReturn(List.of(pig, pig2, beef, unknown));
        // 族解析: 猪蹄族 = RM:pig; 牛肉族 = RM:beef; PT-UNK 无族键 (map 中不出现)。
        when(productFamilyResolver.resolveFamilies(eq(FACTORY), anyCollection()))
                .thenReturn(Map.of(PT_PIG, "RM:pig", PT_PIG2, "RM:pig", PT_BEEF, "RM:beef"));

        List<FinishedGoodsStockItem> out = service.listAvailableForFeed(FACTORY, PT_PIG);

        assertThat(out).extracting(FinishedGoodsStockItem::getBatchNumber)
                .containsExactlyInAnyOrder("FG-PIG-1", "FG-PIG2-1", "FG-UNK-1");   // 牛肉排除, 兄弟猪蹄+未知保留
    }

    @Test
    @DisplayName("列表: productTypeId 为空 → 不按族过滤 (全量可投料成品)")
    void listWithoutFamilyReturnsAll() {
        when(finishedGoodsBatchRepository.findAvailableForFeedByFactory(FACTORY))
                .thenReturn(List.of(fg("FG-A", PT_PIG, "10"), fg("FG-B", PT_BEEF, "20")));

        List<FinishedGoodsStockItem> out = service.listAvailableForFeed(FACTORY, null);

        assertThat(out).hasSize(2);
        verify(productFamilyResolver, never()).resolveFamilies(any(), any());
    }

    @Test
    @DisplayName("列表: 携带 品名/生产日期/成本 (诚实 null cost)")
    void listCarriesNameDateCost() {
        FinishedGoodsBatch b = fg("FG-A", PT_PIG, "10");
        b.setProductName("卤猪蹄");
        b.setProductionDate(LocalDate.of(2026, 7, 1));
        b.setUnitCost(new BigDecimal("12.34"));
        FinishedGoodsBatch bNull = fg("FG-B", PT_PIG, "5");
        bNull.setUnitCost(null);   // 未接通成本
        when(finishedGoodsBatchRepository.findAvailableForFeedByFactory(FACTORY))
                .thenReturn(List.of(b, bNull));

        List<FinishedGoodsStockItem> out = service.listAvailableForFeed(FACTORY, null);

        FinishedGoodsStockItem a = out.stream().filter(i -> i.getBatchNumber().equals("FG-A")).findFirst().orElseThrow();
        assertThat(a.getProductTypeName()).isEqualTo("卤猪蹄");
        assertThat(a.getProductionDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(a.getUnitCost()).isEqualByComparingTo("12.34");
        assertThat(a.getAvailableQuantity()).isEqualByComparingTo("10");
        FinishedGoodsStockItem nullCost = out.stream().filter(i -> i.getBatchNumber().equals("FG-B")).findFirst().orElseThrow();
        assertThat(nullCost.getUnitCost()).isNull();   // 诚实 null
    }

    @Test
    @DisplayName("🔴 严格扣减成功: 减 producedQuantity (不动 shippedQuantity) + 写 PRODUCTION_FEED 调整日志; available 正确下降")
    void consumeStrictReducesProducedNotShippedAndLogs() {
        FinishedGoodsBatch b = fg("FG-A", PT_PIG, "50");     // produced=50, shipped=0 → available=50
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "FG-A"))
                .thenReturn(Optional.of(b));

        BigDecimal drawn = service.consumeForFeedStrict(FACTORY, "FG-A", new BigDecimal("30"), "kg");

        assertThat(drawn).isEqualByComparingTo("30");
        // 🔴 shippedQuantity 绝不动 (避免被 VoucherExportServiceImpl 误当"成品发出"按售价计入)
        assertThat(b.getShippedQuantity()).isEqualByComparingTo("0");
        // 减 producedQuantity → available 随之下降
        assertThat(b.getProducedQuantity()).isEqualByComparingTo("20");
        assertThat(b.getAvailableQuantity()).isEqualByComparingTo("20");
        verify(finishedGoodsBatchRepository).save(b);
        // 调整日志留痕: referenceType=PRODUCTION_FEED, 负数变更, before/after=produced
        ArgumentCaptor<FinishedGoodsAdjustmentLog> logCap = ArgumentCaptor.forClass(FinishedGoodsAdjustmentLog.class);
        verify(finishedGoodsAdjustmentLogRepository).save(logCap.capture());
        FinishedGoodsAdjustmentLog logRow = logCap.getValue();
        assertThat(logRow.getReferenceType()).isEqualTo("PRODUCTION_FEED");
        assertThat(logRow.getAdjustmentQuantity()).isEqualByComparingTo("-30");
        assertThat(logRow.getBeforeProduced()).isEqualByComparingTo("50");
        assertThat(logRow.getAfterProduced()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("严格扣减 loud-fail: 批次不存在 → FG_NOT_FOUND (禁止降级, 不写日志)")
    void consumeStrictNotFoundThrows() {
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "FG-MISSING"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consumeForFeedStrict(FACTORY, "FG-MISSING", new BigDecimal("10"), "kg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("成品库存不存在");
        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(finishedGoodsAdjustmentLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("严格扣减 loud-fail: 库存不足 (qty>available) → FG_INSUFFICIENT (不 clamp, 不改 produced/不写日志)")
    void consumeStrictInsufficientThrows() {
        FinishedGoodsBatch b = fg("FG-A", PT_PIG, "30");     // available = 30
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "FG-A"))
                .thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.consumeForFeedStrict(FACTORY, "FG-A", new BigDecimal("100"), "kg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("成品库存不足");
        assertThat(b.getProducedQuantity()).isEqualByComparingTo("30");   // 未改
        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(finishedGoodsAdjustmentLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("🟠 单位不一致 loud-fail: FG 批次 盒 + 投料 kg → FG_UNIT_MISMATCH (禁止降级, 不误扣)")
    void consumeStrictUnitMismatchThrows() {
        FinishedGoodsBatch b = fg("FG-BOX", PT_PIG, "50");
        b.setUnit("盒");                                       // 气调成品按盒计量
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "FG-BOX"))
                .thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.consumeForFeedStrict(FACTORY, "FG-BOX", new BigDecimal("10"), "kg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("单位")
                .hasMessageContaining("不一致");
        // 不误扣: produced/shipped 均不动, 不写日志
        assertThat(b.getProducedQuantity()).isEqualByComparingTo("50");
        assertThat(b.getShippedQuantity()).isEqualByComparingTo("0");
        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(finishedGoodsAdjustmentLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("单位一致 (both kg) → 正常扣减 (不被单位守卫误伤)")
    void consumeStrictSameUnitOk() {
        FinishedGoodsBatch b = fg("FG-KG", PT_PIG, "50");    // unit "kg"
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, "FG-KG"))
                .thenReturn(Optional.of(b));

        BigDecimal drawn = service.consumeForFeedStrict(FACTORY, "FG-KG", new BigDecimal("10"), "kg");

        assertThat(drawn).isEqualByComparingTo("10");
        assertThat(b.getProducedQuantity()).isEqualByComparingTo("40");
        verify(finishedGoodsAdjustmentLogRepository).save(any());
    }

    @Test
    @DisplayName("成本读取诚实 null: 批次缺失 → null")
    void getFeedUnitCostMissingReturnsNull() {
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "FG-X"))
                .thenReturn(Optional.empty());
        assertThat(service.getFeedUnitCost(FACTORY, "FG-X")).isNull();
    }

    // ─────────────────────────────────────────────────────────────

    private FinishedGoodsBatch fg(String batchNumber, String productTypeId, String produced) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setFactoryId(FACTORY);
        b.setBatchNumber(batchNumber);
        b.setProductTypeId(productTypeId);
        b.setProducedQuantity(new BigDecimal(produced));
        b.setShippedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setUnit("kg");
        b.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        return b;
    }
}
