package com.cretas.aims.ai.tool.impl.returnorder;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.service.inventory.ReturnOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 退货单审批工具
 *
 * 支持退货单全生命周期操作：提交→审批/拒绝→完成
 * Intent Code: RETURN_ORDER_APPROVE
 */
@Slf4j
@Component
public class ReturnOrderApproveTool extends AbstractBusinessTool {

    @Autowired
    private ReturnOrderService returnOrderService;

    @Autowired
    private ToolRbacGuard rbacGuard;

    @Override
    public String getToolName() {
        return "return_order_approve";
    }

    @Override
    public String getDescription() {
        return "退货单审批和状态推进。支持操作：提交(submit)、业务审批通过(approve)、财务审批(finance-approve)、拒绝(reject)、完成(complete)。" +
                "退货跟钱有关，业务审批后必须先财务审批(finance-approve)才能完成(complete)交仓管出货。" +
                "适用场景：提交退货审批、审批退货单、财务审批退货单、确认退货完成。";
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

        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("description", "操作类型");
        action.put("enum", Arrays.asList("submit", "approve", "finance-approve", "reject", "complete"));
        properties.put("action", action);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("returnOrderId", "action"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("returnOrderId", "action");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "returnOrderId" -> "请提供退货单ID或编号。";
            case "action" -> "请选择操作：提交(submit)、业务审批通过(approve)、财务审批(finance-approve)、拒绝(reject)、完成(complete)。";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String returnOrderId = getString(params, "returnOrderId");
        String action = getString(params, "action");
        Long userId = getUserId(context);

        // W5 红线 (AI-RBAC): ReturnOrderController @RequirePermission 被 AI 直调绕过 — 此处补鉴权,
        // 口径与 HTTP 面一致 (ReturnOrderService 本身无角色守卫, 鉴权全在 controller 注解层):
        //   - finance-approve → finance:read_write (退货财审, 跟钱有关, 仅财务角色)
        //   - submit/approve/reject/complete → sales:read_write 或 procurement:read_write (类级注解)
        // fail-closed: 无权 → 拒绝, 不静默执行。
        String act = action.toLowerCase();
        boolean isFinance = "finance-approve".equals(act) || "finance_approve".equals(act);
        boolean permitted = isFinance
                ? rbacGuard.hasAnyPermission(context, "finance:read_write")
                : rbacGuard.hasAnyPermission(context, "sales:read_write", "procurement:read_write");
        if (!permitted) {
            String actionLabel = isFinance ? "退货单财务审批" : "退货单审批/状态推进";
            log.warn("W5 AI-RBAC: 退货单操作被拒, action={}, userId={}, returnOrderId={}",
                    action, userId, returnOrderId);
            Map<String, Object> denied = new HashMap<>();
            denied.put("denied", true);
            denied.put("action", action);
            denied.put("returnOrderId", returnOrderId);
            denied.put("message", rbacGuard.denyMessage(context, actionLabel,
                    isFinance ? "finance:read_write" : "sales:read_write"));
            return denied;
        }

        log.info("退货单操作 - factoryId={}, returnOrderId={}, action={}", factoryId, returnOrderId, action);

        ReturnOrder result = switch (action.toLowerCase()) {
            case "submit" -> returnOrderService.submitReturnOrder(factoryId, returnOrderId);
            case "approve" -> returnOrderService.approveReturnOrder(factoryId, returnOrderId, userId);
            case "finance-approve", "finance_approve" -> returnOrderService.financeApproveReturnOrder(factoryId, returnOrderId, userId);
            case "reject" -> returnOrderService.rejectReturnOrder(factoryId, returnOrderId);
            case "complete" -> returnOrderService.completeReturnOrder(factoryId, returnOrderId);
            default -> throw new IllegalArgumentException("不支持的操作: " + action);
        };

        String actionName = switch (action.toLowerCase()) {
            case "submit" -> "已提交";
            case "approve" -> "已业务审批通过";
            case "finance-approve", "finance_approve" -> "已财务审批通过";
            case "reject" -> "已拒绝";
            case "complete" -> "已完成";
            default -> action;
        };

        Map<String, Object> response = new HashMap<>();
        response.put("returnOrderId", result.getId());
        response.put("returnNumber", result.getReturnNumber());
        response.put("status", result.getStatus().name());
        response.put("message", String.format("退货单 %s %s，当前状态: %s",
                result.getReturnNumber(), actionName, result.getStatus().name()));

        return response;
    }
}
