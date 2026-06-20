package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.WorkOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class OrderDeleteTool extends AbstractBusinessTool {

    @Autowired
    private WorkOrderService workOrderService;

    @Override
    public String getToolName() {
        return "order_delete";
    }

    @Override
    public String getDescription() {
        return "删除或取消订单/工单。需要提供订单ID，可选提供取消原因。" +
                "适用场景：删除订单、取消订单、撤销工单。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> orderId = new HashMap<>();
        orderId.put("type", "string");
        orderId.put("description", "订单/工单ID或编号");
        properties.put("orderId", orderId);

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("description", "操作类型：DELETE（删除）或 CANCEL（取消）");
        operation.put("default", "CANCEL");
        properties.put("operation", operation);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "取消/删除原因");
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("orderId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("orderId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行订单删除/取消 - 工厂ID: {}, 参数: {}", factoryId, params);

        String orderId = getString(params, "orderId");
        String operation = getString(params, "operation", "CANCEL");
        String reason = getString(params, "reason");
        Long userId = getLong(context, "userId");

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("operation", operation);

        if ("DELETE".equalsIgnoreCase(operation)) {
            workOrderService.deleteWorkOrder(factoryId, orderId);
            result.put("message", "订单(ID: " + orderId + ")已删除");
        } else {
            workOrderService.cancelWorkOrder(factoryId, orderId, reason, userId);
            result.put("message", "订单(ID: " + orderId + ")已取消");
        }

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("orderId".equals(paramName)) {
            return "请提供要删除或取消的订单编号或ID。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "orderId":
                return "订单ID";
            case "operation":
                return "操作类型";
            case "reason":
                return "原因";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }
}
