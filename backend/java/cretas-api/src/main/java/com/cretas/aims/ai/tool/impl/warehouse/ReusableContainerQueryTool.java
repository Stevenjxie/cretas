package com.cretas.aims.ai.tool.impl.warehouse;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.warehouse.ReusableContainer;
import com.cretas.aims.service.warehouse.ReusableContainerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * C4 — 周转框/周转耗材列表 + 客户在用余额查询.
 */
@Slf4j
@Component
public class ReusableContainerQueryTool extends AbstractBusinessTool {

    @Autowired
    private ReusableContainerService service;

    @Override
    public String getToolName() {
        return "reusable_container_query";
    }

    @Override
    public String getDescription() {
        return "查询周转框/周转耗材列表和客户在用情况。" +
               "当用户说'周转框'/'周转耗材'/'客户在用几个框'时调用";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "customerId", Map.of("type", "string", "description", "可选, 传了则只看该客户")
            ),
            "required", List.of()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("warehouse", "reusable-container");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String customerId = getString(params, "customerId");

        Page<ReusableContainer> page = service.listContainers(factoryId, PageRequest.of(0, 50));
        List<Map<String, Object>> balances = service.customerBalances(factoryId);
        if (customerId != null && !customerId.isBlank()) {
            balances = balances.stream()
                    .filter(b -> Objects.equals(customerId, b.get("customerId")))
                    .collect(Collectors.toList());
        }

        return buildSimpleResult(
                "周转耗材 " + page.getTotalElements() + " 种, 客户在用记录 " + balances.size() + " 条",
                Map.of(
                    "containers", page.getContent(),
                    "totalContainers", page.getTotalElements(),
                    "customerBalances", balances
                ));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
