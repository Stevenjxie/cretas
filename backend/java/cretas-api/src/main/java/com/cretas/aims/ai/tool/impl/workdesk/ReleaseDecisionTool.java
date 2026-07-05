package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.quality.QualityDispositionInventoryService;
import com.cretas.aims.service.quality.QualityDispositionInventoryService.FlipDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 质量主管放行决策 Tool — Sprint 8 P4c (质量主管 Workdesk 灵魂 Tool).
 *
 * <p>用于质量主管基于综合 audit (质检 + HACCP + 添加剂 + 客户标准) 后做出最终
 * 放行 / 退货决策, 修改 MaterialBatch.status:
 * <ul>
 *   <li>{@code RELEASED} → INSPECTING / FRESH / FROZEN → AVAILABLE (放行进入流转)</li>
 *   <li>{@code REJECTED} → INSPECTING / FRESH / FROZEN → DEFECTIVE (拒收/退货)</li>
 * </ul>
 *
 * <p>防呆 4 位一体 + R1+R2+R3+R4:
 * <ul>
 *   <li>R1 max preview: 显当前 status + 新 status + 数量 + audit 结果</li>
 *   <li>R2 context: 含批次号 / 物料名 / 决策类型</li>
 *   <li>R3 dropdown: decision 限 'RELEASED' | 'REJECTED'</li>
 *   <li>R4 幂等: 已是 AVAILABLE → 重复放行返 ALREADY_RELEASED; 已 DEFECTIVE → ALREADY_REJECTED</li>
 * </ul>
 *
 * <p>WRITE + Preview BLOCKING (放行决策不可撤销, 必须确认).
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"放行 B-X"</li>
 *   <li>"卤猪蹄批次放行"</li>
 *   <li>"退货 B-X 不合格"</li>
 *   <li>"质检通过准放"</li>
 * </ul>
 *
 * <p>Intent Code: {@code RELEASE_DECISION}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4c)
 */
@Slf4j
@Component
public class ReleaseDecisionTool extends AbstractBusinessTool {

    /** R3 valid decisions (dropdown, not free text). */
    private static final Set<String> VALID_DECISIONS = Set.of("RELEASED", "REJECTED");

    /**
     * 可放行/拒收的源 status 白名单 — 与质检处置→库存桥接 {@link QualityDispositionInventoryService#RELEASABLE_FROM}
     * <b>共用同一 single source of truth</b> (防两条放行路径逻辑漂移)。DEFECTIVE / USED_UP 等已是终态不可改。
     */
    private static final Set<MaterialBatchStatus> RELEASABLE_FROM =
            QualityDispositionInventoryService.RELEASABLE_FROM;

    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    @Override
    public String getToolName() {
        return "release_decision";
    }

    @Override
    public String getDescription() {
        return "质量主管批次放行决策 (WRITE + Preview BLOCKING) — 修改 MaterialBatch.status: " +
                "RELEASED → AVAILABLE (放行进入流转) / REJECTED → DEFECTIVE (退货). " +
                "LLM 触发: '放行 B-X' / '卤猪蹄批次放行' / '退货 B-X 不合格' / '质检通过准放'. " +
                "防呆 R1+R2+R3+R4: preview 必显当前/新 status + 数量 + 决策类型. " +
                "R3 decision 限 'RELEASED' | 'REJECTED'. R4 幂等防重复决策.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号 (必填), e.g. B-20260518-A03");
        properties.put("batchNumber", batchNumber);

