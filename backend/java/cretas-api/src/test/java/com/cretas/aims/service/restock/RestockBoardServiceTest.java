package com.cretas.aims.service.restock;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ProductDemandProjection;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.restock.dto.RestockBoardDTO;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RestockBoardService 聚合")
class RestockBoardServiceTest {

    @Mock SalesOrderItemRepository salesOrderItemRepository;
    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    @Mock ProductionPlanRepository productionPlanRepository;
    @Mock ProductTypeRepository productTypeRepository;
    @InjectMocks RestockBoardService service;

    private static final LocalDate D = LocalDate.of(2026, 6, 3);

    private ProductDemandProjection demand(String id, String name, String minU, String maxU, String qty) {
        return new ProductDemandProjection() {
            public String getProductTypeId() { return id; }
            public String getProductName() { return name; }
            public String getMinUnit() { return minU; }
            public String getMaxUnit() { return maxU; }
            public BigDecimal getDemand() { return new BigDecimal(qty); }
        };
    }

    private ProductType pt(String id, String grams, String yield) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setGramsPerUnit(grams == null ? null : new BigDecimal(grams));
        p.setWipToFgYield(yield == null ? null : new BigDecimal(yield));
        return p;
    }

    @Test
    @DisplayName("缺口: 需求7088, 成品1000+在产0+已排产2000 → 缺口4088 SHORTFALL")
    void shortfall() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(demand("PT-ZT", "猪蹄200g", "盒", "盒", "7088")));
        when(productTypeRepository.findById("PT-ZT")).thenReturn(Optional.of(pt("PT-ZT", "200", null)));
        when(finishedGoodsBatchRepository.sumAvailableQuantityByProductType("F006", "PT-ZT")).thenReturn(new BigDecimal("1000"));
        when(semiFinishedInventoryRepository.sumAvailableByProduct("F006", "PT-ZT")).thenReturn(BigDecimal.ZERO);
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq("F006"), eq("PT-ZT"), anyCollection())).thenReturn(new BigDecimal("2000"));

        RestockBoardDTO board = service.getRestockBoard("F006", D);
        assertEquals(1, board.getRows().size());
        RestockRow r = board.getRows().get(0);
        assertEquals(0, new BigDecimal("4088").compareTo(r.getShortfallQty()));
        assertEquals("SHORTFALL", r.getStatus());
        assertEquals(1, board.getSummary().getShortfallProducts());
    }

    @Test
    @DisplayName("满足: 合计>=需求 → 缺口0 SATISFIED")
    void satisfied() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(demand("PT-ZS", "猪舌120g", "盒", "盒", "625")));
        when(productTypeRepository.findById("PT-ZS")).thenReturn(Optional.of(pt("PT-ZS", "120", "1.0")));
        when(finishedGoodsBatchRepository.sumAvailableQuantityByProductType("F006", "PT-ZS")).thenReturn(new BigDecimal("700"));
        when(semiFinishedInventoryRepository.sumAvailableByProduct("F006", "PT-ZS")).thenReturn(BigDecimal.ZERO);
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq("F006"), eq("PT-ZS"), anyCollection())).thenReturn(BigDecimal.ZERO);

        RestockRow r = service.getRestockBoard("F006", D).getRows().get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getShortfallQty()));
        assertEquals("SATISFIED", r.getStatus());
    }

    @Test
    @DisplayName("WIP折盒: 150kg × yield0.9 / 120g每盒 = 1125盒 计入可用")
    void wipFold() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(demand("PT-ZS", "猪舌120g", "盒", "盒", "2000")));
        when(productTypeRepository.findById("PT-ZS")).thenReturn(Optional.of(pt("PT-ZS", "120", "0.9")));
        when(finishedGoodsBatchRepository.sumAvailableQuantityByProductType("F006", "PT-ZS")).thenReturn(BigDecimal.ZERO);
        when(semiFinishedInventoryRepository.sumAvailableByProduct("F006", "PT-ZS")).thenReturn(new BigDecimal("150"));
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq("F006"), eq("PT-ZS"), anyCollection())).thenReturn(BigDecimal.ZERO);

        RestockRow r = service.getRestockBoard("F006", D).getRows().get(0);
        assertEquals(0, new BigDecimal("1125.00").compareTo(r.getWipEstimatedQty()));
        assertTrue(r.isWipIsEstimated());
    }

    @Test
    @DisplayName("gramsPerUnit null + 有WIP → wip列null + 警告, 不静默算错")
    void noGramsWarning() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(demand("PT-X", "X", "盒", "盒", "100")));
        when(productTypeRepository.findById("PT-X")).thenReturn(Optional.of(pt("PT-X", null, null)));
        when(finishedGoodsBatchRepository.sumAvailableQuantityByProductType("F006", "PT-X")).thenReturn(BigDecimal.ZERO);
        when(semiFinishedInventoryRepository.sumAvailableByProduct("F006", "PT-X")).thenReturn(new BigDecimal("50"));
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq("F006"), eq("PT-X"), anyCollection())).thenReturn(BigDecimal.ZERO);

        RestockRow r = service.getRestockBoard("F006", D).getRows().get(0);
        assertNull(r.getWipEstimatedQty());
        assertNotNull(r.getConversionWarning());
        assertTrue(r.getConversionWarning().contains("gramsPerUnit"));
    }

    @Test
    @DisplayName("F2 单位不一致 → demandQty null + UNIT_INCONSISTENT, 不累加")
    void unitInconsistent() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(demand("PT-X", "X", "盒", "箱", "12")));
        when(productTypeRepository.findById("PT-X")).thenReturn(Optional.of(pt("PT-X", "120", null)));

        RestockRow r = service.getRestockBoard("F006", D).getRows().get(0);
        assertNull(r.getDemandQty());
        assertNull(r.getShortfallQty());
        assertEquals("UNIT_INCONSISTENT", r.getStatus());
        assertNotNull(r.getConversionWarning());
    }

    @Test
    @DisplayName("无订单 → 空看板")
    void empty() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of());
        RestockBoardDTO board = service.getRestockBoard("F006", D);
        assertTrue(board.getRows().isEmpty());
        assertEquals(0, board.getSummary().getTotalProducts());
    }
}
