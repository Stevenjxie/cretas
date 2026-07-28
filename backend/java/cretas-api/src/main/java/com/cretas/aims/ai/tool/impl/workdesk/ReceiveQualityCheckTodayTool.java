package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓管员今日待质检批次 Tool (Sprint 8 P4 — 仓管员 Workdesk V1).
 *
 * <p>F006 真场景: 仓管员张师傅今日入库后, 需提醒"哪些批次需要等质量主管来做 QC".
 * Cretas: 输出 status=INSPECTING 的批次 + 今日新入库 (createdAt=today) 的批次清单.
 *
 * <p>vs HJ 模式: 仓管员要去"质检管理"菜单找 → 再去"原材料管理"菜单找今日新入 (2 屏).
 * Cretas: 1 屏, 1 个 Tool.
 *
 * <p>Intent Code: {@code RECEIVE_QUALITY_CHECK_TODAY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4)
 */
@Slf4j
@Component
public class ReceiveQualityCheckTodayTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    @Override
    public String getToolName() {
        return "receive_quality_check_today";
    }

    @Override
    public String getDescription() {
        return "查询今日新入库 + 当前 INSPECTING 状态的物料批次, 提醒仓管员 / 质检员需做 QC. "
                + "LLM 触发场景: 仓管员问 '今天哪些要质检?' / '待质检批次' / "
                + "'今日入库 QC' / '哪些等质量主管来看' / '质检待办'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Collections.emptyMap(),
                "required", Collections.emptyList()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("receive_quality_check_today — factory={}", factoryId);

        // 1. 当前 INSPECTING 状态批次 (跨日)
        List<MaterialBatch> inspecting = materialBatchRepository
                .findByFactoryIdAndStatus(factoryId, MaterialBatchStatus.INSPECTING);

        // 2. 今日入库批次 (count + 列出)
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        long todayCount = materialBatchRepository
                .countByFactoryIdAndReceiptDateAfter(factoryId, todayStart);

        // 拉 factory 全部, 过滤 inboundDate=today (count 已知, list 仅在 count <= 100 时)
        List<Map<String, Object>> inspectingRows = new ArrayList<>();
        for (MaterialBatch b : inspecting) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchId", b.getId());
            row.put("batchNumber", b.getBatchNumber());
            row.put("materialTypeId", b.getMaterialTypeId());
            row.put("materialName", b.getMaterialType() != null
                    ? b.getMaterialType().getName() : null);
            row.put("receiptDate", b.getReceiptDate() != null
                    ? b.getReceiptDate().toString() : null);
            row.put("receiptQuantity", b.getReceiptQuantity());
            row.put("quantityUnit", b.getQuantityUnit());
            row.put("supplierName", b.getSupplier() != null
                    ? b.getSupplier().getName() : null);
            row.put("storageLocation", b.getStorageLocation());
            row.put("daysWaiting", b.getCreatedAt() != null
                    ? java.time.temporal.ChronoUnit.DAYS.between(
                            b.getCreatedAt().toLocalDate(), today)
                    : null);
            row.put("status", b.getStatus().name());
            inspectingRows.add(row);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("today", today.toString());
        data.put("todayInboundCount", todayCount);
        data.put("inspectingCount", inspectingRows.size());
        data.put("inspectingBatches", inspectingRows);

        String message;
        if (inspectingRows.isEmpty() && todayCount == 0) {
            message = "今日暂无新入库批次, 也无 INSPECTING 状态待质检, 仓库可正常发料";
        } else if (inspectingRows.isEmpty()) {
            message = String.format("今日新入库 %d 批, 暂未触发 INSPECTING 状态. "
                    + "如需逐批 QC, 请仓管员手动标记 INSPECTING 状态", todayCount);
        } else {
            message = String.format("⚠️ %d 批 INSPECTING 等待 QC (其中今日入库 %d 批). "
                    + "请通知质量主管尽快验收, 避免阻塞领料",
                    inspectingRows.size(), todayCount);
        }
        return buildSimpleResult(message, data);
    }

    // Unused but kept for potential future use (e.g. cross-zone date conversion)
    @SuppressWarnings("unused")
    private static LocalDateTime startOfTodayLocal() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
