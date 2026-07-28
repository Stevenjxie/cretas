package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 批次更新工具
 *
 * 提供批次信息更新功能，支持更新批次状态、数量、备注等字段。
 * 适用场景：更新批次状态、修改批次数量、添加批次备注等。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class BatchUpdateTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    /**
     * 用户输入状态名称 → MaterialBatchStatus 枚举映射
     */
    private static final Map<String, MaterialBatchStatus> STATUS_ALIAS_MAP = Map.ofEntries(
            Map.entry("PENDING", MaterialBatchStatus.INSPECTING),
            Map.entry("AVAILABLE", MaterialBatchStatus.AVAILABLE),
            Map.entry("RESERVED", MaterialBatchStatus.RESERVED),
            Map.entry("IN_USE", MaterialBatchStatus.AVAILABLE),
            Map.entry("USED_UP", MaterialBatchStatus.USED_UP),
            Map.entry("EXPIRED", MaterialBatchStatus.EXPIRED),
            Map.entry("FROZEN", MaterialBatchStatus.FROZEN),
            Map.entry("REJECTED", MaterialBatchStatus.SCRAPPED),
            Map.entry("FRESH", MaterialBatchStatus.FRESH),
            Map.entry("SCRAPPED", MaterialBatchStatus.SCRAPPED),
            Map.entry("DEPLETED", MaterialBatchStatus.DEPLETED)
    );

    @Override
    public String getToolName() {
        return "batch_update";
    }

    @Override
    public String getDescription() {
        return "更新批次信息。支持更新批次状态、数量、有效期、备注等字段。" +
                "适用场景：修改批次状态（如标记为已用完、冻结）、调整数量、更新有效期。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "批次ID，必需参数");
        properties.put("batchId", batchId);

        // status: 状态（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "批次状态");
        status.put("enum", Arrays.asList(
                "PENDING",      // 待检验
                "AVAILABLE",    // 可用
                "RESERVED",     // 已预留
                "IN_USE",       // 使用中
                "USED_UP",      // 已用完
                "EXPIRED",      // 已过期
                "FROZEN",       // 冻品
                "REJECTED"      // 已拒收
        ));
        properties.put("status", status);

        // quantity: 数量（可选）
        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "更新后的数量");
        quantity.put("minimum", 0);
        properties.put("quantity", quantity);

        // expirationDate: 有效期（可选）
        Map<String, Object> expirationDate = new HashMap<>();
        expirationDate.put("type", "string");
        expirationDate.put("format", "date");
        expirationDate.put("description", "有效期，格式：YYYY-MM-DD");
        properties.put("expirationDate", expirationDate);

        // remark: 备注（可选）
        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "批次备注信息");
        properties.put("remark", remark);

        // reason: 更新原因（可选）
        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "更新原因，用于操作记录");
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("batchId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("batchId");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = new HashMap<>();
        questions.put("batchId", "请问您要更新哪个批次？请提供批次ID或批次号。");
        questions.put("status", "请问要将批次状态设置为什么？（如：可用、已用完、冻结等）");
        questions.put("quantity", "请问要将数量更新为多少？");
        questions.put("expirationDate", "请问新的有效期是什么时候？（格式：YYYY-MM-DD）");
        questions.put("reason", "请说明更新原因。");

        String question = questions.get(paramName);
        return question != null ? question : super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = new HashMap<>();
        displayNames.put("batchId", "批次ID");
        displayNames.put("status", "批次状态");
        displayNames.put("quantity", "数量");
        displayNames.put("expirationDate", "有效期");
        displayNames.put("remark", "备注");
        displayNames.put("reason", "更新原因");

        String name = displayNames.get(paramName);
        return name != null ? name : super.getParameterDisplayName(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行批次更新 - 工厂ID: {}, 参数: {}", factoryId, params);

        String batchId = getString(params, "batchId");
        String status = getString(params, "status");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String reason = getString(params, "reason");
        Long userId = getUserId(context);

        // 先验证批次是否存在
        MaterialBatchDTO existing = materialBatchService.getMaterialBatchById(factoryId, batchId);
        if (existing == null) {
            return Map.of("success", false, "message","批次不存在: " + batchId);
        }

        Map<String, Object> updatedFields = new HashMap<>();
        MaterialBatchDTO updated = null;

        // 1. 更新状态
        if (status != null) {
            MaterialBatchStatus targetStatus = STATUS_ALIAS_MAP.get(status.toUpperCase());
            if (targetStatus == null) {
                return Map.of("success", false, "message","不支持的批次状态: " + status + "。支持的状态: " + STATUS_ALIAS_MAP.keySet());
            }
            updated = materialBatchService.updateBatchStatus(factoryId, batchId, targetStatus);
            updatedFields.put("status", targetStatus.getDisplayName());
        }

        // 2. 调整数量
        // T-R5-4 (R5 audit §3 BUG#2, 2026-05-12): parameter description is
        // "更新后的数量" (ABSOLUTE / final quantity), so we must call the 5-arg
        // adjustBatchQuantity overload that sets newQuantity = param. Previously
        // routed to the 4-arg delta overload, so LLM passing quantity=50 ran
        // current + 50 — silent data corruption.
        if (quantity != null) {
            String adjustReason = reason != null ? reason : "AI工具调整";
            updated = materialBatchService.adjustBatchQuantity(factoryId, batchId, quantity, adjustReason, userId);
            updatedFields.put("quantity", quantity);
        }

        if (updatedFields.isEmpty()) {
            return buildSimpleResult("未指定要更新的字段", Map.of("batchId", batchId));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("batchNumber", existing.getBatchNumber());
        result.put("updatedFields", updatedFields);
        if (reason != null) result.put("reason", reason);
        result.put("message", "批次更新成功");

        log.info("批次更新完成 - 批次ID: {}, 更新字段: {}", batchId, updatedFields.keySet());
        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
