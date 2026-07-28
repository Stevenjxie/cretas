package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.auth.UserMenuPermission;
import com.cretas.aims.entity.auth.UserMenuPermission.GrantType;
import com.cretas.aims.repository.auth.UserMenuPermissionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas 用户级字段权限设置工具
 *
 * 设置用户级字段权限，指定某人能看/不能看某字段。
 * 基于 UserMenuPermission 实体，menuCode 格式为 "moduleCode:fieldCode:permission"。
 *
 * Intent Code: CANVAS_SET_USER_PERMISSION
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-09
 */
@Slf4j
@Component
public class CanvasSetUserPermissionTool extends AbstractBusinessTool {

    @Autowired
    private UserMenuPermissionRepository permRepo;

    private static final Set<String> VALID_PERMISSIONS = Set.of("hidden", "readonly", "visible");

    @Override
    public String getToolName() {
        return "canvas_set_user_permission";
    }

    @Override
    public String getDescription() {
        return "设置用户级字段权限（指定某人能看/不能看某字段）。适用场景：在 Canvas 配置界面控制特定员工对特定字段的访问权限，如禁止销售员查看成本价、允许财务查看毛利率。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> userId = new HashMap<>();
        userId.put("type", "string");
        userId.put("description", "用户ID，如 user_001 或数据库中的用户 ID");
        properties.put("userId", userId);

        Map<String, Object> moduleCode = new HashMap<>();
        moduleCode.put("type", "string");
        moduleCode.put("description", "模块代码，如 PURCHASE、SALES、FINANCE 等");
        properties.put("moduleCode", moduleCode);

        Map<String, Object> fieldCode = new HashMap<>();
        fieldCode.put("type", "string");
        fieldCode.put("description", "字段代码，如 costPrice、grossMargin 等");
        properties.put("fieldCode", fieldCode);

        Map<String, Object> permission = new HashMap<>();
        permission.put("type", "string");
        permission.put("description", "权限级别：hidden（隐藏）、readonly（只读）、visible（可见）");
        permission.put("enum", Arrays.asList("hidden", "readonly", "visible"));
        properties.put("permission", permission);

        Map<String, Object> grantType = new HashMap<>();
        grantType.put("type", "string");
        grantType.put("description", "授权类型：GRANT（追加授权）、REVOKE（撤销权限）");
        grantType.put("enum", Arrays.asList("GRANT", "REVOKE"));
        properties.put("grantType", grantType);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("userId", "moduleCode", "fieldCode", "permission", "grantType"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("userId", "moduleCode", "fieldCode", "permission", "grantType");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String userId = getString(params, "userId");
        String moduleCode = getString(params, "moduleCode");
        String fieldCode = getString(params, "fieldCode");
        String permission = getString(params, "permission");
        String grantTypeStr = getString(params, "grantType");

        log.info("Canvas 设置用户权限 - 工厂ID: {}, 用户: {}, 模块: {}, 字段: {}, 权限: {}, 类型: {}",
                factoryId, userId, moduleCode, fieldCode, permission, grantTypeStr);

        if (!VALID_PERMISSIONS.contains(permission)) {
            throw new IllegalArgumentException("无效的权限级别: " + permission + "。有效值为: hidden, readonly, visible");
        }

        GrantType grantType;
        try {
            grantType = GrantType.valueOf(grantTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的授权类型: " + grantTypeStr + "。有效值为: GRANT, REVOKE");
        }

        // menuCode 格式: moduleCode:fieldCode:permission
        String menuCode = moduleCode + ":" + fieldCode + ":" + permission;

        // 查找已存在的权限记录，存在则更新，不存在则创建
        Optional<UserMenuPermission> existing = permRepo.findByFactoryIdAndUserIdAndMenuCode(factoryId, userId, menuCode);

        UserMenuPermission perm;
        boolean isNew;
        if (existing.isPresent()) {
            perm = existing.get();
            perm.setGrantType(grantType);
            isNew = false;
        } else {
            perm = new UserMenuPermission();
            perm.setFactoryId(factoryId);
            perm.setUserId(userId);
            perm.setMenuCode(menuCode);
            perm.setGrantType(grantType);
            isNew = true;
        }

        UserMenuPermission saved = permRepo.save(perm);

        String action = GrantType.GRANT.equals(grantType) ? "授权" : "撤销";
        log.info("Canvas 设置用户权限完成 - ID: {}, {}权限: 用户 {} 对字段 {}.{} ({}) {}",
                saved.getId(), isNew ? "新建" : "更新", userId, moduleCode, fieldCode, permission, action);

        return buildSimpleResult(
                action + "成功：用户 " + userId + " 对字段 " + moduleCode + "." + fieldCode + " 的 " + permission + " 权限已" + action,
                Map.of(
                        "id", saved.getId(),
                        "factoryId", factoryId,
                        "userId", userId,
                        "moduleCode", moduleCode,
                        "fieldCode", fieldCode,
                        "permission", permission,
                        "grantType", grantType.name(),
                        "menuCode", menuCode,
                        "isNew", isNew
                )
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "userId", "请问要设置权限的用户 ID 是什么？",
                "moduleCode", "请问是哪个模块的权限？请提供模块代码（如 PURCHASE、SALES 等）。",
                "fieldCode", "请问是哪个字段的权限？请提供字段代码（如 costPrice、grossMargin 等）。",
                "permission", "请问权限级别是什么？可选：hidden（隐藏）、readonly（只读）、visible（可见）。",
                "grantType", "请问是追加授权（GRANT）还是撤销权限（REVOKE）？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "userId", "用户ID",
                "moduleCode", "模块代码",
                "fieldCode", "字段代码",
                "permission", "权限级别",
                "grantType", "授权类型"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
