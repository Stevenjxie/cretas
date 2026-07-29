package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 库存汇总查询工具
 *
 * 查询工厂的原材料库存汇总信息，包括总批次数、低库存预警数等。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class MaterialStockSummaryTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_stock_summary";
    }

    @Override
    public String getDescription() {
        return "查询原材料库存汇总信息。" +
                "返回总批次数、低库存预警数量、库存概览等。" +
                "适用场景：查看库存总览、了解整体库存水平、生成库存报告。";
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public boolean supportsPreview() {
        return false;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return false;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of(
                "warehouse:read",
                "warehouse:read_write",
                "inventory:read",
                "inventory:read_write");
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("material");
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
        log.info("查询库存汇总: factoryId={}", factoryId);

        PageResponse<MaterialBatchDTO> batchPage = materialBatchService.getMaterialBatchList(
                factoryId, PageRequest.of(1, 100));
        List<MaterialBatchDTO> batches = batchPage.getContent();
        long total = batchPage.getTotalElements();

        List<Map<String, Object>> lowStockWarnings = materialBatchService.getLowStockWarnings(factoryId);
        int lowStock = lowStockWarnings.size();

        Map<String, Object> result = new HashMap<>();
        result.put("totalBatches", total);
        result.put("lowStockCount", lowStock);
        result.put("batches", batches.size() > 20 ? batches.subList(0, 20) : batches);

        StringBuilder sb = new StringBuilder();
        sb.append("库存汇总：");
        sb.append("总批次数: ").append(total);
        sb.append("，低库存预警: ").append(lowStock).append("个");

        result.put("message", sb.toString());

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
