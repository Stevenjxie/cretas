package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.foodsafety.FoodSample;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.FoodSampleRepository;
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
 * 食品留样销毁/使用 Tool (WRITE + Preview BLOCKING) — Sprint 9 P2.A.
 *
 * <p>把指定 FoodSample 标记为 DISPOSED (按期销毁) 或 USED_FOR_INSPECTION (客户投诉拿去送检).
 * status → DISPOSED + audit log (disposedAt / disposedByUserId / disposeReason).
 *
 * <p>防呆 4 位一体 (per .claude/rules/fool-proof-design.md):
 * <ul>
 *   <li>R1: preview 显当前 status + sample_time + expire_time</li>
 *   <li>R2: message 含 batchNumber + productName (上下文)</li>
 *   <li>R3: reason enum: SCHEDULED / INSPECTION / CONTAMINATED (限定选择)</li>
 *   <li>R4: 已 DISPOSED → 返 ALREADY_DISPOSED 幂等</li>
 *   <li>BLOCKING: WRITE + 高 risk (销毁后无法恢复留样)</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"销毁留样 12345"</li>
 *   <li>"留样 12345 拿去送检"</li>
 *   <li>"标记留样 12345 已销毁"</li>
 *   <li>"客户投诉, 留样 12345 用于检测"</li>
 * </ul>
 *
 * <p>Intent Code: {@code FOOD_SAMPLE_DISPOSE}
 */
@Slf4j
@Component
public class FoodSampleDisposeTool extends AbstractBusinessTool {

    /** 销毁原因 enum (R3 防呆 — 限定选择). */
    private static final Set<String> VALID_REASONS = Set.of(
            "SCHEDULED",       // 按期销毁 (48h 到了)
            "INSPECTION",      // 用于检测 (客户投诉送检)
            "CONTAMINATED");   // 污染需销毁 (冷柜故障)

    @Autowired
    private FoodSampleRepository foodSampleRepository;

    @Override
    public String getToolName() {
        return "food_sample_dispose";
    }

