package com.cretas.aims.ai.tool.impl.factory;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * P0-5 Tool — 关闭物料需求单(自动按 issued-consumed 算退料).
 *
 * <p>触发短语: "关闭 MR..." / "MR... 退料" / "物料需求单 关单".
 */
@Slf4j
@Component
public class MaterialRequisitionCloseTool extends AbstractBusinessTool {

    @Autowired
    private FactoryMaterialRequisitionService service;

    @Override
    public String getToolName() {
        return "factory_material_requisition_close";
    }

    @Override
    public String getDescription() {
        return "关闭物料需求单,系统按 issued-consumed 自动计算退料量并创建反向调拨。" +
               "当用户说'关闭 MR...'、'物料需求单关单'、'生产退料'时调用";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "requisitionId", Map.of("type", "string", "description", "物料需求单 ID 或 MR 单号")
            ),
            "required", List.of("requisitionId")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("requisitionId");
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("factory", "material", "production");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String id = getString(params, "requisitionId");
        Long userId = context != null && context.get("userId") != null
                ? Long.valueOf(context.get("userId").toString()) : null;
        FactoryMaterialRequisition mr = service.close(factoryId, id, userId);
        return buildSimpleResult("物料需求单已关单: " + mr.getRequisitionNo() + " (退料已自动计算)", mr);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
