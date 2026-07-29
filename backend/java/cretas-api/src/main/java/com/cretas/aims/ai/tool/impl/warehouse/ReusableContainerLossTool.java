package com.cretas.aims.ai.tool.impl.warehouse;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.warehouse.ReusableContainerTransaction;
import com.cretas.aims.service.warehouse.ReusableContainerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C4 — 周转框丢失登记 + 客户赔偿.
 */
@Slf4j
@Component
public class ReusableContainerLossTool extends AbstractBusinessTool {

    @Autowired
    private ReusableContainerService service;

    @Override
    public String getToolName() {
        return "reusable_container_loss";
    }

    @Override
    public String getDescription() {
        return "周转框丢失登记 + 客户赔偿 — 减在途+减总量, 记录赔偿金额";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "containerId", Map.of("type", "string", "description", "周转耗材 ID"),
                "quantity", Map.of("type", "integer", "description", "丢失数量"),
                "customerId", Map.of("type", "string", "description", "客户 ID"),
                "customerName", Map.of("type", "string", "description", "客户名称"),
                "compensationAmount", Map.of("type", "number", "description", "赔偿金额"),
                "remark", Map.of("type", "string", "description", "备注(可选)")
            ),
            "required", List.of("containerId", "quantity", "customerId", "customerName", "compensationAmount")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("containerId", "quantity", "customerId", "customerName", "compensationAmount");
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
        BigDecimal compensation = getBigDecimal(params, "compensationAmount");
        String remark = getString(params, "remark");

        ReusableContainerTransaction tx = service.markLoss(factoryId, containerId, quantity,
                customerId, customerName, compensation, remark);
        return buildSimpleResult("已登记 " + customerName + " 丢失 " + quantity + " 个, 赔偿 "
                + compensation, tx);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
