package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 密码修改工具
 *
 * 引导用户进行密码修改操作。
 * Intent Code: SYSTEM_PASSWORD_RESET
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class PasswordResetTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "password_reset";
    }

    @Override
    public String getDescription() {
        return "引导用户进行密码修改操作，提供修改密码的路径说明。" +
                "适用场景：用户要修改密码、忘记密码、重置密码。";
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
        log.info("密码修改引导 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "请前往【我的】-> 【账户安全】-> 【修改密码】进行密码修改。如忘记密码，请联系管理员重置。");

        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
