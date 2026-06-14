package com.cretas.aims.service.impl;

import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.bom.LaborCostConfigRepository;
import com.cretas.aims.repository.bom.OverheadCostConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("B8 BOM cost pre-tax caliber")
@ExtendWith(MockitoExtension.class)
class BomServiceImplPreTaxCaliberTest {

    @Mock
    private BomItemRepository bomItemRepository;
    @Mock
    private LaborCostConfigRepository laborCostConfigRepository;
    @Mock
    private OverheadCostConfigRepository overheadCostConfigRepository;

    private BomServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BomServiceImpl(bomItemRepository, laborCostConfigRepository, overheadCostConfigRepository);
    }

    @Test
    @DisplayName("B8: cost summary marks material prices as pre-tax and does not divide an already pre-tax BOM price again")
    void calculateProductCost_marksPreTaxCaliberWithoutDoubleConverting() {
        BomItem beef = BomItem.builder()
                .factoryId("F006")
                .productTypeId("P-B8")
                .productName("B8 cost product")
                .materialTypeId("RM-BEEF")
                .materialName("beef shank")
                .standardQuantity(new BigDecimal("1.0000"))
                .yieldRate(new BigDecimal("100.00"))
                .unit("jin")
                .unitPrice(new BigDecimal("57.5221"))
                .taxRate(new BigDecimal("13.00"))
                .build();

        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc("F006", "P-B8"))
                .thenReturn(List.of(beef));
        when(laborCostConfigRepository.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc("F006", "P-B8"))
                .thenReturn(List.of());
        when(laborCostConfigRepository.findByFactoryIdAndProductTypeIdIsNullAndDeletedAtIsNullOrderBySortOrderAsc("F006"))
                .thenReturn(List.of());
        when(overheadCostConfigRepository.findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc("F006"))
                .thenReturn(List.of());

        BomCostSummaryDTO result = service.calculateProductCost("F006", "P-B8");

        assertThat(result.getMaterialCostTotal()).isEqualByComparingTo("57.5221");
        assertThat(result.getCostCaliber()).isEqualTo("PRE_TAX");
        assertThat(result.getCaliberHint()).contains("未税");
        assertThat(result.getMaterialCosts()).singleElement().satisfies(item -> {
            assertThat(item.getUnitPrice()).isEqualByComparingTo("57.5221");
            assertThat(item.getSubtotal()).isEqualByComparingTo("57.5221");
            assertThat(item.getUnitPriceCaliber()).isEqualTo("PRE_TAX");
            assertThat(item.getCaliberHint()).contains("BOM unitPrice 为未税价");
        });
    }

    @Test
    @DisplayName("B8: missing tax rate remains null and cost summary honestly marks the gap")
    void calculateProductCost_missingTaxRateStaysNullAndMarkedInCaliberHint() {
        BomItem material = BomItem.builder()
                .factoryId("F006")
                .productTypeId("P-B8-MISSING-TAX")
                .productName("B8 missing tax")
                .materialTypeId("RM-MISSING-TAX")
                .materialName("material without tax rate")
                .standardQuantity(new BigDecimal("1.0000"))
                .yieldRate(new BigDecimal("100.00"))
                .unit("kg")
                .unitPrice(new BigDecimal("57.5221"))
                .taxRate(null)
                .build();

        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(
                "F006", "P-B8-MISSING-TAX"))
                .thenReturn(List.of(material));
        when(laborCostConfigRepository.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc(
                "F006", "P-B8-MISSING-TAX"))
                .thenReturn(List.of());
        when(laborCostConfigRepository.findByFactoryIdAndProductTypeIdIsNullAndDeletedAtIsNullOrderBySortOrderAsc("F006"))
                .thenReturn(List.of());
        when(overheadCostConfigRepository.findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc("F006"))
                .thenReturn(List.of());

        BomCostSummaryDTO result = service.calculateProductCost("F006", "P-B8-MISSING-TAX");

        assertThat(result.getMaterialCosts()).singleElement().satisfies(item -> {
            assertThat(item.getTaxRate()).isNull();
            assertThat(item.getCaliberHint()).contains("缺税率");
        });
        assertThat(result.getCaliberHint()).contains("缺税率");
    }
}
