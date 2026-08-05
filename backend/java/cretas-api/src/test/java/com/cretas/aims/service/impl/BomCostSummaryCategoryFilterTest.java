package com.cretas.aims.service.impl;

import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 成本只归集包材行。
 * RAW/AUXILIARY 的 standard_quantity 是已废弃的历史脏数据；
 * BYPRODUCT 的 standard_quantity 是「预计产出量」，加进成本符号是反的。
 */
@ExtendWith(MockitoExtension.class)
class BomCostSummaryCategoryFilterTest {

    @Mock BomRecipeItemRepository bomRecipeItemRepository;
    @InjectMocks BomServiceImpl bomService;

    private BomRecipeItem item(String name, String category, String qty, String price) {
        BomRecipe parent = new BomRecipe();
        parent.setProductName("测试产品");
        BomRecipeItem it = new BomRecipeItem();
        it.setFactoryId("F001");
        it.setMaterialTypeId("M-" + name);
        it.setMaterialName(name);
        it.setMaterialCategory(category);
        it.setStandardQuantity(new BigDecimal(qty));
        it.setYieldRate(new BigDecimal("100.00"));
        it.setUnit("pcs");
        it.setUnitPrice(new BigDecimal(price));
        it.setTaxRate(BigDecimal.ZERO);
        it.setRecipe(parent);
        return it;
    }

    private BomCostSummaryDTO summaryOf(List<BomRecipeItem> items) {
        lenient().when(bomRecipeItemRepository.findCurrentByProduct(anyString(), anyString()))
                .thenReturn(items);
        return bomService.calculateProductCost("F001", "P001");
    }

    @Test
    void onlyPackagingContributesToMaterialTotal() {
        BomCostSummaryDTO summary = summaryOf(List.of(
                item("陈年脏数据鸭腿", "RAW",       "100", "20.0000"),
                item("陈年脏数据盐",   "AUXILIARY", "50",  "10.0000"),
                item("真空袋",         "PACKAGING", "2",   "1.5000")));

        assertEquals(0, summary.getMaterialCostTotal().compareTo(new BigDecimal("3.0000")),
                "只有包材行应计入物料成本合计");
    }

    @Test
    void byproductOutputIsNeverAddedAsCost() {
        BomCostSummaryDTO summary = summaryOf(List.of(
                item("鸭油",   "BYPRODUCT", "8", "12.0000"),
                item("真空袋", "PACKAGING", "2", "1.5000")));

        assertEquals(0, summary.getMaterialCostTotal().compareTo(new BigDecimal("3.0000")),
                "副产品产出量不得被当作成本加总");
    }

    @Test
    void nonPackagingRowsStayListedWithNullSubtotal() {
        BomCostSummaryDTO summary = summaryOf(List.of(
                item("陈年脏数据鸭腿", "RAW",       "100", "20.0000"),
                item("真空袋",         "PACKAGING", "2",   "1.5000")));

        assertEquals(2, summary.getMaterialCosts().size(), "明细行不应被滤掉, 仍要能看到物料与单价");
        BomCostSummaryDTO.MaterialCostItem raw = summary.getMaterialCosts().stream()
                .filter(row -> "RAW".equals(row.getMaterialCategory()))
                .findFirst().orElseThrow();
        assertNull(raw.getSubtotal(), "非包材行的小计必须是 null(此处不归集), 不是 0");
    }
}
