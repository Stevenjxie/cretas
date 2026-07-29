package com.cretas.aims.ai.tool.impl.warehouse;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.warehouse.ReusableContainerTransaction;
import com.cretas.aims.service.warehouse.ReusableContainerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C4 — 客户归还周转框入库.
 */
@Slf4j
@Component
public class ReusableContainerReturnInTool extends AbstractBusinessTool {

    @Autowired
    private ReusableContainerService service;

    @Override
    public String getToolName() {
        return "reusable_container_return_in";
    }

    @Override
    public String getDescription() {
        return "客户归还周转框入库 — 减在途+加在库, 生成 RETURN_IN 流水";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "containerId", Map.of("type", "string", "description", "周转耗材 ID"),
                "quantity", Map.of("type", "integer", "description", "归还数量"),
                "customerId", Map.of("type", "string", "description", "客户 ID"),
                "customerName", Map.of("type", "string", "description", "客户名称"),
                "remark", Map.of("type", "string", "description", "备注(可选)")
            ),
            "required", List.of("containerId", "quantity", "customerId", "customerName")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("containerId", "quantity", "customerId", "customerName");
    }

    @Override
    public ActionType getActionType() {
        return ActionType.WRITE;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("warehouse", "reusable-container");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String containerId = getString(params, "containerId");
        Integer quantity = getInteger(params, "quantity");
        String customerId = getString(params, "customerId");
        String customerName = getString(params, "customerName");
        String remark = getString(params, "remark");

        ReusableContainerTransaction tx = service.returnIn(factoryId, containerId, quantity,
                customerId, customerName, remark);
        return buildSimpleResult(customerName + " 归还 " + quantity + " 个周转框入库", tx);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
