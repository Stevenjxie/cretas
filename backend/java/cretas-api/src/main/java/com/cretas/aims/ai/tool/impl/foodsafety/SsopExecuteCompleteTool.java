package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.foodsafety.SsopExecutionRecord;
import com.cretas.aims.entity.foodsafety.SsopProcedure;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.SsopExecutionRecordRepository;
import com.cretas.aims.repository.foodsafety.SsopProcedureRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SSOP 清洁执行完成 + sign-off Tool (WRITE + Preview) — Sprint 9 P2.E Phase B.
 *
 * <p>清洁员完成清洁后, 调用此 tool 把 SCHEDULED → COMPLETED 并由检验员 sign-off.
 * 防呆设计:
 * <ul>
 *   <li>R1: preview 显当前 record 详情 + 已花费时间 (按 createdAt 算)</li>
 *   <li>R2: message 含程序 name + procedureCode + target 上下文</li>
 *   <li>R3: shift dropdown enum (MORNING/AFTERNOON/NIGHT) + 状态机校验</li>
 *   <li>R4: 已 COMPLETED/SKIPPED 的 record 不允许重复操作 → 返 status=ALREADY_DONE</li>
 * </ul>
 *
 * <p>状态机校验:
 * <ul>
 *   <li>当前 SCHEDULED / IN_PROGRESS → 允许 → COMPLETED / FAILED / SKIPPED</li>
 *   <li>当前 COMPLETED / FAILED / SKIPPED → 拒绝 (final state)</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"完成 SSOP-DAILY-LINE-A 的清洁"</li>
 *   <li>"标记清洁任务 N 已完成"</li>
 *   <li>"今天早班清洁已 sign-off"</li>
 * </ul>
 *
 * <p>Intent Code: {@code SSOP_EXECUTE_COMPLETE}
 */
@Slf4j
@Component
public class SsopExecuteCompleteTool extends AbstractBusinessTool {

    private static final Set<String> ALLOWED_RESULT_STATUSES = Set.of(
            "COMPLETED", "FAILED", "SKIPPED");

    private static final Set<String> ALLOWED_PREV_STATUSES = Set.of(
            "SCHEDULED", "IN_PROGRESS");

    @Autowired
    private SsopExecutionRecordRepository recordRepository;

    @Autowired
    private SsopProcedureRepository procedureRepository;

    @Override
    public String getToolName() {
        return "ssop_execute_complete";
    }

    @Override
    public String getDescription() {
        return "SSOP 清洁完成 + sign-off (WRITE + Preview) — 清洁员完成清洁后调用. "
                + "把 SCHEDULED/IN_PROGRESS → COMPLETED/FAILED/SKIPPED (R3 dropdown). "
                + "LLM 触发: '完成 SSOP-XX 的清洁' / '标记清洁任务 N 已完成' / "
                + "'今天早班清洁已 sign-off'. WRITE + Preview + 状态机 + sign-off.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> recordId = new HashMap<>();
        recordId.put("type", "integer");
        recordId.put("description", "SSOP execution record id (必填)");
        properties.put("recordId", recordId);

        Map<String, Object> resultStatus = new HashMap<>();
        resultStatus.put("type", "string");
        resultStatus.put("description", "结果状态 (必填): COMPLETED (清洁完成) / FAILED (检验员判不合格) / SKIPPED (跳过, e.g. 设备停机不需清洁)");
        resultStatus.put("enum", List.of("COMPLETED", "FAILED", "SKIPPED"));
        properties.put("resultStatus", resultStatus);

        Map<String, Object> inspectorUserId = new HashMap<>();
        inspectorUserId.put("type", "integer");
        inspectorUserId.put("description", "检验员 user_id (COMPLETED/FAILED 必填). 必须具有该模板 inspectorRole 角色");
        properties.put("inspectorUserId", inspectorUserId);

        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "备注 (e.g. SKIPPED 原因 / FAILED 原因 / 检验员意见). FAILED 或 SKIPPED 时必填");
        properties.put("notes", notes);