    @Override
    public String getDescription() {
        return "销毁/使用食品留样 (标记 DISPOSED / USED_FOR_INSPECTION). "
                + "原因 enum: SCHEDULED (按期销毁) / INSPECTION (用于检测) / CONTAMINATED (污染). "
                + "LLM 触发: '销毁留样 X' / '留样 X 拿去送检' / '标记留样 X 已销毁' / "
                + "'客户投诉, 留样 X 用于检测'. WRITE + Preview BLOCKING + R4 幂等.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> id = new HashMap<>();
        id.put("type", "integer");
        id.put("description", "FoodSample.id (必填). e.g. 12345");
        properties.put("id", id);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "销毁原因 (必填). enum: SCHEDULED (按期销毁 48h 到) / " +
                "INSPECTION (用于检测) / CONTAMINATED (污染)");
        reason.put("enum", List.of("SCHEDULED", "INSPECTION", "CONTAMINATED"));
        properties.put("reason", reason);

        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "备注 (可选). e.g. '客户张三投诉拉肚子, 送疾控中心检测'");
        properties.put("notes", notes);

        schema.put("properties", properties);
        schema.put("required", List.of("id", "reason"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("id", "reason");
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

    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Long id = getLong(params, "id");
        String reason = getString(params, "reason");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("severity", "BLOCKING");
        result.put("id", id);
        result.put("reason", reason);

        // R3: reason enum 校验
        if (!VALID_REASONS.contains(reason)) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ reason 必须是 %s 之一, 实际 %s", VALID_REASONS, reason));
            return result;
        }

        Optional<FoodSample> opt = foodSampleRepository.findById(id);
        if (opt.isEmpty()) {
            result.put("canDo", false);
            result.put("message", String.format("⚠️ 留样 id=%d 不存在, 无法销毁", id));
            return result;
        }

        FoodSample s = opt.get();
        if (!factoryId.equals(s.getFactoryId())) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ 留样 id=%d 不属于当前工厂 (factory=%s), 无权销毁", id, factoryId));
            return result;
        }

        result.put("batchNumber", s.getBatchNumber());
        result.put("productName", s.getProductName());
        result.put("sampleQuantity", s.getSampleQuantity());
        result.put("currentStatus", s.getStatus());
        result.put("sampleTime", s.getSampleTime().toString());
        result.put("expireTime", s.getExpireTime().toString());

        // R4 幂等: 已 DISPOSED / USED_FOR_INSPECTION → 不重写
        if (!"RETAINED".equals(s.getStatus())) {
            result.put("canDo", false);
            result.put("status", "ALREADY_DISPOSED");
            result.put("message", String.format(
                    "ℹ️ 留样 id=%d (批次 %s, %s) 已是 %s 状态 (disposed_at=%s), 无需重复销毁",
                    id, s.getBatchNumber(), s.getProductName(), s.getStatus(),
                    s.getDisposedAt() != null ? s.getDisposedAt().toString() : "-"));
            return result;
        }

        String newStatus = "INSPECTION".equals(reason) ? "USED_FOR_INSPECTION" : "DISPOSED";
        result.put("newStatus", newStatus);
        result.put("canDo", true);
        result.put("message", String.format(
                "🚨 [BLOCKING] 确认销毁/使用留样 — id=%d, 批次 %s (%s), %sg. " +
                "当前 RETAINED → %s. 原因: %s",
                id, s.getBatchNumber(), s.getProductName(), s.getSampleQuantity(),
                newStatus, reason));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Long id = getLong(params, "id");
        String reason = getString(params, "reason");
        String notes = getString(params, "notes");
        Long userId = getUserId(context);

        log.info("food_sample_dispose — factory={} id={} reason={} userId={}",
                factoryId, id, reason, userId);

        // R3 enum 校验
        if (!VALID_REASONS.contains(reason)) {
            throw new BusinessException(400, String.format(
                    "reason 必须是 %s 之一, 实际 %s", VALID_REASONS, reason))
                    .withSeverity("BLOCKING");
        }

        Optional<FoodSample> opt = foodSampleRepository.findById(id);
        if (opt.isEmpty()) {
            throw new BusinessException(404, String.format(
                    "留样 id=%d 不存在, 无法销毁", id))
                    .withSeverity("BLOCKING");
        }

        FoodSample s = opt.get();
        if (!factoryId.equals(s.getFactoryId())) {
            throw new BusinessException(403, String.format(
                    "留样 id=%d 不属于当前工厂 (factory=%s), 无权销毁", id, factoryId))
                    .withSeverity("BLOCKING");
        }

        String prevStatus = s.getStatus();

        if (!"RETAINED".equals(prevStatus)) {
            // R4 幂等
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "ALREADY_DISPOSED");
            data.put("id", id);
            data.put("currentStatus", prevStatus);
            data.put("disposedAt", s.getDisposedAt() != null ? s.getDisposedAt().toString() : null);
            return buildSimpleResult(String.format(
                    "ℹ️ 留样 id=%d (批次 %s, %s) 已是 %s 状态, 跳过重复销毁",
                    id, s.getBatchNumber(), s.getProductName(), prevStatus), data);
        }

        String newStatus = "INSPECTION".equals(reason) ? "USED_FOR_INSPECTION" : "DISPOSED";
        LocalDateTime now = LocalDateTime.now();
        s.setStatus(newStatus);
        s.setDisposedAt(now);
        s.setDisposedByUserId(userId);
        String fullReason = notes != null && !notes.isEmpty()
                ? String.format("%s — %s", reason, notes)
                : reason;
        s.setDisposeReason(fullReason);
        foodSampleRepository.save(s);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("batchNumber", s.getBatchNumber());
        data.put("productName", s.getProductName());
        data.put("previousStatus", prevStatus);
        data.put("currentStatus", newStatus);
        data.put("disposedAt", now.toString());
        data.put("disposedByUserId", userId);
        data.put("disposeReason", fullReason);
        data.put("actionHint", "/foodsafety/food-samples/" + id);

        String message = String.format(
                "✅ 留样 id=%d (批次 %s, %s, %sg) 状态 %s → %s. 原因: %s",
                id, s.getBatchNumber(), s.getProductName(), s.getSampleQuantity(),
                prevStatus, newStatus, fullReason);
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
