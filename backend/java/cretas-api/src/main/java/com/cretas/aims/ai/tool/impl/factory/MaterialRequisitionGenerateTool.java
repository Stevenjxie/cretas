package com.cretas.aims.ai.tool.impl.factory;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * P0-5 Tool — 按生产计划 BOM 自动生成物料需求单.
 *
 * <p>触发短语: "按计划 PP... 生成物料需求单" / "生成备料单" / "排产备料".
 */
@Slf4j
@Component
public class MaterialRequisitionGenerateTool extends AbstractBusinessTool {

    @Autowired
    private FactoryMaterialRequisitionService service;

    @Override
    public String getToolName() {
        return "factory_material_requisition_generate";
    }

    @Override
    public String getDescription() {
        return "根据生产计划自动按 BOM 展开生成工厂物料需求单(备料单)。" +
               "当用户说'按 PP... 生成物料需求单'、'生成备料单'、'排产备料'时调用";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "productionPlanId", Map.of("type", "string", "description", "生产计划 ID 或 plan_number")
            ),
            "required", List.of("productionPlanId")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("productionPlanId");
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("factory", "material", "production");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String planId = getString(params, "productionPlanId");
        Long userId = context != null && context.get("userId") != null
                ? Long.valueOf(context.get("userId").toString()) : null;
        FactoryMaterialRequisition mr = service.generateFromPlan(factoryId, planId, userId);
        return buildSimpleResult(
                "物料需求单已生成: " + mr.getRequisitionNo() + " (" + mr.getItems().size() + " 行)",
                mr);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
