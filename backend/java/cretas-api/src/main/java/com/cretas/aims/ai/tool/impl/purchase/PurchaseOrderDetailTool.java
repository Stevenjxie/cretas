package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.service.inventory.PurchaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class PurchaseOrderDetailTool extends AbstractBusinessTool {

    @Autowired
    private PurchaseService purchaseService;

    @Override
    public String getToolName() {
        return "purchase_order_detail";
    }

    @Override
    public String getDescription() {
        return "查询采购订单详情。根据订单ID获取采购订单的完整信息，包括供应商、物料明细、金额、状态等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> orderId = new HashMap<>();
        orderId.put("type", "string");
        orderId.put("description", "采购订单ID");
        properties.put("orderId", orderId);

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
        String orderId = getString(params, "orderId");
        PurchaseOrder order = purchaseService.getPurchaseOrderById(factoryId, orderId);

        Map<String, Object> result = new HashMap<>();
        result.put("order", order);
        result.put("message", "采购订单详情查询成功");
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
