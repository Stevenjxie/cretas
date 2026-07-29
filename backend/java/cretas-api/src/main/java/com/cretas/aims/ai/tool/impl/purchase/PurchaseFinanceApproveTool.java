package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.inventory.PurchaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PurchaseFinanceApproveTool extends AbstractBusinessTool {

    @Autowired
    private PurchaseService purchaseService;

    @Override
    public String getToolName() { return "purchase_finance_approve"; }

    @Override
    public String getDescription() { return "采购订单财务审核通过或驳回"; }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "orderId", Map.of("type", "string", "description", "采购订单ID"),
                "action", Map.of("type", "string", "description", "操作: approve 或 reject"),
                "notes", Map.of("type", "string", "description", "审核意见")
        ), "required", List.of("orderId", "action"));
    }

    @Override
    protected List<String> getRequiredParameters() { return List.of("orderId", "action"); }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String orderId = getString(params, "orderId");
        String action = getString(params, "action");
        String notes = getString(params, "notes");
        Long userId = getLong(params, "userId");

        if ("approve".equalsIgnoreCase(action)) {
            var order = purchaseService.financeApproveOrder(factoryId, orderId, userId, notes);
            return buildSimpleResult("采购订单财务审核通过", Map.of("orderNumber", order.getOrderNumber(), "status", order.getStatus().name()));
        } else {
            var order = purchaseService.financeRejectOrder(factoryId, orderId, userId, notes);
            return buildSimpleResult("采购订单财务审核已驳回", Map.of("orderNumber", order.getOrderNumber(), "status", order.getStatus().name()));
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
