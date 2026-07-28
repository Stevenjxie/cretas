package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.config.QualityCheckItemDTO;
import com.cretas.aims.service.QualityCheckItemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 关键质检项查询工具
 *
 * 获取工厂的关键质检项列表。
 *
 * Intent Code: QUALITY_CRITICAL_ITEMS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class QualityCriticalItemsTool extends AbstractBusinessTool {

    @Autowired
    private QualityCheckItemService qualityCheckItemService;

    @Override
    public String getToolName() {
        return "quality_critical_items";
    }

    @Override
    public String getDescription() {
        return "查询关键质检项。获取工厂所有标记为关键的质检项列表。" +
                "适用场景：查看关键质检指标、了解必须检验的质量项目。";
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
        log.info("执行关键质检项查询 - 工厂ID: {}", factoryId);

        List<QualityCheckItemDTO> criticalItems = qualityCheckItemService.getCriticalItems(factoryId);

        return buildSimpleResult(
                "查询到" + criticalItems.size() + "个关键质检项",
                Map.of(
                        "criticalItems", criticalItems,
                        "count", criticalItems.size()
                )
        );
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
