package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.service.inventory.PurchaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 采购订单审批工具
 *
 * 提交并审批采购订单。支持提交草稿、审批通过、取消订单。
 * Intent Code: PURCHASE_ORDER_APPROVE
 */
@Slf4j
@Component
public class PurchaseOrderApproveTool extends AbstractBusinessTool {

    @Autowired
    private PurchaseService purchaseService;

    @Override
    public String getToolName() {
        return "purchase_order_approve";
    }

    @Override
    public String getDescription() {
        return "审批采购订单。支持操作：提交(submit)、运营审批通过(approve)、取消(cancel)。" +
                "采购财务通过或驳回必须在工作台的待我审批中处理。";
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

        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("description", "操作类型: submit=提交; approve=运营审批; cancel=取消");
        action.put("enum", Arrays.asList("submit", "approve", "cancel"));
        properties.put("action", action);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("orderId", "action"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("orderId", "action");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "orderId" -> "请提供采购订单ID或订单编号。";
            case "action" -> "请选择操作：submit/approve/cancel。财务审批请前往工作台 → 待我审批。";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String orderId = getString(params, "orderId");
        String action = getString(params, "action");
        Long userId = getUserId(context);

        // 旧模型或缓存仍可能发出已从 schema 删除的动作；这里必须再次 fail closed，
        // 不能只依赖 LLM 遵守 enum。等运行时调用日志确认旧动作归零后可删这段兼容守卫。
        if ("finance_approve".equalsIgnoreCase(action) || "finance_reject".equalsIgnoreCase(action)) {
            throw PurchaseFinanceApproveTool.oaOnly();
        }

        log.info("采购订单审批 - factoryId={}, orderId={}, action={}", factoryId, orderId, action);

        PurchaseOrder result = switch (action.toLowerCase()) {
            case "submit" -> purchaseService.submitOrder(factoryId, orderId);
            case "approve" -> purchaseService.approveOrder(factoryId, orderId, userId);
            case "cancel" -> purchaseService.cancelOrder(factoryId, orderId);
            default -> throw new IllegalArgumentException("不支持的操作: " + action);
        };

        String actionName = switch (action.toLowerCase()) {
            case "submit" -> "提交";
            case "approve" -> "运营审批";
            case "cancel" -> "取消";
            default -> action;
        };

        Map<String, Object> response = new HashMap<>();
        response.put("orderId", result.getId());
        response.put("orderNumber", result.getOrderNumber());
        response.put("status", result.getStatus().name());
        response.put("message", String.format("采购订单 %s 已%s，当前状态: %s",
                result.getOrderNumber(), actionName, result.getStatus().name()));

        return response;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