        Map<String, Object> decision = new HashMap<>();
        decision.put("type", "string");
        decision.put("enum", List.of("RELEASED", "REJECTED"));
        decision.put("description", "决策: RELEASED (放行进入流转) | REJECTED (退货/废弃)");
        properties.put("decision", decision);

        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description",
                "决策理由 (REJECTED 必填). e.g. '质检合格率 99%, 放行' / '亚硝酸盐超限, 退货'");
        properties.put("notes", notes);

        schema.put("properties", properties);
        schema.put("required", List.of("batchNumber", "decision"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber", "decision");
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
        String batchNumber = getString(params, "batchNumber");
        String decision = getString(params, "decision");
        String notes = getString(params, "notes", "");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("severity", "BLOCKING");
        result.put("batchNumber", batchNumber);
        result.put("decision", decision);
        result.put("notes", notes);

        // R3 校验 decision enum
        if (!VALID_DECISIONS.contains(decision)) {
            result.put("canDo", false);
            result.put("status", "INVALID_DECISION");
            result.put("message", String.format(
                    "⚠️ decision='%s' 非法, 必须是 'RELEASED' 或 'REJECTED'", decision));
            return result;
        }

        // REJECTED 必填 notes
        if ("REJECTED".equals(decision) && notes.isBlank()) {
            result.put("canDo", false);
            result.put("status", "MISSING_REJECT_REASON");
            result.put("message", "⚠️ 退货决策必须填 notes (拒收理由), 例如: '亚硝酸盐超限'");
            return result;
        }

        Optional<MaterialBatch> opt = materialBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        if (opt.isEmpty()) {
            result.put("canDo", false);
            result.put("status", "BATCH_NOT_FOUND");
            result.put("message", String.format("⚠️ 批次 %s 不存在或非本工厂", batchNumber));
            return result;
        }

        MaterialBatch batch = opt.get();
        MaterialBatchStatus currentStatus = batch.getStatus();
        result.put("materialTypeId", batch.getMaterialTypeId());
        result.put("currentStatus", currentStatus != null ? currentStatus.name() : "UNKNOWN");
        result.put("currentStatusDisplay",
                currentStatus != null ? currentStatus.getDisplayName() : "未知");
        result.put("currentQuantity", batch.getReceiptQuantity());
        result.put("quantityUnit", batch.getQuantityUnit());
        result.put("productionDate", batch.getProductionDate());
        result.put("expireDate", batch.getExpireDate());

        MaterialBatchStatus newStatus = "RELEASED".equals(decision)
                ? MaterialBatchStatus.AVAILABLE
                : MaterialBatchStatus.DEFECTIVE;
        result.put("newStatus", newStatus.name());
        result.put("newStatusDisplay", newStatus.getDisplayName());

        // R4 幂等检查
        if ("RELEASED".equals(decision) && currentStatus == MaterialBatchStatus.AVAILABLE) {
            result.put("canDo", false);
            result.put("status", "ALREADY_RELEASED");
            result.put("message", String.format(
                    "ℹ️ 批次 %s 已是 AVAILABLE (已放行), 无需重复决策", batchNumber));
            return result;
        }
        if ("REJECTED".equals(decision) && currentStatus == MaterialBatchStatus.DEFECTIVE) {
            result.put("canDo", false);
            result.put("status", "ALREADY_REJECTED");
            result.put("message", String.format(
                    "ℹ️ 批次 %s 已是 DEFECTIVE (已退货), 无需重复决策", batchNumber));
            return result;
        }

        // 状态合法性: 是否可改
        if (currentStatus != null && !RELEASABLE_FROM.contains(currentStatus)
                && currentStatus != MaterialBatchStatus.AVAILABLE
                && currentStatus != MaterialBatchStatus.DEFECTIVE) {
            result.put("canDo", false);
            result.put("status", "INVALID_TRANSITION");
            result.put("message", String.format(
                    "⛔ 批次 %s 当前 status=%s 不可改 (DEPLETED/USED_UP/EXPIRED/SCRAPPED 等终态)",
                    batchNumber, currentStatus.name()));
            return result;
        }

        result.put("canDo", true);
        result.put("message", String.format(
                "🔒 [BLOCKING] 确认决策 → 批次 %s (%s %s), 从 %s 变更为 %s. " +
                "%s. 此决策记入 audit log 不可撤销.",
                batchNumber, batch.getReceiptQuantity(), batch.getQuantityUnit(),
                currentStatus != null ? currentStatus.name() : "UNKNOWN",
                newStatus.name(),
                "RELEASED".equals(decision) ? "放行后可用于生产/出货" : "退货后不可用, 待后续处置"));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        String decision = getString(params, "decision");
        String notes = getString(params, "notes", "");
        Long userId = getUserId(context);

        log.info("release_decision — factory={} batch={} decision={} notes={} userId={}",
                factoryId, batchNumber, decision, notes, userId);

        // R3 重做校验 (防 client 绕 preview)
        if (!VALID_DECISIONS.contains(decision)) {
            throw new IllegalArgumentException(
                    String.format("decision='%s' 非法, 必须是 RELEASED 或 REJECTED", decision));
        }
        if ("REJECTED".equals(decision) && notes.isBlank()) {
            throw new IllegalArgumentException("退货决策必须填 notes (拒收理由)");
        }

        Optional<MaterialBatch> opt = materialBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        if (opt.isEmpty()) {
            throw new IllegalArgumentException(
                    String.format("批次 %s 不存在或非本工厂", batchNumber));
        }
        MaterialBatch batch = opt.get();
        MaterialBatchStatus prevStatus = batch.getStatus();

        MaterialBatchStatus newStatus = "RELEASED".equals(decision)
                ? MaterialBatchStatus.AVAILABLE
                : MaterialBatchStatus.DEFECTIVE;

        // R4 幂等 + 终态校验 — 复用与质检处置桥接共享的纯函数守卫 (single source of truth, 防漂移)
        FlipDecision flip = QualityDispositionInventoryService.evaluateMaterialFlip(prevStatus, newStatus);
        if (flip == FlipDecision.ALREADY_IN_TARGET) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "RELEASED".equals(decision) ? "ALREADY_RELEASED" : "ALREADY_REJECTED");
            data.put("batchNumber", batchNumber);
            data.put("currentStatus", prevStatus.name());
            data.put("message", String.format(
                    "ℹ️ 批次 %s 已是 %s, 决策未重复执行", batchNumber, prevStatus.name()));
            return data;
        }
        if (flip == FlipDecision.INVALID_TERMINAL) {
            throw new IllegalStateException(String.format(
                    "批次 %s 当前 status=%s 不可改 (终态)", batchNumber,
                    prevStatus != null ? prevStatus.name() : "UNKNOWN"));
        }

        batch.setStatus(newStatus);
        materialBatchRepository.save(batch);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNumber", batchNumber);
        data.put("decision", decision);
        data.put("previousStatus", prevStatus != null ? prevStatus.name() : null);
        data.put("newStatus", newStatus.name());
        data.put("notes", notes);
        data.put("decidedBy", userId);
        data.put("nextActionHint", "RELEASED".equals(decision)
                ? "批次已放行, 可在生产/出货流程中使用"
                : "批次已退货, 请联系采购员协调退货流程");

        String message = String.format(
                "%s 批次 %s 决策已执行: %s → %s%s",
                "RELEASED".equals(decision) ? "✅" : "⛔",
                batchNumber,
                prevStatus != null ? prevStatus.name() : "UNKNOWN",
                newStatus.name(),
                notes.isBlank() ? "" : " (备注: " + notes + ")");
        return buildSimpleResult(message, data);
    }
}
