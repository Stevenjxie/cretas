package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 使用帮助工具
 *
 * 提供系统使用帮助和示例查询。
 * Intent Code: SYSTEM_HELP
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class SystemHelpTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "system_help";
    }

    @Override
    public String getDescription() {
        return "提供系统使用帮助和示例查询，列举可用的功能和操作示例。" +
                "适用场景：用户说'帮助'、'怎么用'、'有什么功能'等。";
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
        log.info("使用帮助 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "您可以问我关于库存、生产、质检、设备、发货、考勤等业务问题。例如：\n" +
                "- 「查看今天的库存」\n" +
                "- 「生产批次进度」\n" +
                "- 「设备运行状态」\n" +
                "- 「今天的发货情况」");
        result.put("categories", Arrays.asList(
                "库存管理", "生产管理", "质检管理", "设备管理", "发货管理", "考勤管理"
        ));

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
