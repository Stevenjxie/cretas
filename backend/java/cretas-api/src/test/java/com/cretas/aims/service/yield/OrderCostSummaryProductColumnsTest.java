package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.OrderCostSummaryRowDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 客户反馈 (Google Sheet 2026-07-17, 成本汇总): 「标签已新增，但数据显示为"—"」.
 *
 * <p>根因: 前端 M67CostSummary.vue 渲染了「产品」「SKU」两列, 但后端
 * {@link OrderCostSummaryRowDTO} 从来没有这两个字段 —— 结构性永远是"—", 与数据无关.
 *
 * <p>两个易错点 (线上实测):
 * <ul>
 *   <li>{@code OrderCostBreakdownDTO.productSku} 实为 product_type_id = UUID
 *       (890967c2-c87c-44ca-86da-785f872a8201), 直接透传会让操作员看到一串乱码,
 *       真正的 SKU 编码在 {@code product_types.code} (CPF0060020).</li>
 *   <li>批次快照名与主数据名会漂移 (F006 SO-20260716-0002 快照"牛排" vs 主数据"羊排"),
 *       按 T159-B 惯例取实时主数据名.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("成本汇总 产品/SKU 列 (Google Sheet 2026-07-17)")
class OrderCostSummaryProductColumnsTest {

    private static final String FACTORY = "F006";
    private static final String ORDER_ID = "order-1";
    private static final String PRODUCT_TYPE_ID = "890967c2-c87c-44ca-86da-785f872a8201";

    @Mock SalesOrderRepository salesOrderRepository;
    @Mock OrderCostBreakdownService orderCostBreakdownService;
    @Mock YieldReportService yieldReportService;
    @Mock ProductTypeRepository productTypeRepository;

    @InjectMocks OrderCostSummaryService service;

    @BeforeEach
    void setup() {
        SalesOrder so = new SalesOrder();
        so.setId(ORDER_ID);
        so.setOrderNumber("SO-20260729-0001");
        so.setOrderDate(LocalDate.of(2026, 7, 29));
        lenient().when(salesOrderRepository.findByFactoryIdAndDateRange(eq(FACTORY), any(), any()))
                .thenReturn(List.of(so));
        lenient().when(yieldReportService.getOrderYieldSummary(anyString(), anyString())).thenReturn(null);
    }

    private void givenBreakdown(String productTypeId, String snapshotName) {
        OrderCostBreakdownDTO cb = OrderCostBreakdownDTO.builder()
                .orderId(ORDER_ID)
                .hasData(true)
                .productSku(productTypeId)
                .productName(snapshotName)
                .boxCount(10)
                .build();
        when(orderCostBreakdownService.compute(eq(FACTORY), eq(ORDER_ID), eq(false))).thenReturn(cb);
    }

    private OrderCostSummaryRowDTO firstRow() {
        List<OrderCostSummaryRowDTO> rows = service.summarize(
                FACTORY, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 30), false);
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    @Test
    @DisplayName("SKU 列给出 product_types.code, 而不是 UUID 形态的 productTypeId")
    void skuColumn_shouldCarryHumanReadableCode_notUuid() {
        givenBreakdown(PRODUCT_TYPE_ID, "SOP-20260729-01-黄油鸡-成品800g");
        ProductType pt = new ProductType();
        pt.setId(PRODUCT_TYPE_ID);
        pt.setCode("CPF0060020");
        pt.setName("SOP-20260729-01-黄油鸡-成品800g");
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT_TYPE_ID, FACTORY)).thenReturn(Optional.of(pt));

        OrderCostSummaryRowDTO row = firstRow();

        assertEquals("CPF0060020", row.getSkuCode(), "SKU 列必须是编码, 不能是 UUID");
        assertEquals("SOP-20260729-01-黄油鸡-成品800g", row.getProductName());
    }

    @Test
    @DisplayName("批次快照名与主数据漂移时, 取主数据实时名 (线上牛排/羊排案例)")
    void productName_shouldPreferLiveMasterDataOverBatchSnapshot() {
        givenBreakdown(PRODUCT_TYPE_ID, "SHH0713香辣孜然牛排");   // 批次快照 (已过期)
        ProductType pt = new ProductType();
        pt.setId(PRODUCT_TYPE_ID);
        pt.setCode("CPSHHKH0142");
        pt.setName("SHH0713香辣孜然羊排");                         // 主数据实时值
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT_TYPE_ID, FACTORY)).thenReturn(Optional.of(pt));

        assertEquals("SHH0713香辣孜然羊排", firstRow().getProductName(),
                "应取主数据实时名, 而不是已漂移的批次快照名");
    }

    @Test
    @DisplayName("订单跨多个产品时 compute 返回 null productSku → 两列保持空, 不挑一个冒充")
    void mixedProductOrder_shouldLeaveColumnsNull() {
        givenBreakdown(null, null);   // uniqueOrNull 已在 compute 里返回 null

        OrderCostSummaryRowDTO row = firstRow();

        assertNull(row.getSkuCode(), "跨产品订单不得伪造 SKU");
        assertNull(row.getProductName(), "跨产品订单不得挑一个产品名冒充整张订单");
    }

    @Test
    @DisplayName("产品主数据已被删除时回退批次快照名, 但不编造 SKU 编码")
    void missingProductType_shouldFallBackToSnapshotNameOnly() {
        givenBreakdown(PRODUCT_TYPE_ID, "SHH0713香辣孜然牛排");
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT_TYPE_ID, FACTORY)).thenReturn(Optional.empty());

        OrderCostSummaryRowDTO row = firstRow();

        assertEquals("SHH0713香辣孜然牛排", row.getProductName(), "主数据没了仍应给出快照名而不是空白");
        assertNull(row.getSkuCode(), "查不到主数据就没有可信的 SKU 编码, 不能拿 UUID 顶替");
    }
}
