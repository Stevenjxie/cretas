package com.cretas.aims.ai.tool.impl.sales;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.domain.OrderUsageWhitelists;
import com.cretas.aims.dto.inventory.CreateDeliveryRequest;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.SalesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Sprint 10 Loop 1 — 发货/出库 AI 闭环 Tool.
 *
 * 触发场景:
 *   - "今日 SO 待发" / "今天发什么" (Path A keyword)
 *   - "今天该出什么货" / "需要发哪些订单" (Path B LLM-routed synonym)
 *
 * 两种调用:
 *   1. 无 salesOrderId + 无 items[] → Query 模式: 返回今日待发 SO 列表
 *   2. 有 salesOrderId + items[] → Confirm 模式: 创 DLV (idempotent + R1 max + R2 context + R4 dedup)
 *
 * 防呆 (per .claude/rules/fool-proof-design.md):
 *   - R1 max: 每行 actualQty 不可超 SalesOrderItem.pendingQuantity (后端拒)
 *   - R2 context: 返回 customerName + orderNumber + 品名 (UI 显)
 *   - R4 idempotent: 复用 SalesServiceImpl.createDeliveryRecord 已有 5min in-progress draft 409
 *
 * aiInvocationMetadata 写: {source: "sprint-10-loop-1", testRun: bool, createdAt: iso}
 * 用于 cleanup-sprint-10-test-data.sh 按 source tag soft-delete.
 *
 * @author Cretas Team
 * @since 2026-05-21
 */
@Slf4j
@Component
public class ShipmentConfirmCreateTool extends AbstractBusinessTool {

    @Autowired
    private SalesService salesService;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesDeliveryRecordRepository deliveryRecordRepository;

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override
    public String getToolName() {
        return "shipment_confirm_create";
    }

    @Override
    public String getDescription() {
        return "Sprint 10 Loop 1 — AI 闭环发货. 无 salesOrderId 时 列出今日 SO 待发 (status 可发货 + " +
               "requiredDeliveryDate<=today + 未完全发出). 有 salesOrderId + items[] 时 创建发货单 " +
               "(R1 max + R2 context + R4 5min idempotent). 触发: '今日 SO 待发' / '今天发什么' / " +
               "'今天该出什么货' / '需要发哪些订单'.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> salesOrderId = new HashMap<>();
        salesOrderId.put("type", "string");
        salesOrderId.put("description", "销售订单 ID. 不传 → 返回今日待发 SO 列表; 传 → 进入 confirm 模式.");
        properties.put("salesOrderId", salesOrderId);

        Map<String, Object> items = new HashMap<>();
        items.put("type", "array");
        items.put("description", "发货行 [{salesOrderItemId, actualQty}]. confirm 模式必填.");
        properties.put("items", items);

        Map<String, Object> deliveryDate = new HashMap<>();
        deliveryDate.put("type", "string");
        deliveryDate.put("description", "发货日期 yyyy-MM-dd, 默认今天.");
        properties.put("deliveryDate", deliveryDate);

        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "备注 (可选).");
        properties.put("remark", remark);

        Map<String, Object> testRun = new HashMap<>();
        testRun.put("type", "boolean");
        testRun.put("description", "Playwright E2E 标记. true → ai_invocation_metadata.testRun=true 用于 cleanup.");
        properties.put("testRun", testRun);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // No hard-required — query mode (无 salesOrderId) 也合法.
        // confirm mode 校验在 doExecute 里手工做, 因为 conditional required.
        return Collections.emptyList();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String salesOrderId = getString(params, "salesOrderId");
        List<Map<String, Object>> rawItems = getList(params, "items");

        // Query 模式: 无 salesOrderId → 列今日待发
        if (salesOrderId == null || salesOrderId.isBlank()) {
            return queryTodayPendingShipments(factoryId);
        }

        // Confirm 模式
        if (rawItems == null || rawItems.isEmpty()) {
            throw new IllegalArgumentException("confirm 模式 items[] 必填");
        }
        Boolean testRun = getBoolean(params, "testRun", false);
        String dateStr = getString(params, "deliveryDate");
        LocalDate deliveryDate = (dateStr != null && !dateStr.isBlank())
                ? LocalDate.parse(dateStr, ISO_DATE)
                : LocalDate.now();
        String remark = getString(params, "remark");
        Long userId = context.get("userId") != null
                ? ((Number) context.get("userId")).longValue() : 0L;

