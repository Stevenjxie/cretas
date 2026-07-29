package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.bom.BomVersion;
import com.cretas.aims.service.bom.BomVersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M-BOM-VER-1 — 创建 BomVersion DRAFT (Sprint 3 Track-H).
 *
 * <p>从当前 BomRecipe 状态生成 snapshot, 写入新版本 DRAFT. versionNumber 自动 max+1.
 * 适用场景: "为 BOM 创建新版本草稿" / 用户提议修改但需走审批流程.
 */
@Slf4j
@Component
public class BomVersionCreateTool extends AbstractBusinessTool {

    @Autowired
    private BomVersionService versionService;

    @Override
    public String getToolName() {
        return "bom_version_create";
    }

    @Override
    public String getDescription() {
        return "为指定 BOM 配方创建新版本 DRAFT — 从当前 BomRecipe + items snapshot 写入. "
             + "versionNumber 自动 max+1. status=DRAFT (尚未提交审批). "
             + "适用场景: 用户提议修改 BOM 但需走审批流程 / 历史版本归档前的 baseline 录制.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> bomRecipeId = new HashMap<>();
        bomRecipeId.put("type", "string");
        bomRecipeId.put("description", "BOM 配方 ID (UUID)");

        Map<String, Object> createdBy = new HashMap<>();
        createdBy.put("type", "integer");
        createdBy.put("description", "创建者用户 ID (可选, 从 context 取)");

        Map<String, Object> properties = new HashMap<>();
        properties.put("bomRecipeId", bomRecipeId);
        properties.put("createdBy", createdBy);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("bomRecipeId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("bomRecipeId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String bomRecipeId = getString(params, "bomRecipeId");
        Long createdBy = getLong(params, "createdBy");
        BomVersion draft = versionService.createDraft(factoryId, bomRecipeId, createdBy);
        log.info("BomVersion created via AI tool: factoryId={}, bomRecipeId={}, versionId={}, versionNumber={}",
                factoryId, bomRecipeId, draft.getId(), draft.getVersionNumber());
        return buildSimpleResult("BomVersion DRAFT v" + draft.getVersionNumber() + " 已创建", draft);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
