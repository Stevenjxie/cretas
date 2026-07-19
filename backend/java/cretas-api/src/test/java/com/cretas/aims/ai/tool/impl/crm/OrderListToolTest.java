package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.service.inventory.SalesService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderListToolTest {

    @Test
    void listsCanonicalSalesOrders() {
        SalesService salesService = mock(SalesService.class);
        SalesOrder order = new SalesOrder();
        order.setId("SO-ID-1");
        order.setOrderNumber("SO-001");
        PageResponse<SalesOrder> page = PageResponse.of(List.of(order), 1, 20, 1L);
        when(salesService.getSalesOrders("F001", 1, 20)).thenReturn(page);

        Map<String, Object> result = new OrderListTool(salesService)
                .doExecute("F001", Map.of(), Map.of());

        assertThat(result.get("source")).isEqualTo("sales_orders");
        assertThat(result.get("orders")).isEqualTo(List.of(order));
        assertThat(result.get("total")).isEqualTo(1L);
        verify(salesService).getSalesOrders("F001", 1, 20);
    }
}
