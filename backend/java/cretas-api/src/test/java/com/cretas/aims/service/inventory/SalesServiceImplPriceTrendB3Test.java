package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.SalesPriceTrendDTO;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * B3: getProductPriceTrend — 财审售价趋势接口单元测试.
 *
 * <p>验证:
 * <ul>
 *   <li>结果正确映射 repository projection → DTO</li>
 *   <li>DRAFT / CANCELLED 订单被排除 (excluded statuses 正确传递)</li>
 *   <li>limit 上限被裁切 (max 20)</li>
 *   <li>无历史记录时返回空列表</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("B3: getProductPriceTrend 单元测试")
class SalesServiceImplPriceTrendB3Test {

    @Mock
    private SalesOrderItemRepository salesOrderItemRepository;

    private SalesServiceImpl salesService;

    private static final String FACTORY_ID = "F001";
    private static final String PRODUCT_ID = "PT-001";

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                null,
                salesOrderItemRepository,
                null, null, null, null, null, null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helper: minimal PriceTrendRow projection implementation
    // ─────────────────────────────────────────────────────────────────────

    private SalesOrderItemRepository.PriceTrendRow makeRow(
            String orderNum, LocalDate date, BigDecimal price, BigDecimal qty, String unit) {
        return new SalesOrderItemRepository.PriceTrendRow() {
            @Override public String getOrderNumber() { return orderNum; }
            @Override public LocalDate getOrderDate() { return date; }
            @Override public BigDecimal getUnitPrice() { return price; }
            @Override public BigDecimal getQuantity() { return qty; }
            @Override public String getUnit() { return unit; }
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("正常路径 — 两条历史记录正确映射为 DTO")
    void getProductPriceTrend_twoRows_mapsCorrectly() {
        List<SalesOrderItemRepository.PriceTrendRow> rows = List.of(
                makeRow("SO-20260520-001", LocalDate.of(2026, 5, 20),
                        new BigDecimal("9.50"), new BigDecimal("100"), "kg"),
                makeRow("SO-20260510-002", LocalDate.of(2026, 5, 10),
                        new BigDecimal("8.80"), new BigDecimal("200"), "kg"));

        when(salesOrderItemRepository.findRecentPriceByProduct(
                eq(FACTORY_ID), eq(PRODUCT_ID), anyCollection(), any(Pageable.class)))
                .thenReturn(rows);

        List<SalesPriceTrendDTO> result =
                salesService.getProductPriceTrend(FACTORY_ID, PRODUCT_ID, 10);

        assertEquals(2, result.size());
        assertEquals("SO-20260520-001", result.get(0).getOrderNumber());
        assertEquals(LocalDate.of(2026, 5, 20), result.get(0).getOrderDate());
        assertEquals(new BigDecimal("9.50"), result.get(0).getUnitPrice());
        assertEquals(new BigDecimal("100"), result.get(0).getQuantity());
        assertEquals("kg", result.get(0).getUnit());
    }

    @Test
    @DisplayName("DRAFT / CANCELLED 状态被排除 — excludedStatuses 包含这两个状态")
    @SuppressWarnings("unchecked")
    void getProductPriceTrend_excludesDraftAndCancelled() {
        when(salesOrderItemRepository.findRecentPriceByProduct(
                any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        salesService.getProductPriceTrend(FACTORY_ID, PRODUCT_ID, 5);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<SalesOrderStatus>> statusCaptor =
                ArgumentCaptor.forClass(Collection.class);
        verify(salesOrderItemRepository).findRecentPriceByProduct(
                eq(FACTORY_ID), eq(PRODUCT_ID), statusCaptor.capture(), any(Pageable.class));

        Collection<SalesOrderStatus> excluded = statusCaptor.getValue();
        assertTrue(excluded.contains(SalesOrderStatus.DRAFT),
                "DRAFT must be excluded");
        assertTrue(excluded.contains(SalesOrderStatus.CANCELLED),
                "CANCELLED must be excluded");
    }

    @Test
    @DisplayName("limit 被裁切到 max 20 — 传入 100 只请求 20")
    void getProductPriceTrend_limitCappedAt20() {
        when(salesOrderItemRepository.findRecentPriceByProduct(
                any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        salesService.getProductPriceTrend(FACTORY_ID, PRODUCT_ID, 100);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(salesOrderItemRepository).findRecentPriceByProduct(
                any(), any(), any(), pageableCaptor.capture());

        assertEquals(20, pageableCaptor.getValue().getPageSize(),
                "limit must be capped at 20 for safety");
    }

    @Test
    @DisplayName("无历史记录 → 返回空列表")
    void getProductPriceTrend_noHistory_returnsEmptyList() {
        when(salesOrderItemRepository.findRecentPriceByProduct(
                any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        List<SalesPriceTrendDTO> result =
                salesService.getProductPriceTrend(FACTORY_ID, PRODUCT_ID, 10);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
