package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.SalesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cancels or deletes canonical sales orders; never writes the historical work_orders table. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDeleteTool extends AbstractBusinessTool {

    private final SalesService salesService;
    private final SalesOrderRepository salesOrderRepository;

    @Override
    public String getToolName() {
        return "order_delete";
    }

    @Override
    public String getDescription() {
        return "取消销售订单，或删除仍为草稿的销售订单。操作对象来自 sales_orders，需要销售订单ID或订单编号。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("orderId", Map.of(
                "type", "string",
                "description", "销售订单ID或订单编号"));
        properties.put("operation", Map.of(
                "type", "string",
                "description", "DELETE（仅草稿可删除）或 CANCEL（取消订单）",
                "default", "CANCEL"));
        properties.put("reason", Map.of(
                "type", "string",
                "description", "取消原因"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", Collections.singletonList("orderId"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("orderId");
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        String idOrNumber = getString(params, "orderId");
        String operation = getString(params, "operation", "CANCEL");
        String reason = getString(params, "reason");
        Long userId = getLong(context, "userId");
        SalesOrder order = resolveOrder(factoryId, idOrNumber);

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", order.getId());
        result.put("orderNumber", order.getOrderNumber());
        result.put("operation", operation.toUpperCase());
        result.put("source", "sales_orders");

        if ("DELETE".equalsIgnoreCase(operation)) {
            salesService.deleteDraft(factoryId, order.getId(), userId);
            result.put("message", "销售订单 " + order.getOrderNumber() + " 已删除");
        } else if ("CANCEL".equalsIgnoreCase(operation)) {
            SalesOrder cancelled = salesService.cancelOrder(factoryId, order.getId(), reason);
            result.put("order", cancelled);
            result.put("message", "销售订单 " + order.getOrderNumber() + " 已取消");
        } else {
            throw new IllegalArgumentException("不支持的订单操作: " + operation);
        }
        log.info("销售订单变更 - factoryId={}, orderId={}, operation={}",
                factoryId, order.getId(), operation);
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

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("orderId".equals(paramName)) {
            return "请提供要取消或删除的销售订单编号或ID。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        return "orderId".equals(paramName) ? "销售订单ID" : super.getParameterDisplayName(paramName);
    }
}
