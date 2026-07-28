package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 个人信息编辑工具
 *
 * 引导用户编辑个人资料。
 * Intent Code: SYSTEM_PROFILE_EDIT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ProfileEditTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "profile_edit";
    }

    @Override
    public String getDescription() {
        return "引导用户编辑个人资料，包括头像、手机号、邮箱等信息。" +
                "适用场景：修改个人信息、更新联系方式。";
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
        log.info("个人信息编辑引导 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "请前往【我的】-> 【个人资料】进行修改，可编辑头像、手机号、邮箱等信息。");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
