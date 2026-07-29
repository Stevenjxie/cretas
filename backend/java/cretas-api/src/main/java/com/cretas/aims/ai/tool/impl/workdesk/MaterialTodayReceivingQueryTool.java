package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仓管员今日待收清单 Tool (Sprint 8 P4 — 仓管员 Workdesk V1).
 *
 * <p>F006 真场景: 仓管员张师傅早上 8 am 打开 Cretas, 默认问 "今天要收什么货?"
 * 系统聚合今日 + 明日 expected delivery date 的 PO + Requisition pending receiving,
 * 每行带 R1 max 边界 (已订 - 已收 = 剩余 + 30% 超收上限) 直接告诉仓管员 "你要收多少".
 *
 * <p>vs HJ 模式: 仓管员点 5 个菜单 (采购订单 / 请购单 / 采购入库 / 到货登记 / 扫码).
 * Cretas Workdesk: 1 屏 30 秒 + 一键扫码任务.
 *
 * <p>防呆原则 (R1): 不让仓管员算总账, 系统直接告诉 "{品名} 还要收 {pendingQty}".
 * 文化素质低的仓管员不需要心算, 看数字直接干活.
 *
 * <p>Intent Code: {@code MATERIAL_TODAY_RECEIVING_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4)
 */
@Slf4j
@Component
public class MaterialTodayReceivingQueryTool extends AbstractBusinessTool {

    private static final int DEFAULT_DAYS_AHEAD = 1; // today + tomorrow
    private static final int MAX_DAYS_AHEAD = 7;
    private static final BigDecimal OVER_RECEIVE_PCT = new BigDecimal("0.30"); // 30% 超收

    /** PO 状态认为 "可入库 / 在途": 通过审核但未全部到货. */
    private static final Set<PurchaseOrderStatus> RECEIVABLE_STATUSES = Set.of(
            PurchaseOrderStatus.FINANCE_APPROVED,
            PurchaseOrderStatus.APPROVED,
            PurchaseOrderStatus.PARTIAL_RECEIVED
    );

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Override
    public String getToolName() {
        return "material_today_receiving_query";
    }

