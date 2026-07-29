package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 生产批次时间线工具
 *
 * 获取指定生产批次的操作时间线，包括创建、开始、暂停、恢复、完成等所有状态变更记录。
 * 适用于追踪批次的完整生产历程。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class ProcessingBatchTimelineTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "processing_batch_timeline";
    }

    @Override
    public String getDescription() {
        return "获取生产批次时间线。返回批次从创建到当前状态的所有操作记录，包括状态变更、暂停原因、完成数据等。" +
                "适用场景：追踪生产历程、查看操作记录、审计生产过程。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "生产批次ID或批次号");
        properties.put("batchId", batchId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行获取生产批次时间线 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 1. 解析参数
        String batchId = getString(params, "batchId");

        // 2. 调用服务获取时间线
        List<Map<String, Object>> timeline = processingService.getBatchTimeline(factoryId, batchId);

        // 3. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("timeline", timeline != null ? timeline : Collections.emptyList());
        result.put("eventCount", timeline != null ? timeline.size() : 0);

        // 生成摘要信息
        StringBuilder summary = new StringBuilder();
        summary.append("批次 ").append(batchId).append(" 的时间线：\n");

        if (timeline != null && !timeline.isEmpty()) {
            for (int i = 0; i < timeline.size(); i++) {
                Map<String, Object> event = timeline.get(i);
                summary.append(String.format("%d. [%s] %s",
                        i + 1,
                        event.get("timestamp") != null ? event.get("timestamp") : "N/A",
                        event.get("action") != null ? event.get("action") : event.get("event")));

                if (event.get("operator") != null) {
                    summary.append(" - 操作人: ").append(event.get("operator"));
                }
                if (event.get("reason") != null) {
                    summary.append(" - 原因: ").append(event.get("reason"));
                }
                summary.append("\n");
            }
        } else {
            summary.append("暂无操作记录");
        }

        result.put("message", summary.toString().trim());

        // 提取关键时间点
        Map<String, Object> keyMilestones = extractKeyMilestones(timeline);
        result.put("keyMilestones", keyMilestones);

        log.info("获取生产批次时间线完成 - 批次ID: {}, 事件数: {}", batchId, timeline != null ? timeline.size() : 0);

        return result;
    }

    /**
     * 从时间线中提取关键里程碑
     *
     * @param timeline 时间线列表
     * @return 关键里程碑
     */
    private Map<String, Object> extractKeyMilestones(List<Map<String, Object>> timeline) {
        Map<String, Object> milestones = new HashMap<>();

        if (timeline == null || timeline.isEmpty()) {
            return milestones;
        }

        for (Map<String, Object> event : timeline) {
            String action = event.get("action") != null ? event.get("action").toString() :
                    (event.get("event") != null ? event.get("event").toString() : "");
            Object timestamp = event.get("timestamp");

            // 根据操作类型提取关键时间点
            if (action.toLowerCase().contains("create") || action.contains("创建")) {
                milestones.put("createdAt", timestamp);
            } else if (action.toLowerCase().contains("start") || action.contains("开始")) {
                milestones.put("startedAt", timestamp);
            } else if (action.toLowerCase().contains("pause") || action.contains("暂停")) {
                // 记录最后一次暂停
                milestones.put("lastPausedAt", timestamp);
                // 累计暂停次数
                int pauseCount = milestones.containsKey("pauseCount") ?
                        (Integer) milestones.get("pauseCount") + 1 : 1;
                milestones.put("pauseCount", pauseCount);
            } else if (action.toLowerCase().contains("resume") || action.contains("恢复")) {
                milestones.put("lastResumedAt", timestamp);
            } else if (action.toLowerCase().contains("complete") || action.contains("完成")) {
                milestones.put("completedAt", timestamp);
            } else if (action.toLowerCase().contains("cancel") || action.contains("取消")) {
                milestones.put("cancelledAt", timestamp);
            }
        }

        return milestones;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "batchId":
                return "请问要查看哪个生产批次的时间线？请提供批次ID或批次号。";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "batchId":
                return "批次ID";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    @Override
    public boolean requiresPermission() {
        return false; // 查询类工具不需要特殊权限
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
