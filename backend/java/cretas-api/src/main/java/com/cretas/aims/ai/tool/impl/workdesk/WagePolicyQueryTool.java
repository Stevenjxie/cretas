package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.repository.WagePolicyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工资策略查询 Tool (Sprint 8 P2 — 财务主管 Workdesk).
 *
 * <p>查 factory / 员工的 WagePolicy 配置 (mode: PIECE_RATE/HOURLY/MIXED + mixed formula hint).
 *
 * <p>读路径无副作用. 防呆 R2 — 含 mode + active + employeeId scope.
 *
 * <p>Intent Code: {@code WAGE_POLICY_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class WagePolicyQueryTool extends AbstractBusinessTool {

    @Autowired
    private WagePolicyRepository wagePolicyRepository;

    @Override
    public String getToolName() {
        return "wage_policy_query";
    }

    @Override
    public String getDescription() {
        return "查 factory / 员工的工资策略配置 (PIECE_RATE/HOURLY/MIXED mode + mixed formula). "
                + "LLM 触发场景: 用户问 '工资策略' / '工资计算 mode' / '员工 X 的工资策略' / "
                + "'按件工资规则' / 'WagePolicy'. 可选 employeeId 过滤. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> employeeId = new HashMap<>();
        employeeId.put("type", "integer");
        employeeId.put("description", "可选 — 仅查指定员工的 policy. 留空 = 列工厂全部 policy");
        properties.put("employeeId", employeeId);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Long employeeId = getLong(params, "employeeId");

        log.info("wage_policy_query — factory={} employeeId={}", factoryId, employeeId);

        List<WagePolicy> all;
        if (employeeId != null) {
            // 员工级 policy (优先), 否则 factory default
            Optional<WagePolicy> empPolicy = wagePolicyRepository
                    .findByFactoryIdAndEmployeeIdAndIsActiveTrue(factoryId, employeeId);
            Optional<WagePolicy> defaultPolicy = wagePolicyRepository.findFactoryDefault(factoryId);
            all = new ArrayList<>();
            empPolicy.ifPresent(all::add);
            defaultPolicy.ifPresent(d -> {
                if (empPolicy.isEmpty()) all.add(d);
            });
        } else {
            all = wagePolicyRepository.findByFactoryIdOrderByEmployeeIdAscIdDesc(factoryId);
        }

        List<Map<String, Object>> items = new ArrayList<>(all.size());
        for (WagePolicy p : all) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.getId());
            item.put("factoryId", p.getFactoryId());
            item.put("employeeId", p.getEmployeeId());
            item.put("scope", p.getEmployeeId() != null ? "EMPLOYEE" : "FACTORY_DEFAULT");
            item.put("mode", p.getMode() != null ? p.getMode().name() : null);
            item.put("modeDisplay", displayMode(p.getMode()));
            item.put("mixedFormulaHint", p.getMixedFormulaHint());
            item.put("isActive", p.getIsActive());
            item.put("notes", p.getNotes());
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("employeeId", employeeId);
        data.put("count", items.size());
        data.put("policies", items);
        data.put("actionHint", "/hr/wage-policy");

        String message = employeeId != null
                ? (items.isEmpty()
                        ? "未找到员工 " + employeeId + " 的工资策略, 兜底 PIECE_RATE"
                        : String.format("员工 %d 工资策略: %d 项配置", employeeId, items.size()))
                : String.format("工厂工资策略: %d 项配置 (factory default + employee override)", items.size());
        return buildSimpleResult(message, data);
    }

    private String displayMode(com.cretas.aims.entity.enums.WageMode mode) {
        if (mode == null) return null;
        return switch (mode) {
            case PIECE_RATE -> "按件";
            case HOURLY -> "按时";
            case MIXED -> "混合 (按时+按件)";
        };
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
