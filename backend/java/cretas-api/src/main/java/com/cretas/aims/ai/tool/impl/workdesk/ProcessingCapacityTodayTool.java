package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 今日产能查询工具 (Sprint 8 P1 — 卤味老板 Workdesk V1).
 *
 * <p>聚合今天的 ProductionBatch (按 startTime 在今日内 OR 状态=IN_PROGRESS) 输出
 * 当日总计划数 / 实际完成数 / 良品数 / 进行中批次. 用于"今天卤猪蹄能做多少?" / "今日产能"场景.
 *
 * <p>读路径无副作用. 防呆 R2 — 每条批次含 batchNumber / status / 计划/实际数量.
 *
 * <p>Intent Code: {@code PROCESSING_CAPACITY_TODAY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P1)
 */
@Slf4j
@Component
public class ProcessingCapacityTodayTool extends AbstractBusinessTool {

    private static final int MAX_RETURN = 200;

    @Autowired
    private ProductionBatchRepository productionBatchRepository;

    @Override
    public String getToolName() {
        return "processing_capacity_today";
    }

    @Override
    public String getDescription() {
        return "查询今日产能: 当日开始或仍在进行的 ProductionBatch 汇总 (计划数 / 实际完成 / 良品 / 不良品). "
                + "LLM 触发场景: 用户问 '今天卤猪蹄能做多少?' / '今日产能' / "
                + "'今天产线情况' / '今天能完成多少订单'. read-only.";
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
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("processing_capacity_today — factory={}", factoryId);

        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();

        // Pull batches created today + still in progress (no startTime yet)
        List<ProductionBatch> createdToday =
                productionBatchRepository.findByFactoryIdAndCreatedAtBetween(
                        factoryId, todayStart, tomorrowStart);

        // De-dup just in case
        Map<Long, ProductionBatch> byId = new HashMap<>();
        for (ProductionBatch b : createdToday) {
            if (b.getId() != null) byId.put(b.getId(), b);
        }
        List<ProductionBatch> batches = new ArrayList<>(byId.values());

        BigDecimal totalPlanned = BigDecimal.ZERO;
        BigDecimal totalActual = BigDecimal.ZERO;
        BigDecimal totalGood = BigDecimal.ZERO;
        BigDecimal totalDefect = BigDecimal.ZERO;
        int inProgress = 0;
        int completed = 0;
        int planned = 0;

        List<Map<String, Object>> items = new ArrayList<>(Math.min(MAX_RETURN, batches.size()));
        for (ProductionBatch b : batches) {
            if (items.size() < MAX_RETURN) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", b.getId());
                item.put("batchNumber", b.getBatchNumber());
                item.put("status", b.getStatus() != null ? b.getStatus().name() : null);
                item.put("plannedQuantity", b.getPlannedQuantity());
                item.put("actualQuantity", b.getActualQuantity());
                item.put("goodQuantity", b.getGoodQuantity());
                item.put("defectQuantity", b.getDefectQuantity());
                item.put("startTime", b.getStartTime() != null ? b.getStartTime().toString() : null);
                item.put("endTime", b.getEndTime() != null ? b.getEndTime().toString() : null);
                item.put("equipmentName", b.getEquipmentName());
                items.add(item);
            }

            totalPlanned = addNullSafe(totalPlanned, b.getPlannedQuantity());
            totalActual = addNullSafe(totalActual, b.getActualQuantity());
            totalGood = addNullSafe(totalGood, b.getGoodQuantity());
            totalDefect = addNullSafe(totalDefect, b.getDefectQuantity());

            if (b.getStatus() == null) continue;
            switch (b.getStatus().name()) {
                case "IN_PROGRESS": case "PAUSED": inProgress++; break;
                case "COMPLETED": completed++; break;
                case "PLANNED": planned++; break;
                default: break;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", today.toString());
        summary.put("batchCount", batches.size());
        summary.put("plannedCount", planned);
        summary.put("inProgressCount", inProgress);
        summary.put("completedCount", completed);
        summary.put("totalPlannedQuantity", totalPlanned);
        summary.put("totalActualQuantity", totalActual);
        summary.put("totalGoodQuantity", totalGood);
        summary.put("totalDefectQuantity", totalDefect);

        Map<String, Object> data = new HashMap<>();
        data.put("summary", summary);
        data.put("batches", items);

        String message = batches.isEmpty()
                ? "今日暂无生产批次"
                : String.format("今日 %d 个生产批次 (%d 进行中 / %d 已完成 / %d 计划中), 计划总量 %s, 实际完成 %s",
                        batches.size(), inProgress, completed, planned,
                        totalPlanned.toPlainString(), totalActual.toPlainString());
        return buildSimpleResult(message, data);
    }

    private BigDecimal addNullSafe(BigDecimal sum, BigDecimal addend) {
        if (addend == null) return sum;
        return sum.add(addend);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
