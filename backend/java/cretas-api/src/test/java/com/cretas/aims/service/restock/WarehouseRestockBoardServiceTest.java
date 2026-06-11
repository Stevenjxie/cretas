package com.cretas.aims.service.restock;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ProductWarehouseDemandProjection;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.restock.dto.WarehouseRestockBoardDTO;
import com.cretas.aims.service.restock.dto.WarehouseRestockRow;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P3 多仓备货看板 service 层聚合逻辑 — Mockito 单测。
 *
 * <p>Covers:
 * <ol>
 *   <li>warehouseDemandSplit — 同产品多仓需求独立行</li>
 *   <li>warehouseShortfall — 该仓需求 > 全厂可用 → SHORTFALL</li>
 *   <li>warehouseSatisfied — 全厂可用 >= 最大单仓需求 → SATISFIED</li>
 *   <li>未分仓桶 — null destWarehouseCode 归入"未分仓"</li>
 *   <li>summaryWarehouseCount — 3 个不同目的仓 → warehouseCount=3</li>
 *   <li>emptyBoard — 无订单 → 空看板</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WarehouseRestockBoardService 多仓聚合")
class WarehouseRestockBoardServiceTest {

    @Mock SalesOrderItemRepository salesOrderItemRepository;
    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    @Mock ProductionPlanRepository productionPlanRepository;
    @Mock ProductTypeRepository productTypeRepository;
    @InjectMocks RestockBoardService service;

    private static final LocalDate D = LocalDate.of(2026, 9, 15);

    // ---- helpers ----

    private ProductWarehouseDemandProjection demand(
            String productTypeId, String productName,
            String destCode, String destName,
            String minU, String maxU, String qty) {
        return new ProductWarehouseDemandProjection() {
            public String getProductTypeId()    { return productTypeId; }
            public String getProductName()      { return productName; }
            public String getDestWarehouseCode(){ return destCode; }
            public String getDestWarehouseName(){ return destName; }
            public String getMinUnit()          { return minU; }
            public String getMaxUnit()          { return maxU; }
            public BigDecimal getDemand()       { return new BigDecimal(qty); }
        };
    }

