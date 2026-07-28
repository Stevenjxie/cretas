package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 返回/撤销工具
 *
 * 处理用户请求返回或撤销操作的场景。
 * Intent Code: SYSTEM_GO_BACK / OPERATION_UNDO_OR_RECALL
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class SystemGoBackTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "system_go_back";
    }

    @Override
    public String getDescription() {
        return "处理返回或撤销操作的请求。" +
                "适用场景：用户说'返回'、'撤销'、'回退'等。";
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
        log.info("返回/撤销 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "已收到您的返回请求。请告诉我您接下来想查询什么？");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
