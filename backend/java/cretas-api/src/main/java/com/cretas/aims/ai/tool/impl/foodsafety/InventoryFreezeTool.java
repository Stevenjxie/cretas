package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 冻结库存批次 Tool (WRITE + Preview, BLOCKING) — Sprint 8 P3 Phase B (食品召回闭环 step 5a).
 *
 * <p>把指定 batch 的状态置为 FROZEN, 阻止该批次进一步用于生产/出货.
 * 召回 SOP 必须先冻结涉事批次以避免次生污染.
 *
 * <p>防呆 4 位一体:
 * <ul>
 *   <li>R1: preview 显批次当前数量 + 状态, BLOCKING (modal 必须确认)</li>
 *   <li>R2: message 含 batchNumber + 物料类型 + 当前数量 (上下文)</li>
 *   <li>R3: reason 必填非空 (validateRequiredParams 强制)</li>
 *   <li>R4: 重复冻结 (已 FROZEN) → 返 status=ALREADY_FROZEN 不重写</li>
 *   <li>error sticky: BusinessException severity=BLOCKING</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"冻结 B-X 库存"</li>
 *   <li>"封存这批"</li>
 *   <li>"召回先冻结库存"</li>
 * </ul>
 *
 * <p>Intent Code: {@code INVENTORY_FREEZE}
 */
@Slf4j
@Component
public class InventoryFreezeTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    @Override
    public String getToolName() {
        return "inventory_freeze";
    }

    @Override
    public String getDescription() {
        return "冻结库存批次 — 把指定 batch 状态置为 FROZEN, 阻止用于生产/出货. WRITE + Preview (BLOCKING). "
                + "LLM 触发: '冻结 B-X 库存' / '封存这批' / '召回先冻结库存' / "
                + "'这批不许再发货'. 召回 SOP 第一步.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号 (必填). e.g. B-20260518-A03");
        properties.put("batchNumber", batchNumber);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "冻结原因 (必填). e.g. '客户投诉拉肚子, 启动召回'");
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", List.of("batchNumber", "reason"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber", "reason");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.WRITE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.HIGH;
    }

    /**
     * Preview: 查批次当前状态 + 数量, 返 BLOCKING 提示用户必须确认.
     */
    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        String reason = getString(params, "reason");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("severity", "BLOCKING");
        result.put("batchNumber", batchNumber);
        result.put("reason", reason);

        Optional<MaterialBatch> opt = materialBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        if (opt.isEmpty()) {
            result.put("canDo", false);
            result.put("message", String.format("⚠️ 批次 %s 不存在, 无法冻结", batchNumber));
            return result;
        }

        MaterialBatch batch = opt.get();
        result.put("materialTypeId", batch.getMaterialTypeId());
        result.put("currentStatus", batch.getStatus() != null ? batch.getStatus().name() : null);
        result.put("currentQuantity", batch.getReceiptQuantity());
        result.put("quantityUnit", batch.getQuantityUnit());

        if (batch.getStatus() == MaterialBatchStatus.FROZEN) {
            result.put("canDo", false);
            result.put("status", "ALREADY_FROZEN");
            result.put("message", String.format(
                    "ℹ️ 批次 %s (%s %s) 已是 FROZEN 状态, 无需重复冻结",
                    batchNumber, batch.getReceiptQuantity(), batch.getQuantityUnit()));
            return result;
        }

        result.put("canDo", true);
        result.put("message", String.format(
                "🚨 [BLOCKING] 确认冻结 → 批次 %s, %s %s, 原状态 %s. " +
                "冻结后此批次无法用于生产/出货. 冻结理由: %s",
                batchNumber, batch.getReceiptQuantity(), batch.getQuantityUnit(),
                batch.getStatus() != null ? batch.getStatus().name() : "UNKNOWN", reason));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        String reason = getString(params, "reason");
        Long userId = getUserId(context);

        log.info("inventory_freeze — factory={} batch={} reason={} userId={}",
                factoryId, batchNumber, reason, userId);

        Optional<MaterialBatch> opt = materialBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        if (opt.isEmpty()) {
            throw new BusinessException(404,
                    String.format("批次 %s 不存在, 无法冻结", batchNumber))
                    .withSeverity("BLOCKING");
        }

        MaterialBatch batch = opt.get();
        MaterialBatchStatus prevStatus = batch.getStatus();

        if (prevStatus == MaterialBatchStatus.FROZEN) {
            // R4 幂等: 已冻结 → 返 ALREADY_FROZEN 不重写
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ALREADY_FROZEN");
            result.put("batchNumber", batchNumber);
            result.put("currentStatus", "FROZEN");
            result.put("message", String.format("ℹ️ 批次 %s 已是 FROZEN 状态 (无需重复冻结)", batchNumber));
            return result;
        }

        // 状态更新 + audit notes (NOTE: 仅写 status + 在 notes 追加 audit 行)
        batch.setStatus(MaterialBatchStatus.FROZEN);
        String existingNotes = batch.getNotes() != null ? batch.getNotes() : "";
        String auditLine = String.format("[%s] [FREEZE by user=%s] %s (prev=%s)",
                LocalDateTime.now(), userId, reason, prevStatus);
        batch.setNotes(existingNotes.isEmpty() ? auditLine : existingNotes + "\n" + auditLine);
        materialBatchRepository.save(batch);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNumber", batchNumber);
        data.put("previousStatus", prevStatus != null ? prevStatus.name() : null);
        data.put("currentStatus", "FROZEN");
        data.put("quantity", batch.getReceiptQuantity());
        data.put("quantityUnit", batch.getQuantityUnit());
        data.put("frozenBy", userId);
        data.put("reason", reason);
        data.put("actionHint", "/inventory/material-batch?batchNumber=" + batchNumber);

        String message = String.format(
                "✅ 已冻结批次 %s (%s %s), 原状态 %s → FROZEN. 此批次无法用于生产/出货",
                batchNumber, batch.getReceiptQuantity(), batch.getQuantityUnit(),
                prevStatus != null ? prevStatus.name() : "UNKNOWN");
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
