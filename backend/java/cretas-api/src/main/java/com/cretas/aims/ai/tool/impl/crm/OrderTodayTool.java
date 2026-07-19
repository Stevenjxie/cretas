package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Dedicated canonical implementation for the ORDER_TODAY intent. */
@Component
@RequiredArgsConstructor
public class OrderTodayTool extends AbstractBusinessTool {

    private final SalesOrderRepository salesOrderRepository;

    @Override
    public String getToolName() {
        return "order_today";
    }

    @Override
    public String getDescription() {
        return "查询今天创建的销售订单，数据仅来自 sales_orders。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Collections.emptyMap(),
                "required", Collections.emptyList());
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        LocalDate today = LocalDate.now();
        List<SalesOrder> orders = salesOrderRepository.findByFactoryIdAndDateRange(factoryId, today, today);
        return Map.of(
                "orders", orders,
                "total", orders.size(),
                "orderDate", today,
                "source", "sales_orders",
                "message", "查询到 " + orders.size() + " 个今日销售订单");
    }
}
