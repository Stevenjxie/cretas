package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 意见反馈工具
 *
 * 引导用户提交意见反馈。
 * Intent Code: SYSTEM_FEEDBACK
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class SystemFeedbackTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "system_feedback";
    }

    @Override
    public String getDescription() {
        return "引导用户提交意见反馈或问题报告。" +
                "适用场景：用户说'反馈'、'建议'、'报告问题'等。";
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
        log.info("意见反馈引导 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "请前往【我的】-> 【意见反馈】提交您的建议或问题，我们会尽快处理。也可以直接告诉我您遇到的问题。");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
