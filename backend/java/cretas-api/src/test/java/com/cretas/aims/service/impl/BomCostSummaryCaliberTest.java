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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 标准成本口径 = 辅料 + 包材。
 * 人工要等结算（实际工时 × 时薪 ÷ 实际箱数），均摊要等成本分析，两者都不在 BOM 归集。
 */
@ExtendWith(MockitoExtension.class)
class BomCostSummaryCaliberTest {

    @Mock BomRecipeItemRepository bomRecipeItemRepository;
    @InjectMocks BomServiceImpl bomService;

    private BomRecipeItem item(String name, String qty, String price) {
        BomRecipeItem it = new BomRecipeItem();
        it.setFactoryId("F001");
        it.setMaterialTypeId("M-" + name);
        it.setMaterialName(name);
        it.setStandardQuantity(new BigDecimal(qty));
        it.setYieldRate(new BigDecimal("100.00"));
        it.setUnit("kg");
        it.setUnitPrice(new BigDecimal(price));
        it.setTaxRate(BigDecimal.ZERO);
        // Production reads bomItems via a `JOIN FETCH i.recipe` query (see
        // BomRecipeItemRepository#findCurrentByProductAndStatus), so `recipe` is never
        // null there. The mocked repository here bypasses that join, and
        // calculateProductCost() dereferences bomItems.get(0).getRecipe().getProductName()
        // for the summary's productName — set it explicitly so the fixture matches what
        // production actually hands the service.
        it.setRecipe(BomRecipe.builder().productName("测试配方").build());
        return it;
    }

    @Test
    void calculateProductCost_omitsLaborAndOverhead() {
        lenient().when(bomRecipeItemRepository.findCurrentByProduct(anyString(), anyString()))
                .thenReturn(List.of(item("八角", "2", "30.0000")));

        BomCostSummaryDTO summary = bomService.calculateProductCost("F001", "P001");

        assertTrue(summary.getLaborCosts().isEmpty(), "人工明细必须为空");
        assertTrue(summary.getOverheadCosts().isEmpty(), "均摊明细必须为空");
    }

    @Test
    void calculateProductCost_totalsAreNullNotZero() {
        lenient().when(bomRecipeItemRepository.findCurrentByProduct(anyString(), anyString()))
                .thenReturn(List.of(item("八角", "2", "30.0000")));

        BomCostSummaryDTO summary = bomService.calculateProductCost("F001", "P001");

        // null = 这里不归集；0 = 人工不要钱。后者是假话。
        assertNull(summary.getLaborCostTotal(), "人工总额必须是 null 不是 0");
        assertNull(summary.getOverheadCostTotal(), "均摊总额必须是 null 不是 0");
    }

    @Test
    void calculateProductCost_totalCostEqualsMaterialOnly() {
        lenient().when(bomRecipeItemRepository.findCurrentByProduct(anyString(), anyString()))
                .thenReturn(List.of(item("八角", "2", "30.0000")));

        BomCostSummaryDTO summary = bomService.calculateProductCost("F001", "P001");

        assertEquals(0, summary.getTotalCost().compareTo(summary.getMaterialCostTotal()),
                "总成本必须只等于物料合计");
    }
}
