package com.cretas.aims.ai.tool.impl.user;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.user.CreateUserRequest;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 用户创建工具
 *
 * 创建新用户账号，包括设置用户名、角色、初始密码等。
 * 调用 UserService.createUser 执行实际创建。
 *
 * Intent Code: USER_CREATE
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class UserCreateTool extends AbstractBusinessTool {

    @Autowired
    private UserService userService;

    @Override
    public String getToolName() {
        return "user_create";
    }

    @Override
    public String getDescription() {
        return "创建新用户账号。支持设置用户名、姓名、角色、联系方式等基本信息。" +
                "适用场景：新员工入职开户、添加系统用户、创建操作员账号。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> username = new HashMap<>();
        username.put("type", "string");
        username.put("description", "用户名，用于登录，3-20位字母数字下划线");
        username.put("minLength", 3);
        username.put("maxLength", 20);
        properties.put("username", username);

        Map<String, Object> fullName = new HashMap<>();
        fullName.put("type", "string");
        fullName.put("description", "用户真实姓名/全名");
        properties.put("fullName", fullName);

        Map<String, Object> role = new HashMap<>();
        role.put("type", "string");
        role.put("description", "用户角色代码，如 operator(操作员), quality_inspector(质检员), " +
                "workshop_supervisor(车间主任), dispatcher(调度), warehouse_worker(仓库员), " +
                "hr_admin(HR管理员), factory_super_admin(工厂总监)");
        properties.put("role", role);

        Map<String, Object> phone = new HashMap<>();
        phone.put("type", "string");
        phone.put("description", "手机号码");
        properties.put("phone", phone);

        Map<String, Object> email = new HashMap<>();
        email.put("type", "string");
        email.put("description", "电子邮箱");
        properties.put("email", email);

        Map<String, Object> password = new HashMap<>();
        password.put("type", "string");
        password.put("description", "初始密码，6-20位，不提供则自动生成随机密码");
        properties.put("password", password);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("username", "fullName"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("username", "fullName");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行用户创建 - 工厂ID: {}, 参数: {}", factoryId, params);

        String username = getString(params, "username");
        String fullName = getString(params, "fullName");
        String roleStr = getString(params, "role");
        String phone = getString(params, "phone");
        String email = getString(params, "email");
        String password = getString(params, "password");

        // 构建创建请求
        CreateUserRequest createRequest = new CreateUserRequest();
        createRequest.setUsername(username);
        createRequest.setFullName(fullName);

        // 解析角色
        FactoryUserRole parsedRole = parseRole(roleStr);
        createRequest.setRoleCode(parsedRole != null ? parsedRole : FactoryUserRole.operator);

        if (phone != null) {
            createRequest.setPhone(phone);
        }

        // 设置邮箱（默认生成）
        if (email != null) {
            createRequest.setEmail(email);
        } else {
            createRequest.setEmail(username + "@cretas.local");
        }

        // 设置密码（默认随机生成）
        if (password != null && !password.trim().isEmpty()) {
            createRequest.setPassword(password);
        } else {
            createRequest.setPassword(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }

        // 调用 UserService 创建用户
        UserDTO created = userService.createUser(factoryId, createRequest);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("userId", created.getId());
        result.put("username", created.getUsername());
        result.put("fullName", created.getFullName());
        result.put("role", created.getRoleCode() != null ? created.getRoleCode().name() : "operator");
        result.put("roleName", created.getRoleCode() != null ? created.getRoleCode().getDisplayName() : "操作员");
        result.put("isActive", created.getIsActive());
        result.put("message", "用户 " + created.getFullName() + " 创建成功，用户名: " + created.getUsername());

        if (created.getPhone() != null) {
            result.put("phone", created.getPhone());
        }
        if (created.getEmail() != null) {
            result.put("email", created.getEmail());
        }

        log.info("用户创建完成 - 用户名: {}, 角色: {}, userId: {}",
                created.getUsername(),
                created.getRoleCode() != null ? created.getRoleCode().name() : "operator",
                created.getId());

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
            "username", "请问新用户的登录用户名是什么？（3-20位字母数字下划线）",
            "fullName", "请问新用户的真实姓名是什么？",
            "role", "请问新用户的角色是什么？（操作员/质检员/车间主任/调度/仓库员/HR管理员等）"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "username", "用户名",
            "fullName", "姓名",
            "role", "角色",
            "phone", "手机号",
            "email", "邮箱",
            "password", "初始密码"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
