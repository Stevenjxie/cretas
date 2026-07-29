package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.inventory.PurchaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sprint 10 Loop 2 — 入库/收货 AI 闭环 Tool.
 *
 * <p><b>Loop 2 与 Sprint 8 P4a 的 ReceiveWithLimitTool 的关系</b>:
 * <ul>
 *   <li>ReceiveWithLimitTool (Sprint 8 P4a, 2026-05-20) — 创建 DRAFT 入库单, 仍需人工
 *       Confirm 才正式更新库存. 适合"快速登记到货, 之后再走质检/确认"场景.</li>
 *   <li><b>ReceiveConfirmCreateTool (本 Tool, Sprint 10 Loop 2)</b> — 一气呵成 创建 +
 *       Confirm, 直接生成 MaterialBatch + 触发库存+, 适合"全量到货 + 现场签收"场景.
 *       覆盖 R1 (max preview) + R2 (context: 品名/单号/供应商) + R3 (status dropdown) +
 *       R4 (5min idempotent businessKey).</li>
 * </ul>
 *
 * <p><b>aiInvocationMetadata 写入</b>: 创建 PurchaseReceiveRecord 后, 显式 set
 * {@code {source: "sprint-10-loop-2", testRun: bool, createdAt: iso}} 然后 save 一次,
 * 用于 cleanup script + AI 闭环成功率统计.
 *
 * <p><b>R3 receiveStatus dropdown</b>: PASS / PARTIAL_LOST / DAMAGED / OTHER —
 * 写入 PurchaseReceiveItem.qcResult (现有列, 不加新列). 防呆 R3 让仓管员选标准枚举
 * 而不是自由文本.
 *
 * <p><b>R4 idempotent</b>: 创建前查 5min 窗口内同 PO+date 是否已有 PENDING/DRAFT,
 * 命中即返 409 风格的 {status: "DUPLICATE", existingId, actionHint}, 防止 double click /
 * AI 重复调用 / 多端并发.
 *
 * <p>Intent Code: {@code RECEIVE_CONFIRM_CREATE}.
 *
 * @author Cretas Team
 * @since 2026-05-21 (Sprint 10 Loop 2)
 */
@Slf4j
@Component
public class ReceiveConfirmCreateTool extends AbstractBusinessTool {

    /** 允许超收上限 (30% 行业惯例, mirror ReceiveWithLimitTool.OVER_RECEIVE_PCT). */
    private static final BigDecimal OVER_RECEIVE_PCT = new BigDecimal("0.30");

    /** R3 收货状态枚举 (写入 PurchaseReceiveItem.qcResult, 不加新列). */
    private static final List<String> ALLOWED_RECEIVE_STATUSES =
            List.of("PASS", "PARTIAL_LOST", "DAMAGED", "OTHER");

    /** R4 idempotent 窗口 (分钟) — 5min 内同 PO+date 第 2 次 invoke 返 DUPLICATE. */
    private static final int DEDUP_WINDOW_MINUTES = 5;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private PurchaseReceiveRecordRepository receiveRecordRepository;

    @Autowired
    private PurchaseService purchaseService;

    @Override
    public String getToolName() {
        return "receive_confirm_create";
    }

    @Override
    public String getDescription() {
        return "Sprint 10 Loop 2 — AI 闭环 入库/收货 (R1 max + R2 context + R3 status + R4 idempotent). "
                + "LLM 触发场景: 用户在 Workdesk 选某行 PO 确认收货 / '确认收到 X 件' / '签收入库 PO-Y'. "
                + "WRITE — 一气呵成: 创建 PurchaseReceiveRecord (DRAFT) → 立即 confirmReceive → "
                + "创建 MaterialBatch → 库存+. 失败任何环节, 事务整体回滚. "
                + "防呆 R1 max + R3 收货状态 dropdown + R4 5min 同 PO 幂等 → 仓管员零认知负荷.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> poId = new HashMap<>();
        poId.put("type", "string");
        poId.put("description", "采购订单 ID (必填) — Workdesk 选行时由前端 fill.");
        properties.put("poId", poId);

        Map<String, Object> lineId = new HashMap<>();
        lineId.put("type", "integer");
        lineId.put("description", "采购订单行项目 ID (必填).");
        properties.put("lineId", lineId);

        Map<String, Object> receivedQty = new HashMap<>();
        receivedQty.put("type", "number");
        receivedQty.put("description", "本次实收数量 (必填, 必须 > 0). 系统校验 30% 超收上限.");
        properties.put("receivedQty", receivedQty);

        Map<String, Object> receiveStatus = new HashMap<>();
        receiveStatus.put("type", "string");
        receiveStatus.put("description", "R3 防呆 — 收货状态枚举 (可选, 默认 PASS). " +
                "PASS / PARTIAL_LOST / DAMAGED / OTHER. 写入 receive item qcResult 列.");
        properties.put("receiveStatus", receiveStatus);

        Map<String, Object> signatureConfirmed = new HashMap<>();
        signatureConfirmed.put("type", "boolean");
        signatureConfirmed.put("description", "可选 — 是否已验签 (true=仓管员现场签收). 默认 false.");
        properties.put("signatureConfirmed", signatureConfirmed);

        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "可选 — 入库备注 (e.g. '到货 OK' / '部分破损').");
        properties.put("remark", remark);

