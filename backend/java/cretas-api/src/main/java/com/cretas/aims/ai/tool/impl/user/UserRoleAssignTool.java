package com.cretas.aims.ai.tool.impl.user;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 用户角色分配工具
 *
 * 为用户分配或更新角色权限。
 * 调用 UserService.updateUserRole 执行实际角色变更。
 *
 * Intent Code: USER_ROLE_ASSIGN
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class UserRoleAssignTool extends AbstractBusinessTool {

    @Autowired
    private UserService userService;

    @Override
    public String getToolName() {
        return "user_role_assign";
    }

    @Override
    public String getDescription() {
        return "为用户分配或更新角色。支持设置用户的系统角色和权限级别。" +
                "可用角色：operator(操作员), quality_inspector(质检员), workshop_supervisor(车间主任), " +
                "dispatcher(调度), warehouse_worker(仓库员), hr_admin(HR管理员), factory_super_admin(工厂总监) 等。" +
                "适用场景：员工岗位调整、权限提升/降级、角色变更。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> userId = new HashMap<>();
        userId.put("type", "string");
        userId.put("description", "要分配角色的用户ID（数字）");
        properties.put("userId", userId);

        Map<String, Object> role = new HashMap<>();
        role.put("type", "string");
        role.put("description", "要分配的角色代码，如 operator, quality_inspector, workshop_supervisor, " +
                "dispatcher, warehouse_worker, hr_admin, factory_super_admin, quality_manager, " +
                "warehouse_manager, sales_manager, procurement_manager, finance_manager, equipment_admin, viewer");
        properties.put("role", role);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "角色变更原因");
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("userId", "role"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("userId", "role");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行用户角色分配 - 工厂ID: {}, 参数: {}", factoryId, params);

        String userIdStr = getString(params, "userId");
        String roleStr = getString(params, "role");
        String reason = getString(params, "reason");

        // 解析用户ID
        Long targetUserId;
        try {
            targetUserId = Long.valueOf(userIdStr.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("用户ID格式无效，请提供有效的数字ID。例如: userId: 123");
        }

        // 解析角色
        FactoryUserRole newRole = parseRole(roleStr);
        if (newRole == null) {
            throw new IllegalArgumentException("无效的用户角色: " + roleStr + "。可用角色: " +
                    "operator(操作员), quality_inspector(质检员), workshop_supervisor(车间主任), " +
                    "dispatcher(调度), warehouse_worker(仓库员), hr_admin(HR管理员), " +
                    "factory_super_admin(工厂总监), quality_manager(质量经理), viewer(查看者)");
        }

        // 调用 UserService 更新角色
        userService.updateUserRole(factoryId, targetUserId, newRole);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("userId", targetUserId);
        result.put("newRole", newRole.name());
        result.put("newRoleName", newRole.getDisplayName());
        result.put("message", "用户角色已更新为: " + newRole.getDisplayName());

        if (reason != null) {
            result.put("reason", reason);
        }

        log.info("用户角色分配完成 - 用户ID: {}, 新角色: {}", targetUserId, newRole.name());

        return result;
    }

    /**
     * 解析角色字符串为枚举值
     */
    private FactoryUserRole parseRole(String roleStr) {
        if (roleStr == null) return null;
        String normalized = roleStr.toLowerCase().trim();

        return switch (normalized) {
            case "operator", "操作员" -> FactoryUserRole.operator;
            case "quality_inspector", "质检员" -> FactoryUserRole.quality_inspector;
            case "department_admin", "部门管理员" -> FactoryUserRole.department_admin;
            case "factory_super_admin", "工厂超管", "超级管理员", "工厂总监" -> FactoryUserRole.factory_super_admin;
            case "workshop_supervisor", "车间主管", "车间主任" -> FactoryUserRole.workshop_supervisor;
            case "dispatcher", "调度员", "调度" -> FactoryUserRole.dispatcher;
            case "hr_admin", "hr管理员" -> FactoryUserRole.hr_admin;
            case "quality_manager", "质量经理" -> FactoryUserRole.quality_manager;
            case "warehouse_worker", "仓库员" -> FactoryUserRole.warehouse_worker;
            case "warehouse_manager", "仓储主管" -> FactoryUserRole.warehouse_manager;
            case "sales_manager", "销售主管" -> FactoryUserRole.sales_manager;
            case "procurement_manager", "采购主管" -> FactoryUserRole.procurement_manager;
            case "finance_manager", "财务主管" -> FactoryUserRole.finance_manager;
            case "equipment_admin", "设备管理员" -> FactoryUserRole.equipment_admin;
            case "viewer", "查看者" -> FactoryUserRole.viewer;
            default -> null;
        };
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "userId", "请问要为哪个用户分配角色？请提供用户ID。",
            "role", "请问要分配什么角色？（操作员/质检员/车间主任/调度/仓库员/HR管理员/工厂总监等）"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "userId", "用户ID",
            "role", "角色",
            "reason", "变更原因"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
