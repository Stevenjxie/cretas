package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.SalesService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderQueryToolTest {

    @Test
    void queriesCanonicalSalesOrderById() {
        SalesService salesService = mock(SalesService.class);
        SalesOrderRepository repository = mock(SalesOrderRepository.class);
        SalesOrder order = new SalesOrder();
        order.setId("SO-ID-1");
        order.setOrderNumber("SO-001");
        when(salesService.getSalesOrderById("F001", "SO-ID-1")).thenReturn(order);

        Map<String, Object> result = new OrderQueryTool(salesService, repository)
                .doExecute("F001", Map.of("orderId", "SO-ID-1"), Map.of());

        assertThat(result.get("source")).isEqualTo("sales_orders");
        assertThat(result.get("order")).isSameAs(order);
        verify(salesService).getSalesOrderById("F001", "SO-ID-1");
    }
}
