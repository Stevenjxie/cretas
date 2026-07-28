package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.UpdateMaterialBatchRequest;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 原材料批次信息更新工具
 *
 * 更新原材料批次的附属信息（如存储位置、备注等）。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class MaterialUpdateTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_update";
    }

    @Override
    public String getDescription() {
        return "更新原材料批次的附属信息。" +
                "支持修改存储位置、备注等字段。" +
                "适用场景：变更存储位置、补充批次备注信息。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "要更新的原材料批次ID");
        properties.put("batchId", batchId);

        Map<String, Object> storageLocation = new HashMap<>();
        storageLocation.put("type", "string");
        storageLocation.put("description", "新的存储位置");
        properties.put("storageLocation", storageLocation);

        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "备注信息");
        properties.put("notes", notes);

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
        String storageLocation = getString(params, "storageLocation");
        String notes = getString(params, "notes");

        // W5 红线 (AI-RBAC): 透传调用者真实角色, 走 service 带角色守卫的重载。
        // 本工具仅暴露 storageLocation/notes (非库存字段), 但 service 守卫是字段级 (requestChangesInventoryFields),
        // 传真实角色保证与 HTTP 面 @RequirePermission 一致, 防御未来扩字段时回归。
        String callerRole = getUserRole(context);

        log.info("更新原材料批次信息: factoryId={}, batchId={}, storageLocation={}, notes={}, callerRole={}",
                factoryId, batchId, storageLocation, notes, callerRole);

        UpdateMaterialBatchRequest updateReq = new UpdateMaterialBatchRequest();
        if (storageLocation != null) {
            updateReq.setStorageLocation(storageLocation);
        }
        if (notes != null) {
            updateReq.setNotes(notes);
        }

        MaterialBatchDTO updated = materialBatchService.updateMaterialBatch(factoryId, batchId, updateReq, callerRole);

        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("batchNumber", updated.getBatchNumber());
        result.put("operation", "UPDATE");
        result.put("message", "批次 " + batchId + " 信息已更新");

        if (storageLocation != null) {
            result.put("storageLocation", storageLocation);
        }
        if (notes != null) {
            result.put("notes", notes);
        }

        log.info("原材料批次更新成功: batchId={}", batchId);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = new HashMap<>();
        questions.put("batchId", "请问您要更新哪个批次的信息？请提供批次ID。");
        questions.put("storageLocation", "请问新的存储位置是什么？");
        questions.put("notes", "请问需要添加什么备注？");

        String question = questions.get(paramName);
        return question != null ? question : super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = new HashMap<>();
        displayNames.put("batchId", "批次ID");
        displayNames.put("storageLocation", "存储位置");
        displayNames.put("notes", "备注");

        String name = displayNames.get(paramName);
        return name != null ? name : super.getParameterDisplayName(paramName);
    }

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

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