    @Override
    public String getDescription() {
        return "查询今日 (+ 明日) 预期到货的采购订单待收清单, 按品名展开行项目, 每行带"
                + "R1 max 边界 (已订 / 已收 / 还可入 含 30% 超收). LLM 触发场景: 仓管员问 "
                + "'今天要收什么货?' / '今日到货' / '明天到什么货' / '待收货清单' / "
                + "'今天有几个供应商来送货'. read-only. 防呆原则: 直接告诉仓管员要收多少, "
                + "不让他算账.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> daysAhead = new HashMap<>();
        daysAhead.put("type", "integer");
        daysAhead.put("description",
                "查询未来多少天预期到货 (0=仅今天, 1=今天+明天, 默认 1, 上限 7)");
        daysAhead.put("default", DEFAULT_DAYS_AHEAD);
        daysAhead.put("minimum", 0);
        daysAhead.put("maximum", MAX_DAYS_AHEAD);
        properties.put("daysAhead", daysAhead);

        Map<String, Object> includeOverdue = new HashMap<>();
        includeOverdue.put("type", "boolean");
        includeOverdue.put("description",
                "是否包含已过期未到 (expectedDeliveryDate < today 且 status PARTIAL/APPROVED). 默认 true");
        includeOverdue.put("default", true);
        properties.put("includeOverdue", includeOverdue);

        schema.put("properties", properties);
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
        int daysAhead = clamp(getInteger(params, "daysAhead", DEFAULT_DAYS_AHEAD), 0, MAX_DAYS_AHEAD);
        boolean includeOverdue = Boolean.TRUE.equals(getBoolean(params, "includeOverdue", true));

        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(daysAhead);

        log.info("material_today_receiving_query — factory={} today={} end={} includeOverdue={}",
                factoryId, today, end, includeOverdue);

        // 拉 factory 全部 PO (Page first, 后续过滤). 仓管员单 factory 待收 PO 量小.
        List<PurchaseOrder> allPOs = new ArrayList<>();
        var page = purchaseOrderRepository.findByFactoryIdOrderByCreatedAtDesc(
                factoryId, org.springframework.data.domain.PageRequest.of(0, 200));
        allPOs.addAll(page.getContent());

        List<Map<String, Object>> receivingRows = new ArrayList<>();
        int totalLines = 0;
        int totalPOs = 0;

        for (PurchaseOrder po : allPOs) {
            if (po.getStatus() == null) continue;
            if (!RECEIVABLE_STATUSES.contains(po.getStatus())) continue;

            LocalDate expected = po.getExpectedDeliveryDate();
            boolean inWindow = expected != null
                    && !expected.isBefore(today) && !expected.isAfter(end);
            boolean isOverdue = expected != null && expected.isBefore(today);

            if (!inWindow && !(includeOverdue && isOverdue)) continue;

            List<PurchaseOrderItem> items = purchaseOrderItemRepository
                    .findByPurchaseOrderId(po.getId());
            boolean poHasPending = false;
            for (PurchaseOrderItem item : items) {
                BigDecimal ordered = nonNull(item.getQuantity());
                BigDecimal received = nonNull(item.getReceivedQuantity());
                BigDecimal pending = ordered.subtract(received);
                if (pending.compareTo(BigDecimal.ZERO) <= 0) continue;

                // R1 max: pendingQty + 30% 超收 (基于 ordered 总量)
                BigDecimal overReceiveLimit = ordered.multiply(OVER_RECEIVE_PCT);
                BigDecimal remainingCap = pending.add(overReceiveLimit);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("poId", po.getId());
                row.put("orderNumber", po.getOrderNumber());
                row.put("supplierName", po.getSupplierName());
                row.put("expectedDeliveryDate",
                        expected != null ? expected.toString() : null);
                row.put("isOverdue", isOverdue);
                row.put("lineId", item.getId());
                row.put("materialTypeId", item.getMaterialTypeId());
                row.put("materialName", item.getMaterialName());
                row.put("specification", item.getSpecification());
                row.put("orderedQuantity", ordered);
                row.put("receivedQuantity", received);
                row.put("pendingQuantity", pending);
                row.put("remainingCap", remainingCap);    // R1 max with 30% over-receive
                row.put("overReceiveLimit", overReceiveLimit);
                row.put("unit", item.getUnit());
                // 防呆 R1 提示文案: 直接告诉仓管员要收多少
                row.put("displayHint", String.format(
                        "已订 %s%s, 已收 %s%s, 还要收 %s%s (含 30%% 超收上限 = %s%s)",
                        stripZeros(ordered), nullToEmpty(item.getUnit()),
                        stripZeros(received), nullToEmpty(item.getUnit()),
                        stripZeros(pending), nullToEmpty(item.getUnit()),
                        stripZeros(remainingCap), nullToEmpty(item.getUnit())));
                receivingRows.add(row);
                totalLines++;
                poHasPending = true;
            }
            if (poHasPending) totalPOs++;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("today", today.toString());
        data.put("rangeEnd", end.toString());
        data.put("daysAhead", daysAhead);
        data.put("includeOverdue", includeOverdue);
        data.put("totalPOs", totalPOs);
        data.put("totalLines", totalLines);
        data.put("rows", receivingRows);

        String message = receivingRows.isEmpty()
                ? String.format("今天 (~%d 天内) 暂无待收货, 仓库可专注盘点 / 整理库位",
                        daysAhead)
                : String.format("今天 (~%d 天内) %d 个采购单共 %d 行待入库, 请按清单核对实物",
                        daysAhead, totalPOs, totalLines);
        return buildSimpleResult(message, data);
    }

    private static BigDecimal nonNull(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    /** 去掉末尾 .00 让仓管员看着舒服 (100.00 → 100, 100.50 → 100.5). */
    private static String stripZeros(BigDecimal v) {
        if (v == null) return "0";
        try {
            return v.stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return v.toPlainString();
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
