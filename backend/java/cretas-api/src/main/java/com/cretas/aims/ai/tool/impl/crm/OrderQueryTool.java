package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.SalesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Queries canonical sales orders by ID/number, status, or today's order date. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderQueryTool extends AbstractBusinessTool {

    private final SalesService salesService;
    private final SalesOrderRepository salesOrderRepository;

    @Override
    public String getToolName() {
        return "order_query";
    }

    @Override
    public String getDescription() {
        return "查询销售订单状态或详情，数据来自 sales_orders；支持订单ID、订单编号、状态和今日订单。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("orderId", Map.of(
                "type", "string",
                "description", "销售订单ID或订单编号"));
        properties.put("queryType", Map.of(
                "type", "string",
                "description", "ORDER_STATUS、ORDER_DETAIL 或 ORDER_TODAY",
                "default", "ORDER_STATUS"));
        properties.put("status", Map.of(
                "type", "string",
                "description", "可选销售订单状态"));
        properties.put("page", Map.of("type", "integer", "default", 1));
        properties.put("size", Map.of("type", "integer", "default", 20));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", Collections.emptyList());
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        String orderId = getString(params, "orderId");
        String queryType = getString(params, "queryType", "ORDER_STATUS");
        String statusValue = getString(params, "status");
        int page = Math.max(1, getInteger(params, "page", 1));
        int size = Math.max(1, Math.min(100, getInteger(params, "size", 20)));

        Map<String, Object> result = new HashMap<>();
        result.put("queryType", queryType);
        result.put("source", "sales_orders");

        if (orderId != null && !orderId.isBlank()) {
            SalesOrder order = resolveOrder(factoryId, orderId);
            result.put("order", order);
            result.put("message", "销售订单查询成功");
            return result;
        }

        if ("ORDER_TODAY".equalsIgnoreCase(queryType)) {
            LocalDate today = LocalDate.now();
            List<SalesOrder> orders = salesOrderRepository.findByFactoryIdAndDateRange(factoryId, today, today);
            result.put("orders", orders);
            result.put("total", orders.size());
            result.put("orderDate", today);
            result.put("message", "查询到 " + orders.size() + " 个今日销售订单");
            return result;
        }

        PageResponse<SalesOrder> orders;
        if (statusValue == null || statusValue.isBlank()) {
            orders = salesService.getSalesOrders(factoryId, page, size);
        } else {
            SalesOrderStatus status;
            try {
                status = SalesOrderStatus.valueOf(statusValue.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("不支持的销售订单状态: " + statusValue);
            }
            orders = salesService.getSalesOrdersByStatus(factoryId, status, page, size);
        }
        result.put("orders", orders.getContent());
        result.put("total", orders.getTotalElements());
        result.put("page", orders.getPage());
        result.put("size", orders.getSize());
        result.put("totalPages", orders.getTotalPages());
        result.put("message", "销售订单查询完成，共 " + orders.getTotalElements() + " 条");
        log.info("查询销售订单 - factoryId={}, queryType={}, status={}", factoryId, queryType, statusValue);
        return result;
    }

    private SalesOrder resolveOrder(String factoryId, String idOrNumber) {
        try {
            return salesService.getSalesOrderById(factoryId, idOrNumber);
        } catch (ResourceNotFoundException notFoundById) {
            return salesOrderRepository.findByFactoryIdAndOrderNumber(factoryId, idOrNumber)
                    .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在: " + idOrNumber));
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
