package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.service.inventory.SalesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lists canonical sales orders. The historical work_orders table is not consulted. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderListTool extends AbstractBusinessTool {

    private final SalesService salesService;

    @Override
    public String getToolName() {
        return "order_list";
    }

    @Override
    public String getDescription() {
        return "查询销售订单列表，数据来自 sales_orders；支持分页和销售订单状态筛选。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("page", Map.of(
                "type", "integer",
                "description", "页码（从1开始）",
                "default", 1));
        properties.put("size", Map.of(
                "type", "integer",
                "description", "每页数量",
                "default", 20));
        properties.put("status", Map.of(
                "type", "string",
                "description", "销售订单状态，例如 DRAFT、CONFIRMED、PROCESSING、COMPLETED、CANCELLED"));
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
        int page = Math.max(1, getInteger(params, "page", 1));
        int size = Math.max(1, Math.min(100, getInteger(params, "size", 20)));
        String statusValue = getString(params, "status");

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

        log.info("查询销售订单列表 - factoryId={}, page={}, size={}, status={}",
                factoryId, page, size, statusValue);
        Map<String, Object> result = new HashMap<>();
        result.put("orders", orders.getContent());
        result.put("total", orders.getTotalElements());
        result.put("page", orders.getPage());
        result.put("size", orders.getSize());
        result.put("totalPages", orders.getTotalPages());
        result.put("source", "sales_orders");
        result.put("message", "查询到 " + orders.getTotalElements() + " 个销售订单");
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
