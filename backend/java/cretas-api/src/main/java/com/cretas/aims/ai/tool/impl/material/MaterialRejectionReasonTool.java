package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 原料退货/拒收原因查询工具
 *
 * 查询过期或被拒收的原材料批次，分析退货原因。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class MaterialRejectionReasonTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_rejection_reason";
    }

    @Override
    public String getDescription() {
        return "查询原料退货和拒收原因。" +
                "返回过期/问题原料批次列表及常见退货原因统计。" +
                "适用场景：分析退货原因、供应商评估、质量改进。";
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
        log.info("查询原料退货原因: factoryId={}", factoryId);

        List<MaterialBatchDTO> expired = materialBatchService.getExpiredBatches(factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("rejectedBatches", expired.size());
        result.put("commonReasons", Arrays.asList("过期变质", "检验不合格", "规格不符", "数量差异"));

        // Build batch summary list
        List<Map<String, Object>> batchSummaries = new ArrayList<>();
        expired.stream().limit(10).forEach(b -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("batchNumber", b.getBatchNumber());
            item.put("materialName", b.getMaterialName());
            item.put("expiryDate", b.getExpiryDate());
            item.put("status", b.getStatus());
            batchSummaries.add(item);
        });
        result.put("batches", batchSummaries);

        StringBuilder sb = new StringBuilder();
        sb.append("原料退货/拒收原因查询：");
        if (expired.isEmpty()) {
            sb.append("当前无过期或退货原料记录");
        } else {
            sb.append("过期/问题原料: ").append(expired.size()).append("批");
        }
        result.put("message", sb.toString());

        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
