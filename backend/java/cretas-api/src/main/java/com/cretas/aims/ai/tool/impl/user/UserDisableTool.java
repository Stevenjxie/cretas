package com.cretas.aims.ai.tool.impl.user;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 用户禁用工具
 *
 * 禁用指定用户账号，使其无法登录系统。
 * 支持通过 userId 或 username 定位用户。
 * 调用 UserService.deactivateUser 执行实际禁用。
 *
 * Intent Code: USER_DISABLE
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class UserDisableTool extends AbstractBusinessTool {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Override
    public String getToolName() {
        return "user_disable";
    }

    @Override
    public String getDescription() {
        return "禁用用户账号。禁用后用户将无法登录系统，可后续重新启用。" +
                "支持通过用户ID或用户名指定目标用户。" +
                "适用场景：员工离职、账号安全问题、临时停用账号。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> userId = new HashMap<>();
        userId.put("type", "string");
        userId.put("description", "要禁用的用户ID（数字）");
        properties.put("userId", userId);

        Map<String, Object> username = new HashMap<>();
        username.put("type", "string");
        username.put("description", "要禁用的用户名（当不知道userId时可用用户名查找）");
        properties.put("username", username);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "禁用原因说明");
        properties.put("reason", reason);

        schema.put("properties", properties);
        // userId 和 username 至少提供一个，在 doExecute 中校验
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // 动态校验：userId 或 username 至少一个
        return Collections.emptyList();
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.UPDATE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.HIGH;
    }

    @Override
    public boolean supportsPreview() {
        return false;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    /**
     * Legacy role-only callers cannot prove the permission-code contract, so they must not
     * advertise or execute this tool. The central RBAC path uses {@link #getRequiredPermissions()}.
     */
    @Override
    public boolean hasPermission(String userRole) {
        return false;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("hr:read_write");
    }

    @Override
    public String getVersion() {
        return "2.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("user", "hr", "identity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行用户禁用 - 工厂ID: {}, 参数: {}", factoryId, params);

        String userIdStr = getString(params, "userId");
        String username = getString(params, "username");
        String reason = getString(params, "reason");

        // 获取操作者信息
        Object contextOperatorId = context.get("userId");
        String operatorId = contextOperatorId != null ? String.valueOf(contextOperatorId) : null;

        // 解析目标用户ID
        Long targetUserId = null;

        if (userIdStr != null && !userIdStr.trim().isEmpty()) {
            try {
                targetUserId = Long.valueOf(userIdStr.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("用户ID格式无效，请提供有效的数字ID");
            }
        }

        // 如果没有 userId，尝试通过 username 查找
        if (targetUserId == null && username != null && !username.trim().isEmpty()) {
            Optional<User> userOpt = userRepository.findByFactoryIdAndUsername(factoryId, username.trim());
            if (userOpt.isEmpty()) {
                throw new IllegalArgumentException("未找到用户名为 '" + username + "' 的用户，请检查用户名是否正确");
            }
            User user = userOpt.get();
            targetUserId = user.getId();
            log.info("通过用户名查询到用户ID: username={}, userId={}", username, targetUserId);
        }

        if (targetUserId == null) {
            throw new IllegalArgumentException("请提供要禁用的用户ID或用户名");
        }

        // 不能禁用自己
        if (operatorId != null && String.valueOf(targetUserId).equals(operatorId)) {
            throw new IllegalArgumentException("不能禁用自己的账号");
        }

        // 调用 UserService 禁用用户
        userService.deactivateUser(factoryId, targetUserId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("userId", targetUserId);
        result.put("previousStatus", "ACTIVE");
        result.put("currentStatus", "DISABLED");
        result.put("message", "用户已禁用 (ID: " + targetUserId + ")");

        if (reason != null) {
            result.put("reason", reason);
        }
        if (username != null) {
            result.put("username", username);
        }

        log.info("用户禁用完成 - 用户ID: {}, 原因: {}", targetUserId, reason);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "userId", "请问要禁用哪个用户？请提供用户ID。",
            "username", "请问要禁用哪个用户？请提供用户名。",
            "reason", "请问禁用该用户的原因是什么？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "userId", "用户ID",
            "username", "用户名",
            "reason", "禁用原因"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }
}