        Map<String, Object> testRun = new HashMap<>();
        testRun.put("type", "boolean");
        testRun.put("description", "内部 E2E 标记 (Playwright 调用时 true), " +
                "写入 aiInvocationMetadata.testRun. 默认 false (生产真实创建).");
        properties.put("testRun", testRun);

        schema.put("properties", properties);
        schema.put("required", List.of("poId", "lineId", "receivedQty"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("poId", "lineId", "receivedQty");
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

    // ==================== Preview ====================

    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String poId = getString(params, "poId");
        Long lineId = getLong(params, "lineId");
        BigDecimal proposed = getBigDecimal(params, "receivedQty");
        String receiveStatus = getString(params, "receiveStatus", "PASS");
        validateBasic(poId, lineId, proposed);
        validateReceiveStatus(receiveStatus);

        // 1. 取 PO + line（使用 factory-scoped finder — 防跨租户 ID 猜测）
        Optional<PurchaseOrder> poOpt = purchaseOrderRepository.findByIdAndFactoryId(poId, factoryId);
        if (poOpt.isEmpty()) {
            return buildInvalid("PO_NOT_FOUND",
                    String.format("⚠️ 采购单 %s 不存在或非本工厂", poId));
        }
        PurchaseOrder po = poOpt.get();

        // item 通过 poId (已 factoryId 验证) + lineId 双键查，防跨 PO 行访问
        Optional<PurchaseOrderItem> itemOpt = purchaseOrderItemRepository
                .findByIdAndPurchaseOrderId(lineId, poId);
        if (itemOpt.isEmpty()) {
            return buildInvalid("LINE_NOT_FOUND",
                    String.format("⚠️ PO %s 下不存在行项目 %d", po.getOrderNumber(), lineId));
        }
        PurchaseOrderItem item = itemOpt.get();

        // 2. R1 max 边界
        BigDecimal ordered = nonNull(item.getQuantity());
        BigDecimal alreadyReceived = nonNull(item.getReceivedQuantity());
        BigDecimal pending = ordered.subtract(alreadyReceived);
        BigDecimal overReceiveLimit = ordered.multiply(OVER_RECEIVE_PCT);
        BigDecimal remainingCap = pending.add(overReceiveLimit);

        // 3. R4 idempotent precheck
        DuplicateCheckResult dup = checkDuplicate(factoryId, poId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("poId", po.getId());
        result.put("orderNumber", po.getOrderNumber());
        result.put("supplierName", po.getSupplierName());
        result.put("lineId", item.getId());
        result.put("materialName", item.getMaterialName());
        result.put("specification", item.getSpecification());
        result.put("unit", item.getUnit());
        result.put("ordered", ordered);
        result.put("alreadyReceived", alreadyReceived);
        result.put("pending", pending);
        result.put("overReceiveLimit", overReceiveLimit);
        result.put("remainingCap", remainingCap);
        result.put("yourProposedQty", proposed);
        result.put("receiveStatus", receiveStatus);

        // 4. 校验顺序: 数量 → 状态 → 幂等
        if (proposed.compareTo(BigDecimal.ZERO) <= 0) {
            result.put("status", "PREVIEW_INVALID");
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ 实收数量必须 > 0, 当前: %s", stripZeros(proposed)));
            return result;
        }
        if (pending.compareTo(BigDecimal.ZERO) <= 0) {
            result.put("status", "ALREADY_COMPLETED");
            result.put("canDo", false);
            result.put("message", String.format(
                    "✅ 行项目 %s 已全部入库 (订 %s, 已收 %s), 无需再收",
                    item.getMaterialName(), stripZeros(ordered), stripZeros(alreadyReceived)));
            return result;
        }
        if (proposed.compareTo(remainingCap) > 0) {
            result.put("status", "OVER_LIMIT");
            result.put("canDo", false);
            result.put("warningIfOver", "超 30% 超收上限触发审批");
            result.put("message", String.format(
                    "⛔ 实收 %s%s 超过 30%% 超收上限 (最多 %s%s = 剩余 %s + 超收上限 %s). "
                            + "请减少实收数量或联系采购员调整 PO.",
                    stripZeros(proposed), nullToEmpty(item.getUnit()),
                    stripZeros(remainingCap), nullToEmpty(item.getUnit()),
                    stripZeros(pending), stripZeros(overReceiveLimit)));
            return result;
        }
        if (dup.duplicate) {
            result.put("status", "DUPLICATE");
            result.put("canDo", false);
            result.put("existingId", dup.existingId);
            result.put("existingReceiveNumber", dup.existingReceiveNumber);
            result.put("actionHint", String.format(
                    "/purchase/receives/%s", dup.existingId));
            result.put("message", String.format(
                    "🟡 %d 分钟内已为 PO %s 创建入库单 %s (ID=%s). 重复确认会再开一单 — "
                            + "请前往详情确认或等待 %d 分钟后再试.",
                    DEDUP_WINDOW_MINUTES, po.getOrderNumber(),
                    dup.existingReceiveNumber, dup.existingId, DEDUP_WINDOW_MINUTES));
            return result;
        }

        // 5. canDo
        result.put("status", "PREVIEW");
        result.put("canDo", true);
        String overReceivedHint = proposed.compareTo(pending) > 0
                ? String.format(" (超收 %s%s, 在 30%% 范围内)",
                        stripZeros(proposed.subtract(pending)), nullToEmpty(item.getUnit()))
                : "";
        result.put("message", String.format(
                "✅ 收货 %s — %s %s%s%s. 收货状态: %s. 确认提交后立即更新库存 + 自动挂应付.",
                po.getOrderNumber(), item.getMaterialName(),
                stripZeros(proposed), nullToEmpty(item.getUnit()), overReceivedHint,
                receiveStatus));
        return result;
    }

    // ==================== Execute ====================

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String poId = getString(params, "poId");
        Long lineId = getLong(params, "lineId");
        BigDecimal proposed = getBigDecimal(params, "receivedQty");
        String receiveStatus = getString(params, "receiveStatus", "PASS");
        Boolean signatureConfirmed = getBoolean(params, "signatureConfirmed", Boolean.FALSE);
        String remark = getString(params, "remark", "");
        Boolean testRun = getBoolean(params, "testRun", Boolean.FALSE);
        validateBasic(poId, lineId, proposed);
        validateReceiveStatus(receiveStatus);
        Long userId = getUserId(context);

        // 1. 重做 R1 校验 (防止 client 绕 preview 直接 execute)（使用 factory-scoped finder）
        Optional<PurchaseOrder> poOpt = purchaseOrderRepository.findByIdAndFactoryId(poId, factoryId);
        if (poOpt.isEmpty()) {
            throw new IllegalArgumentException(
                    String.format("采购单 %s 不存在或非本工厂", poId));
        }
        PurchaseOrder po = poOpt.get();

        // item 通过 poId (已 factoryId 验证) + lineId 双键查，防跨 PO 行访问
        Optional<PurchaseOrderItem> itemOpt = purchaseOrderItemRepository
                .findByIdAndPurchaseOrderId(lineId, poId);
        if (itemOpt.isEmpty()) {
            throw new IllegalArgumentException(
                    String.format("PO %s 下不存在行项目 %d", po.getOrderNumber(), lineId));
        }
        PurchaseOrderItem item = itemOpt.get();

        BigDecimal ordered = nonNull(item.getQuantity());
        BigDecimal alreadyReceived = nonNull(item.getReceivedQuantity());
        BigDecimal pending = ordered.subtract(alreadyReceived);
        BigDecimal remainingCap = pending.add(ordered.multiply(OVER_RECEIVE_PCT));
        if (proposed.compareTo(remainingCap) > 0) {
            throw new IllegalArgumentException(String.format(
                    "实收 %s%s 超过 30%% 超收上限 %s%s, 拒绝入库",
                    stripZeros(proposed), nullToEmpty(item.getUnit()),
                    stripZeros(remainingCap), nullToEmpty(item.getUnit())));
        }
        if (proposed.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("实收数量必须 > 0");
        }

        // 2. R4 idempotent — 5min 内同 PO 已创建 → 返 existingId 不重复创建
        DuplicateCheckResult dup = checkDuplicate(factoryId, poId);
        if (dup.duplicate) {
            log.warn("receive_confirm_create — duplicate within {}min: poId={} existingId={}",
                    DEDUP_WINDOW_MINUTES, poId, dup.existingId);
            Map<String, Object> dupData = new LinkedHashMap<>();
            dupData.put("status", "DUPLICATE");
            dupData.put("existingId", dup.existingId);
            dupData.put("existingReceiveNumber", dup.existingReceiveNumber);
            dupData.put("actionHint", String.format("/purchase/receives/%s", dup.existingId));
            dupData.put("count", 0);
            String message = String.format(
                    "🟡 %d 分钟内已为 PO %s 创建入库单 %s (ID=%s). 跳过重复创建 — "
                            + "请前往详情查看或等待 %d 分钟后再试.",
                    DEDUP_WINDOW_MINUTES, po.getOrderNumber(),
                    dup.existingReceiveNumber, dup.existingId, DEDUP_WINDOW_MINUTES);
            return buildSimpleResult(message, dupData);
        }

        log.info("receive_confirm_create — factory={} po={} line={} qty={} status={} sig={} testRun={} user={}",
                factoryId, po.getOrderNumber(), lineId, proposed, receiveStatus,
                signatureConfirmed, testRun, userId);

        // 3. createReceiveRecord (DRAFT)
        CreateReceiveRecordRequest req = new CreateReceiveRecordRequest();
        req.setPurchaseOrderId(poId);
        req.setSupplierId(po.getSupplierId());
        req.setReceiveDate(LocalDate.now());
        String fullRemark = composeRemark(receiveStatus, signatureConfirmed, remark);
        req.setRemark(fullRemark);

        CreateReceiveRecordRequest.ReceiveItemDTO line =
                new CreateReceiveRecordRequest.ReceiveItemDTO();
        line.setMaterialTypeId(item.getMaterialTypeId());
        line.setMaterialName(item.getMaterialName());
        line.setReceivedQuantity(proposed);
        line.setUnit(item.getUnit());
        line.setUnitPrice(item.getUnitPrice());
        line.setQcResult(receiveStatus);  // R3 status → qcResult
        line.setRemark(remark);
        req.setItems(List.of(line));

        PurchaseReceiveRecord record = purchaseService.createReceiveRecord(
                factoryId, req, userId);

        // 4. 写入 aiInvocationMetadata + save (做在 confirm 之前, 防止 confirm 失败时 cleanup 漏 tag)
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "sprint-10-loop-2");
        metadata.put("testRun", Boolean.TRUE.equals(testRun));
        metadata.put("createdAt", Instant.now().toString());
        metadata.put("toolName", getToolName());
        metadata.put("receiveStatus", receiveStatus);
        metadata.put("signatureConfirmed", Boolean.TRUE.equals(signatureConfirmed));
        record.setAiInvocationMetadata(metadata);
        record = receiveRecordRepository.save(record);

        // 5. 立即 confirmReceive (DRAFT → CONFIRMED, 创建 MaterialBatch, 库存+, 自动挂应付)
        PurchaseReceiveRecord confirmed = purchaseService.confirmReceive(
                factoryId, record.getId(), userId);

        // 6. 重算 remaining (alreadyReceived 已被 updateOrderReceiveStatus 增加)
        BigDecimal newPending = pending.subtract(proposed).max(BigDecimal.ZERO);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("receiveId", confirmed.getId());
        data.put("receiveNumber", confirmed.getReceiveNumber());
        data.put("poId", po.getId());
        data.put("orderNumber", po.getOrderNumber());
        data.put("materialName", item.getMaterialName());
        data.put("receivedQty", proposed);
        data.put("unit", item.getUnit());
        data.put("receiveStatus", receiveStatus);
        data.put("signatureConfirmed", Boolean.TRUE.equals(signatureConfirmed));
        data.put("status", confirmed.getStatus().name());
        data.put("remainingPending", newPending);
        data.put("nextActionHint",
                String.format("/purchase/receives/%s", confirmed.getId()));

        String message = String.format(
                "✅ 已入库 %s%s (PO %s — %s). 差 %s%s 待收 — 前往详情: /purchase/receives/%s",
                stripZeros(proposed), nullToEmpty(item.getUnit()),
                po.getOrderNumber(), item.getMaterialName(),
                stripZeros(newPending), nullToEmpty(item.getUnit()),
                confirmed.getId());
        return buildSimpleResult(message, data);
    }

