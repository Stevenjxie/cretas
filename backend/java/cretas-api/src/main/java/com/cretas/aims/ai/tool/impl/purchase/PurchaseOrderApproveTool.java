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
        return "审批采购订单。支持操作：提交(submit)、运营审批通过(approve)、" +
                "财务审核通过(finance_approve)、财务审核驳回(finance_reject)、取消(cancel)。" +
                "适用场景：提交采购单审批、审批采购订单、通过采购申请、财务复核、" +
                "财务驳回采购单、退回采购单、取消采购单。";
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
        action.put("description", "操作类型: submit=提交; approve=运营审批; " +
                "finance_approve=财务审核通过; finance_reject=财务驳回; cancel=取消");
        action.put("enum", Arrays.asList("submit", "approve", "finance_approve", "finance_reject", "cancel"));
        properties.put("action", action);

        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "审核备注 (财务审核动作时建议填写, 其他动作可忽略)");
        properties.put("notes", notes);

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
            case "action" -> "请选择操作：submit/approve/finance_approve/finance_reject/cancel。";
            case "notes" -> "请输入审核备注 (财务驳回时必填, 通过时可选)。";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String orderId = getString(params, "orderId");
        String action = getString(params, "action");
        String notes = getString(params, "notes");
        Long userId = getUserId(context);

        // 财务驳回必须有 notes — 业务要求 (财务必须说明驳回原因, 退回采购员修改)
        if ("finance_reject".equalsIgnoreCase(action) && (notes == null || notes.isBlank())) {
            throw new IllegalArgumentException("财务驳回必须填写备注说明驳回原因");
        }

        log.info("采购订单审批 - factoryId={}, orderId={}, action={}, hasNotes={}",
                factoryId, orderId, action, notes != null && !notes.isBlank());

        PurchaseOrder result = switch (action.toLowerCase()) {
            case "submit" -> purchaseService.submitOrder(factoryId, orderId);
            case "approve" -> purchaseService.approveOrder(factoryId, orderId, userId);
            case "finance_approve" -> purchaseService.financeApproveOrder(factoryId, orderId, userId, notes);
            case "finance_reject" -> purchaseService.financeRejectOrder(factoryId, orderId, userId, notes);
            case "cancel" -> purchaseService.cancelOrder(factoryId, orderId);
            default -> throw new IllegalArgumentException("不支持的操作: " + action);
        };

        String actionName = switch (action.toLowerCase()) {
            case "submit" -> "提交";
            case "approve" -> "运营审批";
            case "finance_approve" -> "财务审核通过";
            case "finance_reject" -> "财务审核驳回";
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
