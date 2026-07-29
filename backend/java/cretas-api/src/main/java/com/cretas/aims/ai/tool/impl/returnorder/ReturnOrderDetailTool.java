package com.cretas.aims.ai.tool.impl.returnorder;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.service.inventory.ReturnOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class ReturnOrderDetailTool extends AbstractBusinessTool {

    @Autowired
    private ReturnOrderService returnOrderService;

    @Override
    public String getToolName() {
        return "return_order_detail";
    }

    @Override
    public String getDescription() {
        return "查询退货单详情。根据退货单ID获取完整信息，包括退货原因、物料明细、审批状态等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> returnOrderId = new HashMap<>();
        returnOrderId.put("type", "string");
        returnOrderId.put("description", "退货单ID");
        properties.put("returnOrderId", returnOrderId);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("returnOrderId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("returnOrderId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String returnOrderId = getString(params, "returnOrderId");
        ReturnOrder order = returnOrderService.getReturnOrderById(factoryId, returnOrderId);

        Map<String, Object> result = new HashMap<>();
        result.put("returnOrder", order);
        result.put("message", "退货单详情查询成功");
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
