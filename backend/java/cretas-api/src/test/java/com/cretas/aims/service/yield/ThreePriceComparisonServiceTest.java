package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.ThreePriceSkuDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.bom.CostVarianceService;
import com.cretas.aims.service.bom.StandardCostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 单元测试 {@link ThreePriceComparisonService} — per-SKU 三价对比看板.
 *
 * <p>覆盖: 三价数值正确组装 / variancePct+overBudget 计算 / 标准成本口径不全诚实 null (不误报超支) /
 * maskPrice 脱敏 / overBudgetOnly 过滤 / 超支优先排序。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ThreePriceComparisonServiceTest {

    private static final String F = "F006";

    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private StandardCostService standardCostService;
    @Mock private CostVarianceService costVarianceService;
    @InjectMocks private ThreePriceComparisonService service;

    private ProductType productType(String id, String name, String unitPrice, String taxIncludedUnitPrice) {
        ProductType pt = new ProductType();
        pt.setId(id);
        pt.setName(name);
        pt.setCode(id);
        pt.setCategory("卤味");
        pt.setUnit("盒");
        pt.setIsActive(true);
        pt.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        pt.setTaxIncludedUnitPrice(taxIncludedUnitPrice == null ? null : new BigDecimal(taxIncludedUnitPrice));
        return pt;
    }

    private ProductionBatch pricedBatch(String batchNumber, String unitCost) {
        ProductionBatch b = new ProductionBatch();
        b.setBatchNumber(batchNumber);
        b.setUnitCost(new BigDecimal(unitCost));
        return b;
    }

    private StandardCostService.StandardUnitCost std(String total, boolean laborIncluded, String hint) {
        return StandardCostService.StandardUnitCost.builder()
                .totalUnitCost(total == null ? null : new BigDecimal(total))
                .laborIncluded(laborIncluded)
                .caliberHint(hint)
                .build();
    }

    @Test
    void assemblesThreePricesAndComputesVarianceAndOverBudget() {
        ProductType pt = productType("PT-1", "卤猪蹄", "50.00", "56.50");
        when(productTypeRepository.findByFactoryIdAndIsActiveTrue(F)).thenReturn(List.of(pt));
        when(standardCostService.resolveStandardUnitCost(F, "PT-1"))
                .thenReturn(std("30.0000", true, "标准成本含人工"));
        when(productionBatchRepository.findRecentPricedBatches(eq(F), eq("PT-1"), any()))
                .thenReturn(List.of(pricedBatch("BATCH-001", "36.0000")));
        // 20% 超支: (36-30)/30 = 20%
        when(costVarianceService.computeVariancePct(new BigDecimal("36.0000"), new BigDecimal("30.0000")))
                .thenReturn(new BigDecimal("20.00"));
        when(costVarianceService.resolveThreshold(F, "PT-1")).thenReturn(new BigDecimal("10"));

        List<ThreePriceSkuDTO> rows = service.compareBySku(F, false, false, null);

        assertThat(rows).hasSize(1);
        ThreePriceSkuDTO row = rows.get(0);
        assertThat(row.getProductTypeId()).isEqualTo("PT-1");
        assertThat(row.getStandardCost()).isEqualByComparingTo("30.0000");
        assertThat(row.getSalesPrice()).isEqualByComparingTo("50.00");
        assertThat(row.getTaxIncludedSalesPrice()).isEqualByComparingTo("56.50");
        assertThat(row.getActualCost()).isEqualByComparingTo("36.0000");
        assertThat(row.getVariancePct()).isEqualByComparingTo("20.00");
        assertThat(row.getThreshold()).isEqualByComparingTo("10");
        assertThat(row.getOverBudget()).isTrue();
        // 毛利率 = (50 - 36) / 50 * 100 = 28.00%
        assertThat(row.getGrossMargin()).isEqualByComparingTo("28.00");
        assertThat(row.getActualCostAsOfBatchNumber()).isEqualTo("BATCH-001");
    }

    @Test
    void honestNull_whenStandardCostCaliberIncomplete_doesNotFalseAlarmOverBudget() {
        ProductType pt = productType("PT-2", "无BOM产品", "40.00", null);
        when(productTypeRepository.findByFactoryIdAndIsActiveTrue(F)).thenReturn(List.of(pt));
        // 标准成本口径不全 (无 BOM / 无研发人工) → totalUnitCost null
        when(standardCostService.resolveStandardUnitCost(F, "PT-2"))
                .thenReturn(std(null, false, "标准成本口径不全，未与实际成本对比: BOM 料标准成本不可用"));
        when(productionBatchRepository.findRecentPricedBatches(eq(F), eq("PT-2"), any()))
                .thenReturn(List.of());
        when(costVarianceService.computeVariancePct(any(), eq((BigDecimal) null))).thenReturn(null);
        when(costVarianceService.resolveThreshold(F, "PT-2")).thenReturn(new BigDecimal("10"));

        List<ThreePriceSkuDTO> rows = service.compareBySku(F, false, false, null);

        assertThat(rows).hasSize(1);
        ThreePriceSkuDTO row = rows.get(0);
        assertThat(row.getStandardCost()).isNull();
        assertThat(row.getActualCost()).isNull();
        assertThat(row.getVariancePct()).isNull();
        // 诚实: 口径不全时绝不误报超支 (镜像 OrderCostAlarmListener 的跳过逻辑)
        assertThat(row.getOverBudget()).isFalse();
        assertThat(row.getCaliberHint()).contains("口径不全");
    }

    @Test
    void maskPrice_nullsAllMoneyFieldsButKeepsOverBudgetFlagAndIdentity() {
        ProductType pt = productType("PT-3", "含税测试品", "20.00", "22.60");
        when(productTypeRepository.findByFactoryIdAndIsActiveTrue(F)).thenReturn(List.of(pt));
        when(standardCostService.resolveStandardUnitCost(F, "PT-3"))
                .thenReturn(std("10.0000", true, "标准成本含人工"));
        when(productionBatchRepository.findRecentPricedBatches(eq(F), eq("PT-3"), any()))
                .thenReturn(List.of(pricedBatch("BATCH-003", "15.0000")));
        when(costVarianceService.computeVariancePct(new BigDecimal("15.0000"), new BigDecimal("10.0000")))
                .thenReturn(new BigDecimal("50.00"));
        when(costVarianceService.resolveThreshold(F, "PT-3")).thenReturn(new BigDecimal("10"));

        List<ThreePriceSkuDTO> rows = service.compareBySku(F, true, false, null);

        assertThat(rows).hasSize(1);
        ThreePriceSkuDTO row = rows.get(0);
        assertThat(row.getStandardCost()).isNull();
        assertThat(row.getSalesPrice()).isNull();
        assertThat(row.getTaxIncludedSalesPrice()).isNull();
        assertThat(row.getActualCost()).isNull();
        assertThat(row.getVariancePct()).isNull();
        assertThat(row.getThreshold()).isNull();
        assertThat(row.getGrossMargin()).isNull();
        // 身份字段 + 超支布尔标记 不受脱敏影响 (超支报警推送本就不含金额, 只含百分比)
        assertThat(row.getProductTypeId()).isEqualTo("PT-3");
        assertThat(row.getProductName()).isEqualTo("含税测试品");
        assertThat(row.getOverBudget()).isTrue();
    }

    @Test
    void overBudgetOnly_filtersOutInBudgetSkus() {
        ProductType inBudget = productType("PT-OK", "达标品", "50.00", null);
        ProductType overBudget = productType("PT-BAD", "超支品", "50.00", null);
        when(productTypeRepository.findByFactoryIdAndIsActiveTrue(F)).thenReturn(List.of(inBudget, overBudget));

        when(standardCostService.resolveStandardUnitCost(F, "PT-OK")).thenReturn(std("30.0000", true, "含人工"));
        when(productionBatchRepository.findRecentPricedBatches(eq(F), eq("PT-OK"), any()))
                .thenReturn(List.of(pricedBatch("B-OK", "31.0000")));
        when(costVarianceService.computeVariancePct(new BigDecimal("31.0000"), new BigDecimal("30.0000")))
                .thenReturn(new BigDecimal("3.33"));
        when(costVarianceService.resolveThreshold(F, "PT-OK")).thenReturn(new BigDecimal("10"));

        when(standardCostService.resolveStandardUnitCost(F, "PT-BAD")).thenReturn(std("30.0000", true, "含人工"));
        when(productionBatchRepository.findRecentPricedBatches(eq(F), eq("PT-BAD"), any()))
                .thenReturn(List.of(pricedBatch("B-BAD", "50.0000")));
        when(costVarianceService.computeVariancePct(new BigDecimal("50.0000"), new BigDecimal("30.0000")))
                .thenReturn(new BigDecimal("66.67"));
        when(costVarianceService.resolveThreshold(F, "PT-BAD")).thenReturn(new BigDecimal("10"));

        List<ThreePriceSkuDTO> rows = service.compareBySku(F, false, true, null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getProductTypeId()).isEqualTo("PT-BAD");
    }

    @Test
    void sortsOverBudgetFirst() {
        ProductType a = productType("PT-A", "A品-达标", "50.00", null);
        ProductType b = productType("PT-B", "B品-超支", "50.00", null);
        when(productTypeRepository.findByFactoryIdAndIsActiveTrue(F)).thenReturn(List.of(a, b));

        when(standardCostService.resolveStandardUnitCost(F, "PT-A")).thenReturn(std("30.0000", true, "含人工"));
        when(productionBatchRepository.findRecentPricedBatches(eq(F), eq("PT-A"), any()))
                .thenReturn(List.of(pricedBatch("B-A", "31.0000")));
        when(costVarianceService.computeVariancePct(new BigDecimal("31.0000"), new BigDecimal("30.0000")))
                .thenReturn(new BigDecimal("3.33"));
        when(costVarianceService.resolveThreshold(F, "PT-A")).thenReturn(new BigDecimal("10"));

        when(standardCostService.resolveStandardUnitCost(F, "PT-B")).thenReturn(std("30.0000", true, "含人工"));
        when(productionBatchRepository.findRecentPricedBatches(eq(F), eq("PT-B"), any()))
                .thenReturn(List.of(pricedBatch("B-B", "50.0000")));
        when(costVarianceService.computeVariancePct(new BigDecimal("50.0000"), new BigDecimal("30.0000")))
                .thenReturn(new BigDecimal("66.67"));
        when(costVarianceService.resolveThreshold(F, "PT-B")).thenReturn(new BigDecimal("10"));

        // repo 返回顺序刻意把「达标」放前面 — 断言 service 重排到超支优先
        List<ThreePriceSkuDTO> rows = service.compareBySku(F, false, false, null);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getProductTypeId()).isEqualTo("PT-B");
        assertThat(rows.get(1).getProductTypeId()).isEqualTo("PT-A");
    }

    @Test
    void inactiveProductType_isExcluded() {
        ProductType inactive = productType("PT-OFF", "已下架品", "10.00", null);
        inactive.setIsActive(false);
        when(productTypeRepository.findByFactoryIdAndIsActiveTrue(F)).thenReturn(List.of(inactive));

        List<ThreePriceSkuDTO> rows = service.compareBySku(F, false, false, null);

        assertThat(rows).isEmpty();
    }
}