    private ProductType pt(String id, String grams, String yield) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setGramsPerUnit(grams == null ? null : new BigDecimal(grams));
        p.setWipToFgYield(yield == null ? null : new BigDecimal(yield));
        return p;
    }

    // ---- tests ----

    @Test
    @DisplayName("多仓需求独立行: 猪舌北仑531+浦东200 → 2行, 不合并")
    void warehouseDemandSplit() {
        when(salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(
                        demand("PT-ZS", "猪舌120g", "WH-BEILUN", "北仑总仓", "盒", "盒", "531"),
                        demand("PT-ZS", "猪舌120g", "WH-PUDONG",  "浦东总仓",  "盒", "盒", "200")));
        when(productTypeRepository.findById("PT-ZS"))
                .thenReturn(Optional.of(pt("PT-ZS", "120", "1.0")));
        when(finishedGoodsBatchRepository.sumSaleableQuantityByProductTypeAndUnitExcludeRd(anyString(), anyString(), eq("盒"), eq(WarehouseCodes.WH_RD)))
                .thenReturn(new BigDecimal("1000"));
        when(semiFinishedInventoryRepository.sumAvailableByProduct(anyString(), anyString()))
                .thenReturn(BigDecimal.ZERO);
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(anyString(), anyString(), anyCollection()))
                .thenReturn(BigDecimal.ZERO);

        WarehouseRestockBoardDTO board = service.getRestockBoardByWarehouse("F006", D);

        assertEquals(2, board.getRows().size(), "2 行");
        WarehouseRestockRow beilun = board.getRows().stream()
                .filter(r -> "WH-BEILUN".equals(r.getDestWarehouseCode())).findFirst().orElseThrow();
        WarehouseRestockRow pudong = board.getRows().stream()
                .filter(r -> "WH-PUDONG".equals(r.getDestWarehouseCode())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("531").compareTo(beilun.getWarehouseDemandQty()));
        assertEquals(0, new BigDecimal("200").compareTo(pudong.getWarehouseDemandQty()));
    }

    @Test
    @DisplayName("该仓需求 > 全厂可用 → SHORTFALL (缺口=仓需求-全厂合计)")
    void warehouseShortfall() {
        when(salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(
                        demand("PT-ZS", "猪舌120g", "WH-BEILUN", "北仑", "盒", "盒", "2000")));
        when(productTypeRepository.findById("PT-ZS"))
                .thenReturn(Optional.of(pt("PT-ZS", "120", null)));
        when(finishedGoodsBatchRepository.sumSaleableQuantityByProductTypeAndUnitExcludeRd("F006", "PT-ZS", "盒", WarehouseCodes.WH_RD))
                .thenReturn(new BigDecimal("500"));
        when(semiFinishedInventoryRepository.sumAvailableByProduct("F006", "PT-ZS"))
                .thenReturn(BigDecimal.ZERO);
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq("F006"), eq("PT-ZS"), anyCollection()))
                .thenReturn(new BigDecimal("1000"));

        WarehouseRestockRow r = service.getRestockBoardByWarehouse("F006", D).getRows().get(0);
        assertEquals("SHORTFALL", r.getStatus());
        // 缺口 = 2000 - (500+0+1000) = 500
        assertEquals(0, new BigDecimal("500").compareTo(r.getShortfallQty()));
        // 全厂可用 = 1500
        assertEquals(0, new BigDecimal("1500").compareTo(r.getTotalAvailableQty()));
    }

    @Test
    @DisplayName("全厂可用 >= 该仓需求 → SATISFIED 缺口0")
    void warehouseSatisfied() {
        when(salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(
                        demand("PT-ZS", "猪舌120g", "WH-BEILUN", "北仑", "盒", "盒", "531")));
        when(productTypeRepository.findById("PT-ZS"))
                .thenReturn(Optional.of(pt("PT-ZS", "120", "1.0")));
        when(finishedGoodsBatchRepository.sumSaleableQuantityByProductTypeAndUnitExcludeRd("F006", "PT-ZS", "盒", WarehouseCodes.WH_RD))
                .thenReturn(new BigDecimal("800"));
        when(semiFinishedInventoryRepository.sumAvailableByProduct("F006", "PT-ZS"))
                .thenReturn(BigDecimal.ZERO);
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(anyString(), anyString(), anyCollection()))
                .thenReturn(BigDecimal.ZERO);

        WarehouseRestockRow r = service.getRestockBoardByWarehouse("F006", D).getRows().get(0);
        assertEquals("SATISFIED", r.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getShortfallQty()));
    }

    @Test
    @DisplayName("未分仓桶: destWarehouseCode='未分仓' 正确映射, destWarehouseName可null")
    void unassignedWarehouseBucket() {
        when(salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(
                        demand("PT-ZS", "猪舌120g", "未分仓", null, "盒", "盒", "300")));
        when(productTypeRepository.findById("PT-ZS"))
                .thenReturn(Optional.of(pt("PT-ZS", "120", null)));
        when(finishedGoodsBatchRepository.sumSaleableQuantityByProductTypeAndUnitExcludeRd("F006", "PT-ZS", "盒", WarehouseCodes.WH_RD))
                .thenReturn(BigDecimal.ZERO);
        when(semiFinishedInventoryRepository.sumAvailableByProduct("F006", "PT-ZS"))
                .thenReturn(BigDecimal.ZERO);
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(anyString(), anyString(), anyCollection()))
                .thenReturn(BigDecimal.ZERO);

        WarehouseRestockRow r = service.getRestockBoardByWarehouse("F006", D).getRows().get(0);
        assertEquals("未分仓", r.getDestWarehouseCode());
        assertNull(r.getDestWarehouseName(), "未分仓桶的 destWarehouseName 可为 null");
        assertEquals("SHORTFALL", r.getStatus(), "需求300 > 可用0 → SHORTFALL");
    }

    @Test
    @DisplayName("summary.warehouseCount: 3 不同仓 → warehouseCount=3")
    void summaryWarehouseCount() {
        when(salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(
                        demand("PT-ZS", "猪舌120g", "WH-A", "A仓", "盒", "盒", "100"),
                        demand("PT-ZS", "猪舌120g", "WH-B", "B仓", "盒", "盒", "200"),
                        demand("PT-ZS", "猪舌120g", "WH-C", "C仓", "盒", "盒", "300")));
        when(productTypeRepository.findById("PT-ZS"))
                .thenReturn(Optional.of(pt("PT-ZS", "120", null)));
        when(finishedGoodsBatchRepository.sumSaleableQuantityByProductTypeAndUnitExcludeRd(anyString(), anyString(), eq("盒"), eq(WarehouseCodes.WH_RD)))
                .thenReturn(BigDecimal.ZERO);
        when(semiFinishedInventoryRepository.sumAvailableByProduct(anyString(), anyString()))
                .thenReturn(BigDecimal.ZERO);
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(anyString(), anyString(), anyCollection()))
                .thenReturn(BigDecimal.ZERO);

        WarehouseRestockBoardDTO board = service.getRestockBoardByWarehouse("F006", D);
        assertEquals(3, board.getSummary().getWarehouseCount());
        assertEquals(3, board.getSummary().getTotalRows());
        assertEquals(3, board.getSummary().getShortfallRows());
        assertEquals(0, board.getSummary().getSatisfiedRows());
    }

    @Test
    @DisplayName("空看板: 无订单 → rows空, summary全0")
    void emptyBoard() {
        when(salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of());

        WarehouseRestockBoardDTO board = service.getRestockBoardByWarehouse("F006", D);
        assertTrue(board.getRows().isEmpty());
        assertEquals(0, board.getSummary().getTotalRows());
        assertEquals(0, board.getSummary().getWarehouseCount());
    }
}
