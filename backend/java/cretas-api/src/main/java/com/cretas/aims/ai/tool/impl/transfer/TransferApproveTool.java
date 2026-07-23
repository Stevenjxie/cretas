package com.cretas.aims.ai.tool.impl.transfer;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.service.inventory.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 调拨单提交/仓储执行状态推进工具
 *
 * 审批通过/驳回只允许在统一 OA 审批中心执行；本工具不提供绕过 OA 的审批动作。
 * Intent Code: TRANSFER_APPROVE
 */
@Slf4j
@Component
public class TransferApproveTool extends AbstractBusinessTool {

    @Autowired
    private TransferService transferService;

    @Override
    public String getToolName() {
        return "transfer_approve";
    }

    @Override
    public String getDescription() {
        return "调拨单提交和仓储执行状态推进。支持操作：提交审批(request)、" +
                "发货(ship)、签收(receive)、确认(confirm)、取消(cancel)。" +
                "审批通过/驳回必须前往统一 OA 审批中心。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> transferId = new HashMap<>();
        transferId.put("type", "string");
        transferId.put("description", "调拨单ID");
        properties.put("transferId", transferId);

        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("description", "操作类型");
        action.put("enum", Arrays.asList("request", "ship", "receive", "confirm", "cancel"));
        properties.put("action", action);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "取消原因（取消时需要）");
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("transferId", "action"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("transferId", "action");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "transferId" -> "请提供调拨单ID或编号。";
            case "action" -> "请选择操作：提交审批(request)、发货(ship)、签收(receive)、确认(confirm)、取消(cancel)。审批请前往统一 OA。";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String transferId = getString(params, "transferId");
        String action = getString(params, "action");
        String reason = getString(params, "reason");
        Long userId = getUserId(context);

        log.info("调拨单操作 - factoryId={}, transferId={}, action={}", factoryId, transferId, action);

        InternalTransfer result = switch (action.toLowerCase()) {
            case "request" -> transferService.requestTransfer(factoryId, transferId, userId);
            case "ship" -> transferService.shipTransfer(factoryId, transferId, userId);
            case "receive" -> transferService.receiveTransfer(factoryId, transferId, userId);
            case "confirm" -> transferService.confirmTransfer(factoryId, transferId, userId);
            case "cancel" -> transferService.cancelTransfer(factoryId, transferId, userId, reason != null ? reason : "AI工具取消");
            default -> throw new IllegalArgumentException("不支持的操作: " + action);
        };

        String actionName = switch (action.toLowerCase()) {
            case "request" -> "已提交";
            case "ship" -> "已发货";
            case "receive" -> "已签收";
            case "confirm" -> "已确认完成";
            case "cancel" -> "已取消";
            default -> action;
        };

        Map<String, Object> response = new HashMap<>();
        response.put("transferId", result.getId());
        response.put("transferNumber", result.getTransferNumber());
        response.put("status", result.getStatus().name());
        response.put("message", String.format("调拨单 %s %s，当前状态: %s",
                result.getTransferNumber(), actionName, result.getStatus().name()));

        return response;
    }
}
