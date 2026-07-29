package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 删除员工工具
 *
 * 删除指定员工，需要确认操作。
 *
 * Intent Code: HR_DELETE_EMPLOYEE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class HRDeleteEmployeeTool extends AbstractBusinessTool {

    @Autowired
    private UserService userService;

    @Override
    public String getToolName() {
        return "hr_delete_employee";
    }

    @Override
    public String getDescription() {
        return "删除员工。根据员工ID删除员工记录，此操作不可恢复，需要确认。" +
                "适用场景：员工离职处理、清理无效员工记录。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> userId = new HashMap<>();
        userId.put("type", "integer");
        userId.put("description", "要删除的员工ID");
        properties.put("userId", userId);

        Map<String, Object> confirmed = new HashMap<>();
        confirmed.put("type", "boolean");
        confirmed.put("description", "是否已确认删除操作");
        confirmed.put("default", false);
        properties.put("confirmed", confirmed);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("userId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("userId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行删除员工 - 工厂ID: {}, 参数: {}", factoryId, params);

        Long targetUserId = getLong(params, "userId");
        Boolean confirmed = getBoolean(params, "confirmed", false);

        if (targetUserId == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "请提供要删除的员工ID (userId)");
            return result;
        }

        // Confirmation step
        if (!Boolean.TRUE.equals(confirmed)) {
            UserDTO user = userService.getUserById(factoryId, targetUserId);
            Map<String, Object> preview = new HashMap<>();
            preview.put("user", user);
            preview.put("action", "DELETE");
            preview.put("needConfirm", true);
            preview.put("message", "确认要删除员工「" + (user != null ? user.getUsername() : targetUserId) + "」吗？此操作不可恢复。请设置 confirmed=true 确认。");
            return preview;
        }

        userService.deleteUser(factoryId, targetUserId);

        return buildSimpleResult("员工已删除", Map.of("deletedUserId", targetUserId));
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "userId", "请提供要删除的员工ID",
                "confirmed", "请确认是否要执行删除操作？此操作不可恢复。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "userId", "员工ID",
                "confirmed", "确认删除"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "platform_admin".equals(userRole) ||
                "hr_admin".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
