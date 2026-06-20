package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.WorkOrder;
import com.cretas.aims.service.WorkOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class OrderUpdateTool extends AbstractBusinessTool {

    @Autowired
    private WorkOrderService workOrderService;

    @Override
    public String getToolName() {
        return "order_update";
    }

    @Override
    public String getDescription() {
        return "修改订单/工单信息或状态。需要提供订单ID和要修改的内容。" +
                "适用场景：修改订单、更新订单状态、订单确认、变更订单。";
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

        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "新状态（如：PENDING、IN_PROGRESS、COMPLETED、CANCELLED）");
        properties.put("status", status);

        Map<String, Object> description = new HashMap<>();
        description.put("type", "string");
        description.put("description", "更新的订单描述/备注");
        properties.put("description", description);

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
        log.info("执行订单更新 - 工厂ID: {}, 参数: {}", factoryId, params);

        String orderId = getString(params, "orderId");
        String status = getString(params, "status");
        String description = getString(params, "description");
        Long userId = getLong(context, "userId");

        WorkOrder updated;
        if (status != null && !status.isEmpty()) {
            updated = workOrderService.updateStatus(factoryId, orderId, status, userId);
        } else {
            WorkOrder updateData = new WorkOrder();
            if (description != null) {
                updateData.setDescription(description);
            }
            updated = workOrderService.updateWorkOrder(factoryId, orderId, updateData);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("order", updated);
        result.put("orderId", orderId);
        result.put("message", "订单(ID: " + orderId + ")已更新");
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("orderId".equals(paramName)) {
            return "请提供要修改的订单编号或ID。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "orderId":
                return "订单ID";
            case "status":
                return "订单状态";
            case "description":
                return "订单描述";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }
}