        return confirmShipment(factoryId, salesOrderId, rawItems, deliveryDate, remark, userId, testRun);
    }

    /** Query 模式 — 列今日待发 SO. */
    private Map<String, Object> queryTodayPendingShipments(String factoryId) {
        LocalDate today = LocalDate.now();
        // 查所有可发货状态 (SO_DELIVERABLE) + requiredDeliveryDate <= 今天 + 还有未发量
        List<SalesOrder> candidates = salesOrderRepository.findAll().stream()
                .filter(so -> factoryId.equals(so.getFactoryId()))
                .filter(so -> so.getStatus() != null
                        && OrderUsageWhitelists.SO_DELIVERABLE.contains(so.getStatus()))
                .filter(so -> so.getRequiredDeliveryDate() == null
                        || !so.getRequiredDeliveryDate().isAfter(today))
                .filter(so -> hasPendingItems(so))
                .toList();

        List<Map<String, Object>> orderList = new ArrayList<>();
        for (SalesOrder so : candidates) {
            Map<String, Object> orderInfo = new LinkedHashMap<>();
            orderInfo.put("salesOrderId", so.getId());
            orderInfo.put("orderNumber", so.getOrderNumber());
            orderInfo.put("customerId", so.getCustomerId());
            orderInfo.put("customerName", so.getCustomerName());
            orderInfo.put("requiredDeliveryDate", so.getRequiredDeliveryDate());
            orderInfo.put("status", so.getStatus().name());
            orderInfo.put("statusDisplay", so.getStatus().getDisplayName());

            // 行明细 + pendingQuantity (R1 边界 显)
            List<Map<String, Object>> itemList = new ArrayList<>();
            if (so.getItems() != null) {
                for (SalesOrderItem item : so.getItems()) {
                    BigDecimal pending = item.getPendingQuantity();
                    if (pending != null && pending.compareTo(BigDecimal.ZERO) > 0) {
                        Map<String, Object> itemInfo = new LinkedHashMap<>();
                        itemInfo.put("salesOrderItemId", item.getId());
                        itemInfo.put("productTypeId", item.getProductTypeId());
                        itemInfo.put("productName", item.getProductName());
                        itemInfo.put("unit", item.getUnit());
                        itemInfo.put("orderedQuantity", item.getQuantity());
                        itemInfo.put("deliveredQuantity", item.getDeliveredQuantity());
                        itemInfo.put("pendingQuantity", pending);
                        itemList.add(itemInfo);
                    }
                }
            }
            orderInfo.put("items", itemList);
            orderInfo.put("itemCount", itemList.size());
            orderList.add(orderInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "QUERY");
        result.put("today", today);
        result.put("orders", orderList);
        result.put("orderCount", orderList.size());
        String msg = orderList.isEmpty()
                ? "今日待发: 暂无待发货销售订单"
                : String.format("今日待发: %d 个销售订单, 共 %d 个待发行",
                        orderList.size(),
                        orderList.stream().mapToInt(o -> (Integer) o.get("itemCount")).sum());
        result.put("message", msg);
        return result;
    }

    private boolean hasPendingItems(SalesOrder so) {
        if (so.getItems() == null || so.getItems().isEmpty()) return false;
        return so.getItems().stream().anyMatch(it -> {
            BigDecimal pending = it.getPendingQuantity();
            return pending != null && pending.compareTo(BigDecimal.ZERO) > 0;
        });
    }

    /** Confirm 模式 — 创建发货单. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> confirmShipment(String factoryId, String salesOrderId,
                                                List<Map<String, Object>> rawItems,
                                                LocalDate deliveryDate, String remark,
                                                Long userId, Boolean testRun) {
        // 加载 SO + 校验
        SalesOrder so = salesOrderRepository.findById(salesOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在: " + salesOrderId));
        if (!factoryId.equals(so.getFactoryId())) {
            throw new BusinessException(403, "无权访问该订单");
        }
        if (so.getStatus() == null
                || !OrderUsageWhitelists.SO_DELIVERABLE.contains(so.getStatus())) {
            throw new BusinessException(409,
                    "订单 " + so.getOrderNumber() + " 当前状态 ("
                            + (so.getStatus() != null ? so.getStatus().getDisplayName() : "未知")
                            + ") 不可发货")
                    .withHint("仅 已确认/财务已批准/处理中/部分发货 状态可发货");
        }

        // 建 lookup: salesOrderItemId → SalesOrderItem
        Map<Long, SalesOrderItem> itemMap = new HashMap<>();
        if (so.getItems() != null) {
            for (SalesOrderItem soi : so.getItems()) {
                itemMap.put(soi.getId(), soi);
            }
        }

        // R1 校验 + 构造 CreateDeliveryRequest.items
        CreateDeliveryRequest request = new CreateDeliveryRequest();
        request.setSalesOrderId(salesOrderId);
        request.setCustomerId(so.getCustomerId());
        request.setDeliveryDate(deliveryDate);
        request.setRemark(remark);

        List<CreateDeliveryRequest.DeliveryItemDTO> dtoItems = new ArrayList<>();
        for (Map<String, Object> raw : rawItems) {
            Object idRaw = raw.get("salesOrderItemId");
            if (idRaw == null) {
                throw new IllegalArgumentException("items[].salesOrderItemId 必填");
            }
            Long soItemId;
            try {
                soItemId = Long.parseLong(idRaw.toString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "items[].salesOrderItemId 格式错误: " + idRaw);
            }
            SalesOrderItem soi = itemMap.get(soItemId);
            if (soi == null) {
                throw new IllegalArgumentException(
                        "salesOrderItemId=" + soItemId + " 不属于订单 " + so.getOrderNumber());
            }

            Object qtyRaw = raw.get("actualQty");
            if (qtyRaw == null) {
                throw new IllegalArgumentException("items[].actualQty 必填");
            }
            BigDecimal actualQty;
            try {
                actualQty = new BigDecimal(qtyRaw.toString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("items[].actualQty 数字格式错误: " + qtyRaw);
            }

            if (actualQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        "items[].actualQty 必须 > 0 (row " + soItemId + ")");
            }

            // R1 max: actualQty <= pendingQuantity
            BigDecimal pending = soi.getPendingQuantity();
            if (pending == null) pending = BigDecimal.ZERO;
            if (actualQty.compareTo(pending) > 0) {
                throw new BusinessException(409,
                        String.format("发货量超额: %s — 已订 %s, 已发 %s, 仅可发 %s, 您填 %s",
                                soi.getProductName() != null ? soi.getProductName() : ("行 " + soItemId),
                                soi.getQuantity(),
                                soi.getDeliveredQuantity(),
                                pending,
                                actualQty))
                        .withHint("请改小发货量到剩余可发数以内");
            }

            CreateDeliveryRequest.DeliveryItemDTO dto = new CreateDeliveryRequest.DeliveryItemDTO();
            dto.setProductTypeId(soi.getProductTypeId());
            dto.setProductName(soi.getProductName());
            dto.setDeliveredQuantity(actualQty);
            dto.setUnit(soi.getUnit());
            dto.setUnitPrice(soi.getUnitPrice());
            dto.setSourceWarehouseCode(soi.getSourceWarehouseCode());
            dto.setRemark(null);
            dtoItems.add(dto);
        }
        request.setItems(dtoItems);

        // R4 dedup: SalesServiceImpl.createDeliveryRecord 内已有 PENDING 草稿 → 409.
        // 直接传, 复用既有 idempotent 逻辑.
        SalesDeliveryRecord created;
        try {
            created = salesService.createDeliveryRecord(factoryId, request, userId);
        } catch (BusinessException be) {
            // R4 命中: 返回幂等命中信息, 让 AI reply 引导用户跳已有草稿.
            if (be.getCode() != null && be.getCode() == 409 && be.getMessage() != null
                    && be.getMessage().contains("已有草稿发货单")) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("mode", "CONFIRM");
                result.put("status", "IDEMPOTENT_HIT");
                result.put("message", be.getMessage());
                result.put("actionHint", be.getActionHint() != null
                        ? be.getActionHint() : "请查看现有发货单");
                result.put("count", 0);
                return result;
            }
            throw be;
        }

        // 写 aiInvocationMetadata 标记
        Map<String, Object> aiMeta = new LinkedHashMap<>();
        aiMeta.put("source", "sprint-10-loop-1");
        aiMeta.put("testRun", testRun != null && testRun);
        aiMeta.put("createdAt", java.time.Instant.now().toString());
        created.setAiInvocationMetadata(aiMeta);
        SalesDeliveryRecord saved = deliveryRecordRepository.save(created);

        // 构造 AI reply
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "CONFIRM");
        result.put("status", "CREATED");
        result.put("deliveryId", saved.getId());
        result.put("deliveryNumber", saved.getDeliveryNumber());
        result.put("customerName", so.getCustomerName());
        result.put("orderNumber", so.getOrderNumber());
        result.put("itemCount", saved.getItems() != null ? saved.getItems().size() : 0);
        result.put("totalAmount", saved.getTotalAmount());
        result.put("deliveryDate", saved.getDeliveryDate());
        result.put("actionHint", String.format(
                "已生发货单 %s (客户 %s, 行数 %d) — 前往打印: /sales/orders/%s/delivery/%s",
                saved.getDeliveryNumber(),
                so.getCustomerName() != null ? so.getCustomerName() : "(未命名)",
                saved.getItems() != null ? saved.getItems().size() : 0,
                so.getId(),
                saved.getId()));
        result.put("message", String.format(
                "已生发货单 %s (客户 %s, %d 行)",
                saved.getDeliveryNumber(),
                so.getCustomerName() != null ? so.getCustomerName() : "(未命名)",
                saved.getItems() != null ? saved.getItems().size() : 0));
        result.put("printPath", String.format("/sales/orders/%s/delivery/%s",
                so.getId(), saved.getId()));
        result.put("testRun", testRun != null && testRun);

        log.info("Sprint10 Loop1 发货: factoryId={}, so={}, dlv={}, items={}, testRun={}",
                factoryId, so.getOrderNumber(), saved.getDeliveryNumber(),
                dtoItems.size(), testRun);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> qs = new HashMap<>();
        qs.put("salesOrderId", "请选择要发货的销售订单。");
        qs.put("items", "请选择发货行 + 输入每行实际发货数量。");
        qs.put("deliveryDate", "发货日期 (默认今天)?");
        return qs.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> names = Map.of(
                "salesOrderId", "销售订单",
                "items", "发货行",
                "deliveryDate", "发货日期",
                "remark", "备注"
        );
        return names.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
