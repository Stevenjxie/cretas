package com.cretas.aims.service.restock;

import com.cretas.aims.entity.MaterialProductConversion;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ProductDemandProjection;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.restock.dto.RestockBoardDTO;
import com.cretas.aims.service.restock.dto.RestockHorizonDTO;
import com.cretas.aims.service.restock.dto.RestockHorizonProductRow;
import com.cretas.aims.service.restock.dto.RestockRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.cretas.aims.entity.factory.WarehouseCodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RestockBoardService")
class RestockBoardServiceTest {

    private static final String FACTORY_ID = "F006";
    private static final String BOX_UNIT = "\u76d2";
    private static final LocalDate JUNE_1 = LocalDate.of(2026, 6, 1);
    private static final LocalDate JUNE_2 = LocalDate.of(2026, 6, 2);
    private static final LocalDate JUNE_3 = LocalDate.of(2026, 6, 3);

    @Mock SalesOrderItemRepository salesOrderItemRepository;
    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    @Mock ProductionPlanRepository productionPlanRepository;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock ConversionRepository conversionRepository;
    @Mock MaterialBatchRepository materialBatchRepository;
    @InjectMocks RestockBoardService service;

    @Test
    @DisplayName("single-day board keeps original shortfall calculation")
    void singleDayShortfall() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq(FACTORY_ID), eq(JUNE_3), anyCollection()))
                .thenReturn(List.of(demand("PT-BEEF", "beef shank", BOX_UNIT, BOX_UNIT, "1473")));
        when(productTypeRepository.findById("PT-BEEF")).thenReturn(Optional.of(productType("200", "1.0")));
        when(finishedGoodsBatchRepository.sumSaleableQuantityByProductTypeAndUnitExcludeRd(FACTORY_ID, "PT-BEEF", BOX_UNIT, WarehouseCodes.WH_RD))
                .thenReturn(new BigDecimal("1000"));
        when(semiFinishedInventoryRepository.sumAvailableByProduct(FACTORY_ID, "PT-BEEF")).thenReturn(BigDecimal.ZERO);
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq(FACTORY_ID), eq("PT-BEEF"), anyCollection()))
                .thenReturn(new BigDecimal("100"));

        RestockBoardDTO board = service.getRestockBoard(FACTORY_ID, JUNE_3);
        RestockRow row = board.getRows().get(0);

        assertEquals(0, new BigDecimal("373").compareTo(row.getShortfallQty()));
        assertEquals("SHORTFALL", row.getStatus());
        assertEquals(1, board.getSummary().getShortfallProducts());
    }

    @Test
    @DisplayName("WIP is converted with gramsPerUnit and wipToFgYield")
    void wipConversion() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq(FACTORY_ID), eq(JUNE_3), anyCollection()))
                .thenReturn(List.of(demand("PT-TONGUE", "pork tongue", BOX_UNIT, BOX_UNIT, "2000")));
        when(productTypeRepository.findById("PT-TONGUE")).thenReturn(Optional.of(productType("120", "0.9")));
        when(finishedGoodsBatchRepository.sumSaleableQuantityByProductTypeAndUnitExcludeRd(FACTORY_ID, "PT-TONGUE", BOX_UNIT, WarehouseCodes.WH_RD))
                .thenReturn(BigDecimal.ZERO);
        when(semiFinishedInventoryRepository.sumAvailableByProduct(FACTORY_ID, "PT-TONGUE"))
                .thenReturn(new BigDecimal("150"));
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq(FACTORY_ID), eq("PT-TONGUE"), anyCollection()))
                .thenReturn(BigDecimal.ZERO);

        RestockRow row = service.getRestockBoard(FACTORY_ID, JUNE_3).getRows().get(0);

        assertEquals(0, new BigDecimal("1125.00").compareTo(row.getWipEstimatedQty()));
        assertTrue(row.isWipIsEstimated());
    }

    @Test
    @DisplayName("missing gramsPerUnit does not silently estimate WIP")
    void missingGramsWarning() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq(FACTORY_ID), eq(JUNE_3), anyCollection()))
                .thenReturn(List.of(demand("PT-X", "x", BOX_UNIT, BOX_UNIT, "100")));
        when(productTypeRepository.findById("PT-X")).thenReturn(Optional.of(productType(null, null)));
        when(finishedGoodsBatchRepository.sumSaleableQuantityByProductTypeAndUnitExcludeRd(FACTORY_ID, "PT-X", BOX_UNIT, WarehouseCodes.WH_RD))
                .thenReturn(BigDecimal.ZERO);
        when(semiFinishedInventoryRepository.sumAvailableByProduct(FACTORY_ID, "PT-X"))
                .thenReturn(new BigDecimal("50"));
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq(FACTORY_ID), eq("PT-X"), anyCollection()))
                .thenReturn(BigDecimal.ZERO);

        RestockRow row = service.getRestockBoard(FACTORY_ID, JUNE_3).getRows().get(0);

        assertNull(row.getWipEstimatedQty());
        assertNotNull(row.getConversionWarning());
        assertTrue(row.getConversionWarning().contains("规格克重"));
    }

    @Test
    @DisplayName("horizon deducts daily demand in date order and keeps raw material estimate separate")
    void horizonDeductsAcrossDays() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq(FACTORY_ID), eq(JUNE_1), anyCollection()))
                .thenReturn(List.of(demand("PT-BEEF", "beef shank", BOX_UNIT, BOX_UNIT, "1912")));
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq(FACTORY_ID), eq(JUNE_2), anyCollection()))
                .thenReturn(List.of(demand("PT-BEEF", "beef shank", BOX_UNIT, BOX_UNIT, "159")));
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq(FACTORY_ID), eq(JUNE_3), anyCollection()))
                .thenReturn(List.of(demand("PT-BEEF", "beef shank", BOX_UNIT, BOX_UNIT, "1473")));
        when(productTypeRepository.findById("PT-BEEF")).thenReturn(Optional.of(productType("200", "0.8")));
        when(finishedGoodsBatchRepository.sumSaleableQuantityByProductTypeAndUnitExcludeRd(FACTORY_ID, "PT-BEEF", BOX_UNIT, WarehouseCodes.WH_RD))
                .thenReturn(new BigDecimal("1000"));
        when(semiFinishedInventoryRepository.sumAvailableByProduct(FACTORY_ID, "PT-BEEF"))
                .thenReturn(new BigDecimal("100"));
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq(FACTORY_ID), eq("PT-BEEF"), anyCollection()))
                .thenReturn(new BigDecimal("500"));
        when(conversionRepository.findByFactoryIdAndProductTypeId(FACTORY_ID, "PT-BEEF"))
                .thenReturn(List.of(conversion("MAT-BEEF", "beef raw", "kg", "2.0")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType(FACTORY_ID, "MAT-BEEF"))
                .thenReturn(new BigDecimal("100"));

        RestockHorizonDTO horizon = service.getRestockHorizon(FACTORY_ID, JUNE_1, JUNE_3);
        RestockHorizonProductRow row = horizon.getRows().get(0);

        assertEquals(3, horizon.getDates().size());
        assertEquals(0, new BigDecimal("3544").compareTo(row.getTotalDemandQty()));
        assertEquals(0, new BigDecimal("400.00").compareTo(row.getWipEstimatedQty()));
        assertEquals(0, new BigDecimal("200.0").compareTo(row.getRawEstimatedFgQty()));
        assertEquals(0, new BigDecimal("1900.00").compareTo(row.getStartingCoverQty()));
        assertEquals(0, new BigDecimal("1644.00").compareTo(row.getEndingShortfallQty()));
        assertEquals("SHORTFALL", row.getDays().get(2).getStatus());
    }

    @Test
    @DisplayName("horizon warns instead of choosing a random active conversion")
    void horizonWarnsOnMultipleActiveConversions() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq(FACTORY_ID), eq(JUNE_1), anyCollection()))
                .thenReturn(List.of(demand("PT-BEEF", "beef shank", BOX_UNIT, BOX_UNIT, "100")));
        when(productTypeRepository.findById("PT-BEEF")).thenReturn(Optional.of(productType("200", "0.8")));
        when(finishedGoodsBatchRepository.sumSaleableQuantityByProductTypeAndUnitExcludeRd(FACTORY_ID, "PT-BEEF", BOX_UNIT, WarehouseCodes.WH_RD))
                .thenReturn(BigDecimal.ZERO);
        when(semiFinishedInventoryRepository.sumAvailableByProduct(FACTORY_ID, "PT-BEEF"))
                .thenReturn(BigDecimal.ZERO);
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq(FACTORY_ID), eq("PT-BEEF"), anyCollection()))
                .thenReturn(BigDecimal.ZERO);
        when(conversionRepository.findByFactoryIdAndProductTypeId(FACTORY_ID, "PT-BEEF"))
                .thenReturn(List.of(
                        conversion("MAT-BEEF-1", "beef raw 1", "kg", "2.0"),
                        conversion("MAT-BEEF-2", "beef raw 2", "kg", "1.5")));

        RestockHorizonProductRow row = service.getRestockHorizon(FACTORY_ID, JUNE_1, JUNE_1).getRows().get(0);

        assertNull(row.getRawEstimatedFgQty());
        assertNotNull(row.getConversionWarning());
        assertTrue(row.getConversionWarning().contains("多个启用"));
    }

    @Test
    @DisplayName("T134: fgShippableQty reflects WH-LOG-only stock (WH-WKS batch not counted)")
    void fgShippableQtyWhLogOnly() {
        // Scenario: 200 boxes in WH-LOG (shippable) + 300 boxes in WH-WKS (non-shippable)
        // fgAvailableQty (all-warehouse) = 500; fgShippableQty (WH-LOG only) = 200
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq(FACTORY_ID), eq(JUNE_1), anyCollection()))
                .thenReturn(List.of(demand("PT-BEEF", "beef shank", BOX_UNIT, BOX_UNIT, "1912")));
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq(FACTORY_ID), eq(JUNE_2), anyCollection()))
                .thenReturn(List.of(demand("PT-BEEF", "beef shank", BOX_UNIT, BOX_UNIT, "159")));
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq(FACTORY_ID), eq(JUNE_3), anyCollection()))
                .thenReturn(List.of(demand("PT-BEEF", "beef shank", BOX_UNIT, BOX_UNIT, "0")));
        when(productTypeRepository.findById("PT-BEEF")).thenReturn(Optional.of(productType("200", "1.0")));
        // All-warehouse FG: 500 boxes (WH-LOG 200 + WH-WKS 300)
        when(finishedGoodsBatchRepository.sumSaleableQuantityByProductTypeAndUnitExcludeRd(FACTORY_ID, "PT-BEEF", BOX_UNIT, WarehouseCodes.WH_RD))
                .thenReturn(new BigDecimal("500"));
        // WH-LOG only: 200 boxes (WH-WKS 300 are NOT shippable)
        when(finishedGoodsBatchRepository.sumShippableQuantityByProductTypeAndWarehouseCodeAndUnit(
                FACTORY_ID, "PT-BEEF", WarehouseCodes.WH_LOG, BOX_UNIT))
                .thenReturn(new BigDecimal("200"));
        when(semiFinishedInventoryRepository.sumAvailableByProduct(FACTORY_ID, "PT-BEEF")).thenReturn(BigDecimal.ZERO);
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq(FACTORY_ID), eq("PT-BEEF"), anyCollection()))
                .thenReturn(BigDecimal.ZERO);
        when(conversionRepository.findByFactoryIdAndProductTypeId(FACTORY_ID, "PT-BEEF"))
                .thenReturn(List.of());

        RestockHorizonProductRow row = service.getRestockHorizon(FACTORY_ID, JUNE_1, JUNE_3).getRows().get(0);

        // fgAvailableQty counts all warehouses (WH-LOG + WH-WKS = 500)
        assertEquals(0, new BigDecimal("500").compareTo(row.getFgAvailableQty()),
                "fgAvailableQty should count all warehouses (WH-LOG + WH-WKS)");
        // fgShippableQty counts WH-LOG only (200)
        assertEquals(0, new BigDecimal("200").compareTo(row.getFgShippableQty()),
                "fgShippableQty must reflect WH-LOG-only stock; WH-WKS batch must NOT be counted");
        // startingCoverQty uses fgAvailableQty (not shippable), so coverage formula is unchanged
        assertEquals(0, new BigDecimal("500").compareTo(row.getStartingCoverQty()),
                "startingCoverQty (coverage formula) must be unchanged — uses fgAvailableQty not fgShippableQty");
    }

    @Test
    @DisplayName("horizon rejects inverted date range")
    void horizonRejectsInvertedRange() {
        try {
            service.getRestockHorizon(FACTORY_ID, JUNE_3, JUNE_1);
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("endDate"));
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private ProductDemandProjection demand(
            String id,
            String name,
            String minUnit,
            String maxUnit,
            String quantity) {
        return new ProductDemandProjection() {
            @Override public String getProductTypeId() { return id; }
            @Override public String getProductName() { return name; }
            @Override public String getMinUnit() { return minUnit; }
            @Override public String getMaxUnit() { return maxUnit; }
            @Override public BigDecimal getDemand() { return new BigDecimal(quantity); }
        };
    }

    private ProductType productType(String gramsPerUnit, String wipToFgYield) {
        ProductType productType = new ProductType();
        productType.setGramsPerUnit(gramsPerUnit == null ? null : new BigDecimal(gramsPerUnit));
        productType.setWipToFgYield(wipToFgYield == null ? null : new BigDecimal(wipToFgYield));
        return productType;
    }

    private MaterialProductConversion conversion(
            String materialTypeId,
            String materialName,
            String materialUnit,
            String conversionRate) {
        RawMaterialType materialType = new RawMaterialType();
        materialType.setId(materialTypeId);
        materialType.setName(materialName);
        materialType.setUnit(materialUnit);

        MaterialProductConversion conversion = new MaterialProductConversion();
        conversion.setMaterialTypeId(materialTypeId);
        conversion.setMaterialType(materialType);
        conversion.setConversionRate(new BigDecimal(conversionRate));
        conversion.setIsActive(true);
        return conversion;
    }
}
