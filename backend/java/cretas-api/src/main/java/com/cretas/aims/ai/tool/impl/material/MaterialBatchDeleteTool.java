package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 原材料批次删除工具
 *
 * 删除指定的原材料批次记录。此为危险操作，需要管理员权限。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class MaterialBatchDeleteTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_batch_delete";
    }

    @Override
    public String getDescription() {
        return "删除原材料批次记录。此操作不可撤销，请谨慎使用。" +
                "需要提供批次ID，删除前建议先查询确认批次信息。" +
                "适用场景：删除错误入库记录、清理废弃批次。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "要删除的原材料批次ID");
        properties.put("batchId", batchId);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("batchId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("batchId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchId = getString(params, "batchId");

        // W5 红线 (AI-RBAC): 传入调用者真实角色 (来自 JWT, 经 context.userRole 透传),
        // 走 service 带角色守卫的重载 — 否则 callerRole=null 被 service 当"内部调用"放行,
        // 仓管/操作员经 AI 对话即可绕过 HTTP 面 @RequirePermission 删批次 (无单据移除库存)。
        // W0 WriteGuard 只是确认门, 不做鉴权; 鉴权由 service isPrivilegedInventoryRole 收口。
        String callerRole = getUserRole(context);

        log.info("删除原材料批次: factoryId={}, batchId={}, callerRole={}", factoryId, batchId, callerRole);

        materialBatchService.deleteMaterialBatch(factoryId, batchId, callerRole);

        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("operation", "DELETE");
        result.put("message", "批次 " + batchId + " 已成功删除");

        log.info("原材料批次删除成功: batchId={}", batchId);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("batchId".equals(paramName)) {
            return "请问您要删除哪个批次？请提供批次ID。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("batchId".equals(paramName)) {
            return "批次ID";
        }
        return super.getParameterDisplayName(paramName);
    }

    /**
     * 删除操作需要权限
     */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "warehouse_manager".equals(userRole);
    }
}
