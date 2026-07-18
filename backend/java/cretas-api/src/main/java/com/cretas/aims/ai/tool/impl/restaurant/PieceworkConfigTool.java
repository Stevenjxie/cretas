package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.restaurant.PieceworkConfig;
import com.cretas.aims.repository.restaurant.PieceworkConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Slf4j @Component
public class PieceworkConfigTool extends AbstractBusinessTool {
    @Autowired private PieceworkConfigRepository repo;

    @Override public String getToolName() { return "restaurant_piecework_config"; }
    @Override public String getDescription() { return "配置计件绩效规则 — 设定岗位计件模式(个人/小组)、基础单量、超出单价."; }
    @Override public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "store_id", Map.of("type", "string"), "role", Map.of("type", "string"),
            "calc_mode", Map.of("type", "string", "description", "INDIVIDUAL 或 TEAM"),
            "base_threshold", Map.of("type", "integer"), "base_salary", Map.of("type", "number"),
            "per_unit_bonus", Map.of("type", "number"), "team_size", Map.of("type", "integer")),
            "required", List.of("store_id", "role"));
    }
    @Override protected List<String> getRequiredParameters() { return List.of("store_id", "role"); }
    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> ctx) throws Exception {
        PieceworkConfig config = PieceworkConfig.builder()
            .factoryId(factoryId).storeId(getString(params, "store_id"))
            .role(getString(params, "role"))
            .calcMode(getString(params, "calc_mode") != null ? getString(params, "calc_mode") : "TEAM")
            .baseThreshold(params.get("base_threshold") != null ? Integer.parseInt(params.get("base_threshold").toString()) : null)
            .baseSalary(params.get("base_salary") != null ? new BigDecimal(params.get("base_salary").toString()) : null)
            .perUnitBonus(params.get("per_unit_bonus") != null ? new BigDecimal(params.get("per_unit_bonus").toString()) : null)
            .teamSize(params.get("team_size") != null ? Integer.parseInt(params.get("team_size").toString()) : null)
            .effectiveMonth(LocalDate.now().withDayOfMonth(1))
            .build();
        config = repo.save(config);
        return buildSimpleResult("计件配置已保存", Map.of("configId", config.getId(), "role", config.getRole()));
    }
}
