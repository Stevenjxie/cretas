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
 * 今日 SSOP 清洁任务清单 Tool (READ + FILTER) — Sprint 9 P2.E Phase B.
 *
 * <p>Workdesk 主用 widget. 返回今日所有任务按 shift + status 分组. 内部计算 completionRate
 * 用于 production gate 显示.
 *
 * <p>分组:
 * <ul>
 *   <li>{@code scheduled} — SCHEDULED 待执行</li>
 *   <li>{@code inProgress} — IN_PROGRESS 进行中</li>
 *   <li>{@code completed} — COMPLETED 已完成 + sign-off</li>
 *   <li>{@code failed} — FAILED 失败</li>
 *   <li>{@code skipped} — SKIPPED 跳过</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"今天有哪些清洁任务"</li>
 *   <li>"今日 SSOP 清单"</li>
 *   <li>"还有哪些没清洁"</li>
 *   <li>"清洁完成率"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code SSOP_EXECUTION_TODAY}
 */
@Slf4j
@Component
public class SsopExecutionTodayTool extends AbstractBusinessTool {

    @Autowired
    private SsopExecutionRecordRepository recordRepository;

    @Autowired
    private SsopProcedureRepository procedureRepository;

    @Override
    public String getToolName() {
        return "ssop_execution_today";
    }

    @Override
    public String getDescription() {
        return "今日 SSOP 清洁任务清单 (READ + FILTER) — Workdesk widget 主用. "
                + "按 status 分组 (SCHEDULED/IN_PROGRESS/COMPLETED/FAILED/SKIPPED). "
                + "LLM 触发: '今天有哪些清洁任务' / '今日 SSOP 清单' / '还有哪些没清洁' / "
                + "'清洁完成率'. read-only.";
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
        log.info("ssop_execution_today — factory={} date={}", factoryId, queryDate);

        List<SsopExecutionRecord> all = recordRepository
                .findByFactoryIdAndExecutionDateOrderByShiftAscIdAsc(factoryId, queryDate);

        List<Map<String, Object>> scheduled = new ArrayList<>();
        List<Map<String, Object>> inProgress = new ArrayList<>();
        List<Map<String, Object>> completed = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();

        for (SsopExecutionRecord r : all) {
            Map<String, Object> row = buildRow(r);
            switch (r.getStatus()) {
                case "SCHEDULED":
                    scheduled.add(row);
                    break;
                case "IN_PROGRESS":
                    inProgress.add(row);
                    break;
                case "COMPLETED":
                    completed.add(row);
                    break;
                case "FAILED":
                    failed.add(row);
                    break;
                case "SKIPPED":
                    skipped.add(row);
                    break;
                default:
                    log.warn("Unknown SSOP record status: {} (id={})", r.getStatus(), r.getId());
            }
        }

        int total = all.size();
        int completedCount = completed.size() + skipped.size();
        double completionRate = total > 0 ? (double) completedCount / total * 100 : 0;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", queryDate.toString());
        data.put("totalTasks", total);
        data.put("scheduledCount", scheduled.size());
        data.put("inProgressCount", inProgress.size());
        data.put("completedCount", completed.size());
        data.put("failedCount", failed.size());
        data.put("skippedCount", skipped.size());
        data.put("completionRate", Math.round(completionRate * 100.0) / 100.0);
        data.put("canStartProduction", scheduled.isEmpty() && inProgress.isEmpty() && failed.isEmpty());
        data.put("scheduled", scheduled);
        data.put("inProgress", inProgress);
        data.put("completed", completed);
        data.put("failed", failed);
        data.put("skipped", skipped);
        data.put("actionHint", "/foodsafety/ssop/today");

        String message = String.format(
                "📋 工厂 %s, %s SSOP 任务: 共 %d 项 (待执行 %d 🟡, 进行中 %d 🔵, 已完成 %d ✅, 失败 %d ⛔, 跳过 %d). " +
                "完成率 %.2f%%.%s",
                factoryId, queryDate, total, scheduled.size(), inProgress.size(),
                completed.size(), failed.size(), skipped.size(), completionRate,
                (scheduled.isEmpty() && inProgress.isEmpty() && failed.isEmpty())
                        ? " ✅ 可开始生产" : " ⛔ 尚未全部完成, 阻产");
        return buildSimpleResult(message, data);
    }

    private Map<String, Object> buildRow(SsopExecutionRecord r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("procedureId", r.getProcedureId());

        Optional<SsopProcedure> procOpt = procedureRepository.findById(r.getProcedureId());
        row.put("procedureName", procOpt.map(SsopProcedure::getName).orElse(null));
        row.put("procedureCode", procOpt.map(SsopProcedure::getProcedureCode).orElse(null));
        row.put("target", procOpt.map(SsopProcedure::getTarget).orElse(null));
        row.put("inspectorRole", procOpt.map(SsopProcedure::getInspectorRole).orElse(null));

        row.put("shift", r.getShift());
        row.put("status", r.getStatus());
        row.put("executorUserId", r.getExecutorUserId());
        row.put("inspectorUserId", r.getInspectorUserId());
        row.put("completedAt", r.getCompletedAt() != null ? r.getCompletedAt().toString() : null);
        row.put("inspectorSignedAt",
                r.getInspectorSignedAt() != null ? r.getInspectorSignedAt().toString() : null);
        row.put("notes", r.getNotes());
        return row;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
