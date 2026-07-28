package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.bom.EngineeringChangeNotice;
import com.cretas.aims.service.bom.ECNService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * M-BOM-VER-1 — ECN 审批 (Sprint 3 Track-H).
 *
 * <p>action=SUBMIT: DRAFT → SUBMITTED (触发通知).
 * action=APPROVE: SUBMITTED → APPROVED (cascade 自动 approve 关联 BomVersion).
 * action=REJECT: SUBMITTED → REJECTED (终态; cascade 拒绝 BomVersion DRAFT).
 *
 * 适用场景: ECN 审批工作流 (申请→通过/拒绝).
 */
@Slf4j
@Component
public class EcnApproveTool extends AbstractBusinessTool {

    @Autowired
    private ECNService ecnService;

    @Override
    public String getToolName() {
        return "ecn_approve";
    }

    @Override
    public String getDescription() {
        return "ECN 工作流 transition. SUBMIT: DRAFT→SUBMITTED 触发通知. APPROVE: "
             + "SUBMITTED→APPROVED + cascade approve 关联 BomVersion. REJECT: SUBMITTED→REJECTED "
             + "+ cascade reject. 适用场景: ECN 申请提交 / 主管审批通过 / 主管驳回.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("ecnId", Map.of("type", "string", "description", "ECN ID"));
        properties.put("action", Map.of("type", "string",
                "enum", Arrays.asList("SUBMIT", "APPROVE", "REJECT"),
                "description", "操作 SUBMIT/APPROVE/REJECT"));
        properties.put("approverId", Map.of("type", "integer",
                "description", "审批人 ID (APPROVE/REJECT 必填)"));
        properties.put("rejectionReason", Map.of("type", "string",
                "description", "拒绝原因 (REJECT 必填)"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("ecnId", "action"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("ecnId", "action");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String ecnId = getString(params, "ecnId");
        String action = getString(params, "action");
        Long approverId = getLong(params, "approverId");

        EngineeringChangeNotice result;
        switch (action.toUpperCase()) {
            case "SUBMIT":
                result = ecnService.submitForApproval(factoryId, ecnId);
                return buildSimpleResult("ECN " + result.getEcnNumber() + " 已提交审批", result);
            case "APPROVE":
                if (approverId == null) {
                    throw new IllegalArgumentException("APPROVE 必须提供 approverId");
                }
                result = ecnService.approve(factoryId, ecnId, approverId);
                return buildSimpleResult("ECN " + result.getEcnNumber()
                        + " 已审批通过 (cascade 触发 BomVersion approve)", result);
            case "REJECT":
                if (approverId == null) {
                    throw new IllegalArgumentException("REJECT 必须提供 approverId");
                }
                String reason = getString(params, "rejectionReason");
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("REJECT 必须提供 rejectionReason");
                }
                result = ecnService.reject(factoryId, ecnId, approverId, reason);
                return buildSimpleResult("ECN " + result.getEcnNumber() + " 已拒绝", result);
            default:
                throw new IllegalArgumentException("无效 action: " + action
                        + " (限 SUBMIT / APPROVE / REJECT)");
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
