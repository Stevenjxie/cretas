package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 权限查询工具
 *
 * 查询当前用户的角色和权限信息。
 * Intent Code: SYSTEM_PERMISSION_QUERY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class PermissionQueryTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "permission_query";
    }

    @Override
    public String getDescription() {
        return "查询当前用户的角色和权限信息，告知用户可以访问的功能模块。" +
                "适用场景：用户说'我的权限'、'我能做什么'、'权限说明'等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("权限查询 - 工厂ID: {}", factoryId);

        String userRole = getString(context, "userRole");
        String roleDesc = userRole != null ? userRole : "未知";

        Map<String, Object> result = new HashMap<>();
        result.put("message", "您当前角色为【" + roleDesc + "】。如需调整权限，请联系工厂管理员。您可以前往【我的】-> 【权限说明】查看当前角色可访问的功能模块。");
        result.put("currentRole", roleDesc);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
