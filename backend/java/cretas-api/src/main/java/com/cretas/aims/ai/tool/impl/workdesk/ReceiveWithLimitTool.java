package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.PurchaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 仓管员快速入库 (R1 max display) Tool (Sprint 8 P4 — 仓管员 Workdesk V1).
 *
 * <p><b>这是 P4 仓管员 Workdesk 的灵魂 Tool</b> — 客户 F006 张权原话:
 * "做仓管的他年纪都比较大文化素质很低, 你不能太依赖他们, 最好的方法就是你告诉他这个东西你
 * 要收多少就行了"
 *
 * <p><b>R1 max preview 实现</b>:
 * <ul>
 *   <li>doPreview() 必显: 已订 / 已收 / 还可入 (含 30% 超收上限)</li>
 *   <li>用户输入 receivedQty 超 remainingCap → canDo=false, 提示"超 30% 超收上限触发审批"</li>
 *   <li>未超 → canDo=true, 仓管员确认提交 → 真创建 PurchaseReceiveRecord (DRAFT 状态)</li>
 * </ul>
 *
 * <p>注: 此 Tool 创建 DRAFT receive record, 用户后续仍需 confirmReceive 才正式入库.
 * 防呆 R4 (幂等): Service 层 dedup 5min 窗口 + business key (po_id + line_id + day).
 *
 * <p>Intent Code: {@code RECEIVE_WITH_LIMIT}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4)
 */
@Slf4j
@Component
public class ReceiveWithLimitTool extends AbstractBusinessTool {

    /** 允许超收上限 (30% 行业惯例 — 食品行业散装允许 ±2-5%, 卤味毛料松). */
    private static final BigDecimal OVER_RECEIVE_PCT = new BigDecimal("0.30");

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private PurchaseService purchaseService;

    @Override
    public String getToolName() {
        return "receive_with_limit";
    }

    @Override
    public String getDescription() {
        return "仓管员快速入库 (含 R1 max 边界 preview). LLM 触发场景: 用户说 '收货 X' / "
                + "'入库 PO-Y 50 公斤' / '快速入库' / '入 30 件卤猪蹄' / '签收到货'. "
                + "WRITE + Preview — preview 必显已订 / 已收 / 还可入 (含 30% 超收上限) "
                + "+ 超限 disable 提交. 防呆 R1 灵魂 Tool — 直接告诉仓管员要收多少.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> poId = new HashMap<>();
        poId.put("type", "string");
        poId.put("description", "采购订单 ID (必填)");
        properties.put("poId", poId);

        Map<String, Object> lineId = new HashMap<>();
        lineId.put("type", "integer");
        lineId.put("description", "采购订单行项目 ID (必填)");
        properties.put("lineId", lineId);

        Map<String, Object> receivedQty = new HashMap<>();
        receivedQty.put("type", "number");
        receivedQty.put("description", "本次实收数量 (必填). 系统会校验是否超 30% 超收上限");
        properties.put("receivedQty", receivedQty);

        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "可选 — 入库备注 (e.g. '到货品相好' / '部分包装破损')");
        properties.put("remark", remark);

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

    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String poId = getString(params, "poId");
        Long lineId = getLong(params, "lineId");
        BigDecimal proposed = getBigDecimal(params, "receivedQty");
        validateBasic(poId, lineId, proposed);

        // 1. 取 PO + 行项目（使用 factory-scoped finder — 防跨租户 ID 猜测）
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

        // 2. R1 max 计算
        BigDecimal ordered = nonNull(item.getQuantity());
        BigDecimal alreadyReceived = nonNull(item.getReceivedQuantity());
        BigDecimal pending = ordered.subtract(alreadyReceived);
        BigDecimal overReceiveLimit = ordered.multiply(OVER_RECEIVE_PCT);
        BigDecimal remainingCap = pending.add(overReceiveLimit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("poId", po.getId());
        result.put("orderNumber", po.getOrderNumber());
        result.put("supplierName", po.getSupplierName());
        result.put("lineId", item.getId());
        result.put("materialName", item.getMaterialName());
        result.put("specification", item.getSpecification());
        result.put("unit", item.getUnit());
        // R1 max 边界
        result.put("ordered", ordered);
        result.put("alreadyReceived", alreadyReceived);
        result.put("pending", pending);
        result.put("overReceiveLimit", overReceiveLimit);
        result.put("remainingCap", remainingCap);
        result.put("yourProposedQty", proposed);

        // 3. 校验是否可入
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

        // 4. canDo
        result.put("status", "PREVIEW");
        result.put("canDo", true);
        String overReceivedHint = proposed.compareTo(pending) > 0
                ? String.format(" (超收 %s%s, 在 30%% 范围内)",
                        stripZeros(proposed.subtract(pending)), nullToEmpty(item.getUnit()))
                : "";
        result.put("message", String.format(
                "✅ 入库 %s — %s %s%s%s. 创建后行项目累计 %s%s / %s%s. 确认提交?",
                po.getOrderNumber(), item.getMaterialName(),
                stripZeros(proposed), nullToEmpty(item.getUnit()), overReceivedHint,
                stripZeros(alreadyReceived.add(proposed)), nullToEmpty(item.getUnit()),
                stripZeros(ordered), nullToEmpty(item.getUnit())));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String poId = getString(params, "poId");
        Long lineId = getLong(params, "lineId");
        BigDecimal proposed = getBigDecimal(params, "receivedQty");
        String remark = getString(params, "remark", "");
        validateBasic(poId, lineId, proposed);
        Long userId = getUserId(context);

        // 重做 R1 校验 — 防止 client 绕 preview 直接 execute 超收（使用 factory-scoped finder）
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

        log.info("receive_with_limit — factory={} po={} line={} qty={} user={}",
                factoryId, po.getOrderNumber(), lineId, proposed, userId);

        // 创建 DRAFT receive record
        CreateReceiveRecordRequest req = new CreateReceiveRecordRequest();
        req.setPurchaseOrderId(poId);
        req.setSupplierId(po.getSupplierId());
        req.setReceiveDate(LocalDate.now());
        req.setRemark(remark != null && !remark.isBlank()
                ? remark : "AI Workdesk 快速入库");
        CreateReceiveRecordRequest.ReceiveItemDTO line =
                new CreateReceiveRecordRequest.ReceiveItemDTO();
        line.setMaterialTypeId(item.getMaterialTypeId());
        line.setMaterialName(item.getMaterialName());
        line.setReceivedQuantity(proposed);
        line.setUnit(item.getUnit());
        line.setUnitPrice(item.getUnitPrice());
        req.setItems(List.of(line));

        PurchaseReceiveRecord record = purchaseService.createReceiveRecord(
                factoryId, req, userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("receiveId", record.getId());
        data.put("poId", po.getId());
        data.put("orderNumber", po.getOrderNumber());
        data.put("materialName", item.getMaterialName());
        data.put("receivedQty", proposed);
        data.put("unit", item.getUnit());
        data.put("nextActionHint",
                "草稿入库单已创建. 仓管员可前往采购入库列表 confirm 收货, "
                + "或直接调用 purchase_receive_confirm Tool 提交.");

        String message = String.format(
                "📦 入库 %s — %s %s%s 已创建草稿单 (ID=%s). 待 confirm 后正式更新库存.",
                po.getOrderNumber(), item.getMaterialName(),
                stripZeros(proposed), nullToEmpty(item.getUnit()), record.getId());
        return buildSimpleResult(message, data);
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
}
