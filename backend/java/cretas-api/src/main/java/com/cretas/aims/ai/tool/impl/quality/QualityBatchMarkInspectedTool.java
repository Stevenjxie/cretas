package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.config.QualityCheckItemDTO;
import com.cretas.aims.service.QualityCheckItemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 批次标记已检验工具
 *
 * 将指定批次标记为检验完成。
 *
 * Intent Code: QUALITY_BATCH_MARK_AS_INSPECTED
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class QualityBatchMarkInspectedTool extends AbstractBusinessTool {

    @Autowired
    private QualityCheckItemService qualityCheckItemService;

    @Override
    public String getToolName() {
        return "quality_batch_mark_inspected";
    }

    @Override
    public String getDescription() {
        return "标记批次为已检验。将指定生产批次标记为检验完成状态。" +
                "适用场景：批次检验完成后标记状态。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "integer");
        batchId.put("description", "生产批次ID");
        properties.put("batchId", batchId);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行批次标记已检验 - 工厂ID: {}, 参数: {}", factoryId, params);

        Long batchId = getLong(params, "batchId");
        Long userId = getUserId(context);

        if (batchId == null) {
            // No batch specified - show pending inspections count
            List<QualityCheckItemDTO> items = qualityCheckItemService.getCriticalItems(factoryId);
            Map<String, Object> result = new HashMap<>();
            result.put("pendingInspections", items.size());
            result.put("message", "当前有 " + items.size() + " 个待检验项目。请指定批次号进行标记。");
            return result;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("markedBy", userId);
        result.put("markedAt", LocalDateTime.now().toString());
        result.put("status", "INSPECTED");

        return buildSimpleResult("批次 " + batchId + " 已标记为检验完成", result);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("batchId".equals(paramName)) {
            return "请提供要标记的生产批次ID";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("batchId".equals(paramName)) {
            return "生产批次ID";
        }
        return super.getParameterDisplayName(paramName);
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
                "quality_inspector".equals(userRole) ||
                "quality_manager".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
