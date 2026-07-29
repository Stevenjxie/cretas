package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.foodsafety.SsopExecutionRecord;
import com.cretas.aims.entity.foodsafety.SsopProcedure;
import com.cretas.aims.repository.foodsafety.SsopExecutionRecordRepository;
import com.cretas.aims.repository.foodsafety.SsopProcedureRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SSOP 生产开工 gate 检查 Tool (READ + RULE) — Sprint 9 P2.E Phase B.
 *
 * <p>Production planning / 生产任务下达前调用此 tool. 返回 {@code canStartProduction}
 * + 未完成的 SSOP records. 调用方根据 canStartProduction 决定是否阻产 + 写
 * {@code SsopBlockingGate} audit (但本 tool 是 READ-only, 不写 audit).
 *
 * <p>判定规则:
 * <pre>
 *   canStartProduction = true   当且仅当 今日所有 SCHEDULED+IN_PROGRESS+FAILED records 为空
 *                                       (即全部 records 都是 COMPLETED 或 SKIPPED)
 *
 *   blockedProcedures =       所有 status IN (SCHEDULED, IN_PROGRESS, FAILED) 的 records
 * </pre>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"开工前检查"</li>
 *   <li>"今天能开始生产吗"</li>
 *   <li>"SSOP gate 状态"</li>
 *   <li>"清洁完了吗"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code SSOP_PRODUCTION_GATE_CHECK}
 */
@Slf4j
@Component
public class SsopProductionGateCheckTool extends AbstractBusinessTool {

    private static final List<String> BLOCKING_STATUSES = List.of(
            "SCHEDULED", "IN_PROGRESS", "FAILED");

    @Autowired
    private SsopExecutionRecordRepository recordRepository;

    @Autowired
    private SsopProcedureRepository procedureRepository;

    @Override
    public String getToolName() {
        return "ssop_production_gate_check";
    }

    @Override
    public String getDescription() {
        return "SSOP 生产开工 gate 检查 (READ + RULE) — production planning 必调. "
                + "返回 canStartProduction + blockedProcedures 列表. "
                + "LLM 触发: '开工前检查' / '今天能开始生产吗' / 'SSOP gate 状态' / "
                + "'清洁完了吗'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> date = new HashMap<>();
        date.put("type", "string");
        date.put("description", "查询日期 ISO YYYY-MM-DD (默认今天)");
        properties.put("date", date);

        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String dateStr = getString(params, "date");
        LocalDate queryDate = dateStr != null && !dateStr.isBlank()
                ? LocalDate.parse(dateStr) : LocalDate.now();
        log.info("ssop_production_gate_check — factory={} date={}", factoryId, queryDate);

        // 查所有 today records — 不论 status
        List<SsopExecutionRecord> all = recordRepository
                .findByFactoryIdAndExecutionDateOrderByShiftAscIdAsc(factoryId, queryDate);

        // 未完成 = SCHEDULED + IN_PROGRESS + FAILED
        List<SsopExecutionRecord> blocked = recordRepository
                .findByFactoryIdAndExecutionDateAndStatusIn(factoryId, queryDate, BLOCKING_STATUSES);

        boolean canStartProduction = blocked.isEmpty();

        List<Map<String, Object>> blockedRows = new ArrayList<>();
        for (SsopExecutionRecord r : blocked) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recordId", r.getId());
            row.put("procedureId", r.getProcedureId());

            Optional<SsopProcedure> procOpt = procedureRepository.findById(r.getProcedureId());
            row.put("procedureName", procOpt.map(SsopProcedure::getName).orElse(null));
            row.put("procedureCode", procOpt.map(SsopProcedure::getProcedureCode).orElse(null));
            row.put("target", procOpt.map(SsopProcedure::getTarget).orElse(null));

            row.put("shift", r.getShift());
            row.put("status", r.getStatus());
            row.put("notes", r.getNotes());
            blockedRows.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", queryDate.toString());
        data.put("totalTasks", all.size());
        data.put("blockedCount", blocked.size());
        data.put("canStartProduction", canStartProduction);
        data.put("blockedProcedures", blockedRows);
        data.put("actionHint", canStartProduction
                ? null : "/foodsafety/ssop/today?filter=blocked");

        String message;
        if (canStartProduction) {
            message = String.format(
                    "✅ %s 工厂 %s SSOP gate 检查通过 — 全部 %d 项清洁任务已完成, 可开始生产",
                    factoryId, queryDate, all.size());
        } else {
            message = String.format(
                    "⛔ %s 工厂 %s 阻产 — %d 项 SSOP 清洁未完成 (含 %d 项失败). " +
                    "必须补做并 sign-off 后才能开始生产.",
                    factoryId, queryDate, blocked.size(),
                    blocked.stream().filter(r -> "FAILED".equals(r.getStatus())).count());
        }
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