    // ==================== Helpers ====================

    /**
     * R4 dedup helper — 查 5min 窗口内同 factory+poId 是否已有 DRAFT/PENDING_QC/CONFIRMED 记录.
     * CONFIRMED 也算 duplicate, 避免 1 PO 多次重复"收货"创建多个入库单.
     *
     * <p>使用 factory-scoped finder {@code findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc}
     * 而非跨工厂的 {@code findByPurchaseOrderId}，确保幂等检查本身也严格隔离租户。
     */
    private DuplicateCheckResult checkDuplicate(String factoryId, String poId) {
        LocalDateTime cutoff = LocalDateTime.now(ZoneId.systemDefault())
                .minusMinutes(DEDUP_WINDOW_MINUTES);
        // factory-scoped: 只查本工厂下该 PO 的收货记录，不跨租户
        List<PurchaseReceiveRecord> existing =
                receiveRecordRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(
                        factoryId, poId);
        for (PurchaseReceiveRecord r : existing) {
            if (r.getCreatedAt() == null) continue;
            // Only consider live, non-rejected records as dedup signals.
            PurchaseReceiveStatus s = r.getStatus();
            if (s != PurchaseReceiveStatus.DRAFT
                    && s != PurchaseReceiveStatus.PENDING_QC
                    && s != PurchaseReceiveStatus.CONFIRMED) {
                continue;
            }
            if (r.getCreatedAt().isAfter(cutoff)) {
                DuplicateCheckResult result = new DuplicateCheckResult();
                result.duplicate = true;
                result.existingId = r.getId();
                result.existingReceiveNumber = r.getReceiveNumber();
                return result;
            }
        }
        DuplicateCheckResult empty = new DuplicateCheckResult();
        empty.duplicate = false;
        return empty;
    }

