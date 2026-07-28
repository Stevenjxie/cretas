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
 * 即将过期原材料预警工具
 *
 * 查询即将在指定天数内过期的原材料批次。
 * 默认查询7天内过期的批次，可通过参数自定义预警天数。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialExpiringAlertTool extends AbstractBusinessTool {

    /**
     * 默认预警天数
     */
    private static final int DEFAULT_WARNING_DAYS = 7;

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_expiring_alert";
    }

    @Override
    public String getDescription() {
        return "查询即将过期的原材料批次预警。返回指定天数内即将过期的批次列表，包括批次号、" +
                "原材料名称、过期日期、剩余天数、当前数量等。默认查询7天内过期的批次。" +
                "适用于保质期管理、库存预警、先进先出提醒等场景。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // warningDays: 预警天数（可选，默认7天）
        Map<String, Object> warningDays = new HashMap<>();
        warningDays.put("type", "integer");
        warningDays.put("description", "预警天数，查询多少天内即将过期的批次。默认为7天。");
        warningDays.put("default", DEFAULT_WARNING_DAYS);
        warningDays.put("minimum", 1);
        warningDays.put("maximum", 365);
        properties.put("warningDays", warningDays);

        schema.put("properties", properties);

        // 无必需字段
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // warningDays 是可选参数
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 获取预警天数，默认7天
        Integer warningDays = getInteger(params, "warningDays", DEFAULT_WARNING_DAYS);

        log.info("查询即将过期原材料预警: factoryId={}, warningDays={}", factoryId, warningDays);

        // 调用服务获取即将过期批次
        List<MaterialBatchDTO> expiringBatches = materialBatchService.getExpiringBatches(factoryId, warningDays);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("total", expiringBatches.size());
        result.put("warningDays", warningDays);

        if (expiringBatches.isEmpty()) {
            result.put("message", String.format("未来 %d 天内没有即将过期的原材料批次", warningDays));
            result.put("batches", Collections.emptyList());
        } else {
            result.put("message", String.format("共有 %d 个批次将在 %d 天内过期", expiringBatches.size(), warningDays));
            // 转换为简洁格式，按剩余天数排序
            List<Map<String, Object>> batchList = expiringBatches.stream()
                    .sorted(Comparator.comparing(MaterialBatchDTO::getRemainingDays, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(this::convertToSimpleFormat)
                    .collect(Collectors.toList());
            result.put("batches", batchList);
        }

        return result;
    }

    /**
     * 转换为简洁格式（包含剩余天数）
     */
    private Map<String, Object> convertToSimpleFormat(MaterialBatchDTO batch) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("batchNumber", batch.getBatchNumber());
        item.put("materialName", batch.getMaterialName());
        item.put("materialCode", batch.getMaterialCode());
        item.put("expireDate", batch.getExpireDate() != null ? batch.getExpireDate().toString() : null);
        item.put("remainingDays", batch.getRemainingDays());
        item.put("currentQuantity", batch.getCurrentQuantity());
        item.put("unit", batch.getUnit());
        item.put("supplierName", batch.getSupplierName());
        item.put("storageLocation", batch.getStorageLocation());
        item.put("status", batch.getStatus() != null ? batch.getStatus().name() : null);
        item.put("statusDisplayName", batch.getStatusDisplayName());
        return item;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("warningDays".equals(paramName)) {
            return "请问您想查询多少天内即将过期的批次？默认是7天。";
        }
        return super.getParameterQuestion(paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
