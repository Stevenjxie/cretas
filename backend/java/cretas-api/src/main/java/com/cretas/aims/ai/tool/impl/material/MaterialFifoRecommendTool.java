package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FIFO原则批次推荐工具
 *
 * 根据先进先出（FIFO）原则，推荐应该优先使用的原材料批次。
 * 这是一个查询类工具，无需特殊权限。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialFifoRecommendTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_fifo_recommend";
    }

    @Override
    public String getDescription() {
        return "根据FIFO（先进先出）原则推荐应该优先使用的原材料批次。" +
                "系统会按照入库时间排序，优先推荐较早入库且未过期的批次。" +
                "适用于生产领料、库存分配等场景。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // materialTypeId: 原材料类型ID（必需）
        Map<String, Object> materialTypeId = new HashMap<>();
        materialTypeId.put("type", "string");
        materialTypeId.put("description", "原材料类型的唯一标识符，用于筛选特定类型的批次");
        properties.put("materialTypeId", materialTypeId);

        // requiredQuantity: 需求数量（必需）
        Map<String, Object> requiredQuantity = new HashMap<>();
        requiredQuantity.put("type", "number");
        requiredQuantity.put("description", "需要使用的原材料数量，系统将推荐足够满足此数量的批次组合");
        requiredQuantity.put("minimum", 0);
        properties.put("requiredQuantity", requiredQuantity);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("materialTypeId", "requiredQuantity"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("materialTypeId", "requiredQuantity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 获取参数
        String materialTypeId = getString(params, "materialTypeId");
        BigDecimal requiredQuantity = getBigDecimal(params, "requiredQuantity");

        // 验证数量
        if (requiredQuantity == null || requiredQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("需求数量必须大于0");
        }

        log.info("FIFO推荐查询: factoryId={}, materialTypeId={}, requiredQuantity={}",
                factoryId, materialTypeId, requiredQuantity);

        // 调用服务层获取FIFO推荐批次
        List<MaterialBatchDTO> fifoBatches = materialBatchService.getFIFOBatches(
                factoryId, materialTypeId, requiredQuantity);

        // 计算总可用数量
        BigDecimal totalAvailable = fifoBatches.stream()
                .map(MaterialBatchDTO::getCurrentQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 判断是否满足需求
        boolean isSufficient = totalAvailable.compareTo(requiredQuantity) >= 0;

        // 构建批次推荐列表
        List<Map<String, Object>> recommendations = fifoBatches.stream()
                .map(this::buildBatchInfo)
                .collect(Collectors.toList());

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("materialTypeId", materialTypeId);
        result.put("requiredQuantity", requiredQuantity);
        result.put("totalAvailable", totalAvailable);
        result.put("isSufficient", isSufficient);
        result.put("recommendedBatches", recommendations);
        result.put("batchCount", fifoBatches.size());

        // 生成友好消息
        if (fifoBatches.isEmpty()) {
            result.put("message", "未找到可用的原材料批次");
        } else if (isSufficient) {
            result.put("message", String.format("找到 %d 个推荐批次，总可用量 %s，满足需求量 %s",
                    fifoBatches.size(), totalAvailable, requiredQuantity));
        } else {
            result.put("message", String.format("找到 %d 个推荐批次，总可用量 %s，不足需求量 %s，缺口 %s",
                    fifoBatches.size(), totalAvailable, requiredQuantity,
                    requiredQuantity.subtract(totalAvailable)));
        }

        return result;
    }

    /**
     * 构建单个批次信息
     */
    private Map<String, Object> buildBatchInfo(MaterialBatchDTO batch) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("batchId", batch.getId());
        info.put("batchNumber", batch.getBatchNumber());
        info.put("currentQuantity", batch.getCurrentQuantity());
        info.put("unit", batch.getUnit());
        info.put("expirationDate", batch.getExpireDate());
        info.put("status", batch.getStatus());
        info.put("supplierName", batch.getSupplierName());
        info.put("receiptDate", batch.getReceiptDate());

        // 计算距离过期天数
        if (batch.getExpireDate() != null) {
            long daysToExpire = java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.LocalDate.now(), batch.getExpireDate());
            info.put("daysToExpire", daysToExpire);
            info.put("expirationWarning", daysToExpire <= 7 ? "即将过期" : null);
        }

        return info;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "materialTypeId", "请问您需要哪种原材料？请提供原材料类型ID或名称。",
                "requiredQuantity", "请问您需要多少数量？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "materialTypeId", "原材料类型",
                "requiredQuantity", "需求数量"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /**
     * 查询类工具，无需特殊权限
     */
    @Override
    public boolean requiresPermission() {
        return false;
    }

    /**
     * 所有角色都可使用
     */
    @Override
    public boolean hasPermission(String userRole) {
        return true;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