    private static void validateBasic(String poId, Long lineId, BigDecimal qty) {
        if (poId == null || poId.isBlank()) {
            throw new IllegalArgumentException("poId 必填");
        }
        if (lineId == null) {
            throw new IllegalArgumentException("lineId 必填");
        }
        if (qty == null) {
            throw new IllegalArgumentException("receivedQty 必填");
        }
    }

    private static void validateReceiveStatus(String status) {
        if (status == null || status.isBlank()) {
            return;  // default to PASS via getString default
        }
        if (!ALLOWED_RECEIVE_STATUSES.contains(status)) {
            throw new IllegalArgumentException(String.format(
                    "receiveStatus 必须为 %s 之一, 当前: %s",
                    ALLOWED_RECEIVE_STATUSES, status));
        }
    }

    private static String composeRemark(String receiveStatus, Boolean signatureConfirmed,
                                        String remark) {
        StringBuilder sb = new StringBuilder("AI Workdesk Sprint 10 Loop 2 一键收货");
        if (receiveStatus != null && !receiveStatus.isBlank()) {
            sb.append(" | 状态: ").append(receiveStatus);
        }
        if (Boolean.TRUE.equals(signatureConfirmed)) {
            sb.append(" | 已验签");
        }
        if (remark != null && !remark.isBlank()) {
            sb.append(" | ").append(remark);
        }
        return sb.toString();
    }

    private static Map<String, Object> buildInvalid(String status, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("canDo", false);
        r.put("message", message);
        return r;
    }

    private static BigDecimal nonNull(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static String stripZeros(BigDecimal v) {
        if (v == null) return "0";
        try {
            return v.stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return v.toPlainString();
        }
    }

    /** Internal struct for dedup precheck result. */
    private static class DuplicateCheckResult {
        boolean duplicate;
        String existingId;
        String existingReceiveNumber;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