        schema.put("properties", properties);
        schema.put("required", List.of("recordId", "resultStatus"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("recordId", "resultStatus");
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
        return ToolExecutor.RiskLevel.MEDIUM;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Long recordId = getLong(params, "recordId");
        String resultStatus = getString(params, "resultStatus");
        Long inspectorUserId = getLong(params, "inspectorUserId");
        String notes = getString(params, "notes");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("recordId", recordId);
        result.put("targetResultStatus", resultStatus);

        // R3 enum 校验
        if (!ALLOWED_RESULT_STATUSES.contains(resultStatus)) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ 结果状态 %s 不合法. 合法值: %s",
                    resultStatus, String.join(", ", ALLOWED_RESULT_STATUSES)));
            return result;
        }

        Optional<SsopExecutionRecord> recOpt = recordRepository.findById(recordId);
        if (recOpt.isEmpty() || !factoryId.equals(recOpt.get().getFactoryId())) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ SSOP record id=%d 不存在或不属于工厂 %s", recordId, factoryId));
            return result;
        }
        SsopExecutionRecord rec = recOpt.get();
        result.put("currentStatus", rec.getStatus());

        // R2 上下文
        Optional<SsopProcedure> procOpt = procedureRepository.findById(rec.getProcedureId());
        procOpt.ifPresent(p -> {
            result.put("procedureName", p.getName());
            result.put("procedureCode", p.getProcedureCode());
            result.put("target", p.getTarget());
            result.put("inspectorRole", p.getInspectorRole());
        });

        // R4 状态机 — 已 final 状态拒绝
        if (!ALLOWED_PREV_STATUSES.contains(rec.getStatus())) {
            result.put("canDo", false);
            result.put("status", "ALREADY_DONE");
            result.put("actionHint", "/foodsafety/ssop/execution-records/" + recordId);
            result.put("message", String.format(
                    "ℹ️ 任务已是终态 %s, 不可重复操作. 当前 sign-off by user_id=%s @ %s",
                    rec.getStatus(), rec.getInspectorUserId(), rec.getInspectorSignedAt()));
            return result;
        }

        // sign-off 必填校验 (COMPLETED / FAILED)
        if (("COMPLETED".equals(resultStatus) || "FAILED".equals(resultStatus))
                && inspectorUserId == null) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ resultStatus=%s 必须提供 inspectorUserId (检验员 sign-off)",
                    resultStatus));
            return result;
        }

        // FAILED / SKIPPED 必须有 notes
        if (("FAILED".equals(resultStatus) || "SKIPPED".equals(resultStatus))
                && (notes == null || notes.isBlank())) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ resultStatus=%s 必须提供 notes (说明原因)", resultStatus));
            return result;
        }

        result.put("canDo", true);
        result.put("message", String.format(
                "📋 完成 SSOP — '%s' (%s, %s) 将从 %s → %s%s",
                result.get("procedureName"), result.get("procedureCode"),
                rec.getShift(), rec.getStatus(), resultStatus,
                inspectorUserId != null
                        ? String.format(" (检验员 user_id=%d sign-off)", inspectorUserId) : ""));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Long recordId = getLong(params, "recordId");
        String resultStatus = getString(params, "resultStatus");
        Long inspectorUserId = getLong(params, "inspectorUserId");
        String notes = getString(params, "notes");
        Long executorUserId = getUserId(context);

        log.info("ssop_execute_complete — factory={} recordId={} resultStatus={} inspectorUserId={} executorUserId={}",
                factoryId, recordId, resultStatus, inspectorUserId, executorUserId);

        // enum + 状态机校验 (mirror preview)
        if (!ALLOWED_RESULT_STATUSES.contains(resultStatus)) {
            throw new BusinessException(400,
                    String.format("结果状态 %s 不合法", resultStatus));
        }

        Optional<SsopExecutionRecord> recOpt = recordRepository.findById(recordId);
        if (recOpt.isEmpty() || !factoryId.equals(recOpt.get().getFactoryId())) {
            throw new BusinessException(404,
                    String.format("SSOP record id=%d 不存在或不属于工厂 %s", recordId, factoryId));
        }
        SsopExecutionRecord rec = recOpt.get();

        if (!ALLOWED_PREV_STATUSES.contains(rec.getStatus())) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "ALREADY_DONE");
            data.put("recordId", recordId);
            data.put("currentStatus", rec.getStatus());
            data.put("count", 0);
            data.put("actionHint", "/foodsafety/ssop/execution-records/" + recordId);
            return buildSimpleResult(String.format(
                    "ℹ️ SSOP record id=%d 已是终态 %s, 跳过", recordId, rec.getStatus()), data);
        }

        if (("COMPLETED".equals(resultStatus) || "FAILED".equals(resultStatus))
                && inspectorUserId == null) {
            throw new BusinessException(400,
                    String.format("resultStatus=%s 必须提供 inspectorUserId", resultStatus));
        }
        if (("FAILED".equals(resultStatus) || "SKIPPED".equals(resultStatus))
                && (notes == null || notes.isBlank())) {
            throw new BusinessException(400,
                    String.format("resultStatus=%s 必须提供 notes 说明原因", resultStatus));
        }

        Optional<SsopProcedure> procOpt = procedureRepository.findById(rec.getProcedureId());
        String procedureName = procOpt.map(SsopProcedure::getName).orElse("?");
        String procedureCode = procOpt.map(SsopProcedure::getProcedureCode).orElse("?");

        LocalDateTime now = LocalDateTime.now();
        rec.setStatus(resultStatus);
        rec.setCompletedAt(now);
        if (rec.getExecutorUserId() == null) {
            rec.setExecutorUserId(executorUserId);
        }
        if ("COMPLETED".equals(resultStatus) || "FAILED".equals(resultStatus)) {
            rec.setInspectorUserId(inspectorUserId);
            rec.setInspectorSignedAt(now);
        }
        if (notes != null && !notes.isBlank()) {
            rec.setNotes(notes);
        }
        rec = recordRepository.save(rec);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", rec.getId());
        data.put("procedureId", rec.getProcedureId());
        data.put("procedureName", procedureName);
        data.put("procedureCode", procedureCode);
        data.put("status", rec.getStatus());
        data.put("shift", rec.getShift());
        data.put("executorUserId", rec.getExecutorUserId());
        data.put("inspectorUserId", rec.getInspectorUserId());
        data.put("completedAt", rec.getCompletedAt().toString());
        data.put("inspectorSignedAt",
                rec.getInspectorSignedAt() != null ? rec.getInspectorSignedAt().toString() : null);
        data.put("actionHint", "/foodsafety/ssop/execution-records/" + rec.getId());

        String message = String.format(
                "✅ SSOP 已完成 — '%s' (%s) status=%s%s",
                procedureName, procedureCode, resultStatus,
                inspectorUserId != null
                        ? String.format(", 检验员 user_id=%d sign-off @ %s", inspectorUserId, now) : "");
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
