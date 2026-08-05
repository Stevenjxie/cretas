package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WorkdeskRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台岗位能力清单 —— 回答「我这个岗位能干什么」。
 *
 * <p>解决的是发现性问题：全仓近 600 个 Tool 藏在一个搜索框后面，采购员不知道
 * 系统能替他做什么。岗位归属本来就存在于工具注释里（{@code Sprint 8 P4b 采购员
 * Workdesk}），本 Tool 只是把它读出来。
 *
 * <p>⚠️ 与权限无关：这里列出的是「这个岗位的日常活」，不是「你有权调用的一切」。
 * 权限过滤仍由 {@link ToolRegistry#getToolDefinitionsForRole(String)} 负责。
 *
 * <p>零 LLM：话术是模板，数量与名称全部来自注册表。
 */
@Slf4j
@Component
public class WorkdeskRoleCapabilitiesTool extends AbstractBusinessTool {

    /**
     * {@code @Lazy} 打破循环依赖：ToolRegistry 在初始化时收集所有 {@code @Component}
     * Tool，而本 Tool 又依赖 ToolRegistry（同 ai-intent-tool-skill-architecture 禁止事项 2）。
     */
    @Autowired
    @Lazy
    private ToolRegistry toolRegistry;

    @Override
    public String getToolName() {
        return "workdesk_role_capabilities";
    }

    @Override
    public String getDescription() {
        return "查询某个工作台岗位能干什么。返回该岗位的能力清单（工具名 + 说明）。"
                + "支持的岗位：仓管员、采购员、质量主管。"
                + "适用场景：新人问'我能用系统做什么'、采购员问'系统能帮我干嘛'、"
                + "不带岗位调用时返回所有岗位及各自的能力数量。";
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    /** 只读注册表内存结构，不碰任何 repository。 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> role = new HashMap<>();
        role.put("type", "string");
        role.put("description", "岗位名称，如 采购员 / 仓管员 / 质量主管。不传则返回所有岗位概览。");

        Map<String, Object> properties = new HashMap<>();
        properties.put("role", role);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String roleName = getString(params, "role", null);

        if (roleName == null || roleName.isBlank()) {
            return allRolesOverview();
        }

        // 认不出的岗位名直接抛 —— 绝不能退化成空清单。空清单与「这个岗位确实
        // 没有工具」不可区分, 用户打错一个字就会被告知「你没有可干的事」。
        WorkdeskRole role = WorkdeskRole.fromDisplayName(roleName);

        List<ToolExecutor> executors = toolRegistry.getExecutorsByWorkdeskRole(role);

        List<Map<String, Object>> capabilities = new ArrayList<>();
        for (ToolExecutor executor : executors) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolName", executor.getToolName());
            item.put("description", executor.getDescription());
            capabilities.add(item);
        }

        log.info("岗位能力清单: factoryId={}, role={}, total={}", factoryId, role, capabilities.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", role.displayName());
        result.put("total", capabilities.size());
        result.put("capabilities", capabilities);
        result.put("message", capabilities.isEmpty()
                ? "「" + role.displayName() + "」岗位暂无已登记的能力。"
                : "作为" + role.displayName() + "，你可以做 " + capabilities.size() + " 件事。");
        return result;
    }

    private Map<String, Object> allRolesOverview() {
        List<Map<String, Object>> roles = new ArrayList<>();
        int grandTotal = 0;
        for (WorkdeskRole role : WorkdeskRole.values()) {
            int count = toolRegistry.getExecutorsByWorkdeskRole(role).size();
            grandTotal += count;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", role.displayName());
            item.put("total", count);
            roles.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", null);
        result.put("roles", roles);
        result.put("total", grandTotal);
        result.put("message", "共 " + roles.size() + " 个工作台岗位，合计 " + grandTotal + " 项已登记能力。");
        return result;
    }
}
