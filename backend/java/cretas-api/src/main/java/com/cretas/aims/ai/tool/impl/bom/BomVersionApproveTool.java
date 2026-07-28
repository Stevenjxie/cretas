package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.bom.BomVersion;
import com.cretas.aims.service.bom.BomVersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * M-BOM-VER-1 — 审批 BomVersion (Sprint 3 Track-H).
 *
 * <p>action=APPROVE: status → APPROVED, effectiveFrom=today, 旧版本自动 OBSOLETE (DB trigger).
 * action=REJECT: status → REJECTED (terminal), 需 rejectionReason.
 * 适用场景: "批准 v3 版本生效" / "拒绝待审 BOM 版本".
 */
@Slf4j
@Component
public class BomVersionApproveTool extends AbstractBusinessTool {

    @Autowired
    private BomVersionService versionService;

    @Override
    public String getToolName() {
        return "bom_version_approve";
    }

    @Override
    public String getDescription() {
        return "审批 BomVersion (APPROVE / REJECT). APPROVE: PENDING/DRAFT → APPROVED, "
             + "effectiveFrom=today, 旧版本自动 OBSOLETE. REJECT: → REJECTED (终态). "
             + "适用场景: 批准生效 / 拒绝草稿.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> versionId = new HashMap<>();
        versionId.put("type", "string");
        versionId.put("description", "BomVersion ID");

        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("enum", Arrays.asList("APPROVE", "REJECT"));
        action.put("description", "操作: APPROVE 通过 / REJECT 拒绝");

        Map<String, Object> approverId = new HashMap<>();
        approverId.put("type", "integer");
        approverId.put("description", "审批人 ID");

        Map<String, Object> rejectionReason = new HashMap<>();
        rejectionReason.put("type", "string");
        rejectionReason.put("description", "拒绝原因 (action=REJECT 时必填)");

        Map<String, Object> properties = new HashMap<>();
        properties.put("versionId", versionId);
        properties.put("action", action);
        properties.put("approverId", approverId);
        properties.put("rejectionReason", rejectionReason);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("versionId", "action"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("versionId", "action");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String versionId = getString(params, "versionId");
        String action = getString(params, "action");
        Long approverId = getLong(params, "approverId");

        BomVersion result;
        if ("APPROVE".equalsIgnoreCase(action)) {
            result = versionService.approve(factoryId, versionId, approverId);
            return buildSimpleResult("BomVersion v" + result.getVersionNumber() + " 已审批通过", result);
        } else if ("REJECT".equalsIgnoreCase(action)) {
            String reason = getString(params, "rejectionReason");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("REJECT 必须提供 rejectionReason");
            }
            result = versionService.reject(factoryId, versionId, approverId, reason);
            return buildSimpleResult("BomVersion v" + result.getVersionNumber() + " 已拒绝", result);
        } else {
            throw new IllegalArgumentException("无效 action: " + action + " (限 APPROVE / REJECT)");
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
