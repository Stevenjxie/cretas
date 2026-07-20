package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 已过期原材料批次查询工具
 *
 * 查询当前工厂所有已过期的原材料批次。
 * 返回包含批次号、原材料名称、过期日期、当前数量等关键信息的列表。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialExpiredQueryTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_expired_query";
    }

    @Override
    public String getDescription() {
        return "查询已过期的原材料批次。返回所有过期批次的详细信息，包括批次号、原材料名称、" +
                "过期日期、当前数量、供应商等。适用于过期物料盘点、库存清理、质量管理等场景。";
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

        // 无必需参数
        Map<String, Object> properties = new HashMap<>();
        schema.put("properties", properties);

        // 无必需字段
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // 无必需参数
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("查询已过期原材料批次: factoryId={}", factoryId);

        // 调用服务获取已过期批次
        List<MaterialBatchDTO> expiredBatches = materialBatchService.getExpiredBatches(factoryId);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("total", expiredBatches.size());

        if (expiredBatches.isEmpty()) {
            result.put("message", "当前没有已过期的原材料批次");
            result.put("batches", Collections.emptyList());
        } else {
            result.put("message", String.format("共有 %d 个已过期批次", expiredBatches.size()));
            // 转换为简洁格式
            List<Map<String, Object>> batchList = expiredBatches.stream()
                    .map(this::convertToSimpleFormat)
                    .collect(Collectors.toList());
            result.put("batches", batchList);
        }

        return result;
    }

    /**
     * 转换为简洁格式（避免返回过多字段）
     */
    private Map<String, Object> convertToSimpleFormat(MaterialBatchDTO batch) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("batchNumber", batch.getBatchNumber());
        item.put("materialName", batch.getMaterialName());
        item.put("materialCode", batch.getMaterialCode());
        item.put("expireDate", batch.getExpireDate() != null ? batch.getExpireDate().toString() : null);
        item.put("currentQuantity", batch.getCurrentQuantity());
        item.put("unit", batch.getUnit());
        item.put("supplierName", batch.getSupplierName());
        item.put("storageLocation", batch.getStorageLocation());
        item.put("status", batch.getStatus() != null ? batch.getStatus().name() : null);
        item.put("statusDisplayName", batch.getStatusDisplayName());
        return item;
    }
}
