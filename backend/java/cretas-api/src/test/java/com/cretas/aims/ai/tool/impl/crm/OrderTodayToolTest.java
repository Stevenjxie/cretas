package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderTodayToolTest {

    @Test
    void readsTodaysOrdersFromCanonicalRepository() {
        SalesOrderRepository repository = mock(SalesOrderRepository.class);
        LocalDate today = LocalDate.now();
        SalesOrder order = new SalesOrder();
        order.setId("SO-ID-1");
        when(repository.findByFactoryIdAndDateRange("F001", today, today)).thenReturn(List.of(order));

        Map<String, Object> result = new OrderTodayTool(repository)
                .doExecute("F001", Map.of(), Map.of());

        assertThat(result.get("source")).isEqualTo("sales_orders");
        assertThat(result.get("total")).isEqualTo(1);
        verify(repository).findByFactoryIdAndDateRange(eq("F001"), eq(today), eq(today));
    }
}
