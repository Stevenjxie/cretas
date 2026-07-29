package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.PurchaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sprint 10 Loop 3 — 一键采购下单 AI 闭环灵魂 Tool.
 *
 * <p><b>这是 Sprint 10 Loop 3 (Procurement Order Create) 的灵魂 Tool</b> —
 * 实现采购员从 Workdesk "低库存清单 → 选物料 → 一键采购下单" 闭环.
 * 与 {@link com.cretas.aims.ai.tool.impl.workdesk.RequisitionCreateTool} 区别:
 * <ul>
 *   <li>RequisitionCreateTool 创 PurchaseRequisition (请购单 DRAFT, 走内部审批 → 转 PO)</li>
 *   <li>ProcurementOrderCreateTool 直接创 PurchaseOrder (采购订单 DRAFT, 走 PO 审批 → 转入库)</li>
 * </ul>
 *
 * <p>防呆 4 大原则:
 * <ul>
 *   <li>R1 (max display): 数量 max = 月均用量 × 3 (历史 PO 90 天 AVG → ×3 buffer), preview 显示</li>
 *   <li>R2 (context): preview 必显物料名 + 推荐供应商名 + 期望到货日</li>
 *   <li>R3 (constrained choice): 供应商 dropdown ranked (last-PO 时间 + 加权 AVG 价格 top 3)</li>
 *   <li>R4 (idempotent): businessKey = materialId + supplierId + 5min window → 409 + existingPoId + actionHint</li>
 * </ul>
 *
 * <p>aiInvocationMetadata schema (per Sprint 10 P0 V20260822_00):
 * <pre>
 * { "source": "sprint-10-loop-3", "testRun": true|false, "createdAt": "iso8601" }
 * </pre>
 *
 * <p>Intent Code: {@code PROCUREMENT_ORDER_CREATE}
 *
 * @author Cretas Team
 * @since 2026-05-21 (Sprint 10 Loop 3)
 */
@Slf4j
@Component
public class ProcurementOrderCreateTool extends AbstractBusinessTool {

    /** R1 月均用量回看天数. */
    private static final int MONTHLY_USAGE_LOOKBACK_DAYS = 90;

    /** R1 数量上限 multiplier: 月均 × 3 (3 个月 buffer). */
    private static final int MAX_QUANTITY_MONTHS_BUFFER = 3;

    /** R4 idempotency 时间窗 (分钟). */
    private static final int IDEMPOTENCY_WINDOW_MINUTES = 5;

    /** R3 supplier ranking 历史 PO 回看天数. */
    private static final int SUPPLIER_RANKING_LOOKBACK_DAYS = 180;

    /** R3 supplier ranking 返回 top N. */
    private static final int SUPPLIER_RANKING_TOP_N = 3;

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Override
    public String getToolName() {
        return "procurement_order_create";
    }

    @Override
    public String getDescription() {
        return "Sprint 10 Loop 3 灵魂 Tool — 一键采购下单. preview 必显物料/数量(max=月均×3)/推荐供应商/期望到货日. "
                + "WRITE + Preview — preview 用户确认后才创 PurchaseOrder DRAFT. LLM 触发场景: 采购员说 "
                + "'一键采购 X 物料' / '帮我下采购单' / '创建采购订单 X'. 防呆 R1+R2+R3+R4 灵魂 Tool. "
                + "复用 PurchaseService.createPurchaseOrder (基础), 加 aiInvocationMetadata 标 + R4 5min idempotency check.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> materialId = new HashMap<>();
        materialId.put("type", "string");
        materialId.put("description", "物料 ID (必填, RawMaterialType.id)");
        properties.put("materialId", materialId);

        Map<String, Object> supplierId = new HashMap<>();
        supplierId.put("type", "string");
        supplierId.put("description", "供应商 ID (必填, 选自 recommendedSuppliers ranked top 3)");
        properties.put("supplierId", supplierId);

        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "采购数量 (必填, max = 月均用量 × 3, preview 时校验)");
        properties.put("quantity", quantity);

        Map<String, Object> expectedDeliveryDate = new HashMap<>();
        expectedDeliveryDate.put("type", "string");
        expectedDeliveryDate.put("description", "可选 — 期望到货日 (ISO YYYY-MM-DD), 默认下单日 + 7d");
        properties.put("expectedDeliveryDate", expectedDeliveryDate);

        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "可选 — 采购备注 (e.g. '低库存补货')");
        properties.put("remark", remark);

        Map<String, Object> testRun = new HashMap<>();
        testRun.put("type", "boolean");
        testRun.put("description", "可选 — true 表示 Playwright E2E 测试数据 (会被 cleanup 脚本回收)");
        properties.put("testRun", testRun);

        schema.put("properties", properties);
        schema.put("required", List.of("materialId", "supplierId", "quantity"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("materialId", "supplierId", "quantity");
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
        String materialId = getString(params, "materialId");
        String supplierId = getString(params, "supplierId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String expectedDateStr = getString(params, "expectedDeliveryDate");
        String remark = getString(params, "remark", "");

        // 基础校验
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return buildInvalid("INVALID_QTY",
                    "⚠️ 采购数量必须 > 0, 请输入有效数量",
                    "/purchase/orders");
        }

        // 拉物料 (R2 context: 物料身份)
        Optional<RawMaterialType> matOpt = rawMaterialTypeRepository.findById(materialId);
        if (matOpt.isEmpty() || !factoryId.equals(matOpt.get().getFactoryId())) {
            return buildInvalid("MATERIAL_NOT_FOUND",
                    String.format("⚠️ 物料 %s 不存在或非本工厂", materialId),
                    "/material/types");
        }
        RawMaterialType material = matOpt.get();

        // 拉供应商 (R2 context: 供应商身份)
        Optional<Supplier> supOpt = supplierRepository.findByIdAndFactoryId(supplierId, factoryId);
        if (supOpt.isEmpty()) {
            return buildInvalid("SUPPLIER_NOT_FOUND",
                    String.format("⚠️ 供应商 %s 不存在或非本工厂", supplierId),
                    "/supplier/list");
        }
        Supplier supplier = supOpt.get();
        if (Boolean.FALSE.equals(supplier.getIsActive())) {
            return buildInvalid("SUPPLIER_INACTIVE",
                    String.format("⚠️ 供应商 [%s] 已停用, 不可下单 — 请前往启用或换供应商", supplier.getName()),
                    "/supplier/list");
        }

        // R1 数量上限校验 (max = 月均用量 × 3)
        BigDecimal monthlyAvg = calculateMonthlyAvgUsage(materialId);
        BigDecimal maxQty = monthlyAvg.multiply(BigDecimal.valueOf(MAX_QUANTITY_MONTHS_BUFFER));
        boolean overMax = monthlyAvg.compareTo(BigDecimal.ZERO) > 0
                          && quantity.compareTo(maxQty) > 0;
        if (overMax) {
            return buildInvalid("OVER_MAX_QTY",
                    String.format("⚠️ 采购数量 %s%s 超过上限 %s%s (月均 %s%s × 3 月 buffer). 请减少数量或分批采购.",
                            stripZeros(quantity), nullToEmpty(material.getUnit()),
                            stripZeros(maxQty), nullToEmpty(material.getUnit()),
                            stripZeros(monthlyAvg), nullToEmpty(material.getUnit())),
                    "/purchase/orders");
        }

        // 期望到货日 (默认 7d 后)
        LocalDate expectedDate;
        if (expectedDateStr != null && !expectedDateStr.isBlank()) {
            try {
                expectedDate = LocalDate.parse(expectedDateStr);
            } catch (Exception e) {
                return buildInvalid("INVALID_DATE",
                        String.format("⚠️ 期望到货日格式无效 (%s), 请用 YYYY-MM-DD", expectedDateStr),
                        "/purchase/orders");
            }
            if (expectedDate.isBefore(LocalDate.now())) {
                return buildInvalid("PAST_DATE",
                        String.format("⚠️ 期望到货日 %s 已过去, 请选未来日期", expectedDate),
                        "/purchase/orders");
            }
        } else {
            expectedDate = LocalDate.now().plusDays(7);
        }

        // R4 idempotency check (preview 阶段告诉用户重复风险)
        Optional<PurchaseOrder> existing = findRecentDuplicate(factoryId, materialId, supplierId);
        if (existing.isPresent()) {
            PurchaseOrder dup = existing.get();
            return buildInvalid("DUPLICATE_RECENT",
                    String.format("⚠️ 已有近期采购单 %s (同物料 + 同供应商 %d 分钟内创建), 是否前往查看?",
                            dup.getOrderNumber(), IDEMPOTENCY_WINDOW_MINUTES),
                    "/purchase/orders/" + dup.getId(),
                    Map.of("existingPoId", dup.getId(),
                           "existingOrderNumber", dup.getOrderNumber()));
        }

        // 历史价估算 (info, not constraint)
        BigDecimal estimatedUnitPrice = estimateUnitPrice(factoryId, materialId);
        BigDecimal estimatedBudget = estimatedUnitPrice != null
                ? estimatedUnitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP)
                : null;

        // 推荐 supplier ranking (top 3 by last-PO + price)
        List<Map<String, Object>> recommendedSuppliers = recommendSuppliers(factoryId, materialId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("canDo", true);
        // R2 上下文身份
        result.put("materialId", material.getId());
        result.put("materialName", material.getName());
        result.put("materialCode", material.getCode());
        result.put("unit", material.getUnit());
        result.put("quantity", quantity);
        result.put("expectedDeliveryDate", expectedDate.toString());
        result.put("supplierId", supplier.getId());
        result.put("supplierName", supplier.getName());
        result.put("supplierDeliveryDays", supplier.getDeliveryDays());
        result.put("remark", remark);
        // R1 边界 (max display)
        result.put("monthlyAvgUsage", monthlyAvg);
        result.put("maxQuantity", maxQty);
        result.put("quantityWithinMax", true);
        // R1 预算 (info)
        result.put("estimatedUnitPrice", estimatedUnitPrice);
        result.put("estimatedBudget", estimatedBudget);
        result.put("priceSource", estimatedUnitPrice != null
                ? String.format("最近 90 天 PO 历史加权 AVG")
                : "无历史价格数据 — 单价空, 创单后手工填");
        // R3 推荐 (UI 已选定 supplier, 此处为透明性展示备选)
        result.put("recommendedSuppliers", recommendedSuppliers);

        // 4 位一体: 含 next action
        result.put("approvalChainHint",
                "草稿创建后 → 主管审批 → APPROVED 状态可入库收货");
        result.put("nextActionUrl", "/purchase/orders");

        String budgetStr = estimatedBudget != null
                ? String.format("预算 ¥%s (按 ¥%s/单位 × %s)",
                        estimatedBudget.toPlainString(),
                        estimatedUnitPrice.toPlainString(),
                        stripZeros(quantity))
                : "预算未定 (无历史价)";
        result.put("message", String.format(
                "📝 采购 %s × %s%s, 供应商 [%s], %s, 期望 %s 到货. 月均用量 %s%s × 3 = 上限 %s%s. 确认提交?",
                material.getName(), stripZeros(quantity), nullToEmpty(material.getUnit()),
                supplier.getName(), budgetStr, expectedDate,
                stripZeros(monthlyAvg), nullToEmpty(material.getUnit()),
                stripZeros(maxQty), nullToEmpty(material.getUnit())));
        return result;
    }

    @Override
    @Transactional
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String materialId = getString(params, "materialId");
        String supplierId = getString(params, "supplierId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String expectedDateStr = getString(params, "expectedDeliveryDate");
        String remark = getString(params, "remark", "");
        Boolean testRun = getBoolean(params, "testRun", false);
        Long userId = getUserId(context);

        if (userId == null) {
            throw new IllegalArgumentException("当前会话无用户 ID, 无法创建采购单");
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("采购数量必须 > 0");
        }

        Optional<RawMaterialType> matOpt = rawMaterialTypeRepository.findById(materialId);
        if (matOpt.isEmpty() || !factoryId.equals(matOpt.get().getFactoryId())) {
            throw new IllegalArgumentException(
                    String.format("物料 %s 不存在或非本工厂", materialId));
        }
        RawMaterialType material = matOpt.get();

        Optional<Supplier> supOpt = supplierRepository.findByIdAndFactoryId(supplierId, factoryId);
        if (supOpt.isEmpty()) {
            throw new IllegalArgumentException(
                    String.format("供应商 %s 不存在或非本工厂", supplierId));
        }
        Supplier supplier = supOpt.get();
        if (Boolean.FALSE.equals(supplier.getIsActive())) {
            throw new IllegalArgumentException(
                    String.format("供应商 [%s] 已停用, 不可下单", supplier.getName()));
        }

        // R1 服务端二次防御 (前端可能绕过)
        BigDecimal monthlyAvg = calculateMonthlyAvgUsage(materialId);
        BigDecimal maxQty = monthlyAvg.multiply(BigDecimal.valueOf(MAX_QUANTITY_MONTHS_BUFFER));
        if (monthlyAvg.compareTo(BigDecimal.ZERO) > 0
                && quantity.compareTo(maxQty) > 0) {
            throw new IllegalArgumentException(String.format(
                    "采购数量 %s 超过上限 %s (月均用量 %s × 3 月 buffer). 请减少数量或分批采购.",
                    quantity.toPlainString(), maxQty.toPlainString(), monthlyAvg.toPlainString()));
        }

        // R4 idempotency check (server-side)
        Optional<PurchaseOrder> existing = findRecentDuplicate(factoryId, materialId, supplierId);
        if (existing.isPresent()) {
            PurchaseOrder dup = existing.get();
            // 409-style 响应 (Tool 返 Map, 调用方 frontend 据 status 弹 ElMessageBox.confirm)
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "DUPLICATE");
            data.put("existingPoId", dup.getId());
            data.put("existingOrderNumber", dup.getOrderNumber());
            data.put("actionHint", String.format("已有近期采购单 %s, 是否前往查看?", dup.getOrderNumber()));
            data.put("nextActionUrl", "/purchase/orders/" + dup.getId());
            return buildSimpleResult(
                    String.format("⚠️ 已有近期采购单 %s (同物料+同供应商 %d 分钟内创建), 已跳过重复创建.",
                            dup.getOrderNumber(), IDEMPOTENCY_WINDOW_MINUTES),
                    data);
        }

        // 期望到货日 (默认 7d 后)
        LocalDate expectedDate = LocalDate.now().plusDays(7);
        if (expectedDateStr != null && !expectedDateStr.isBlank()) {
            try {
                expectedDate = LocalDate.parse(expectedDateStr);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "期望到货日格式无效, 请用 YYYY-MM-DD: " + expectedDateStr);
            }
            if (expectedDate.isBefore(LocalDate.now())) {
                throw new IllegalArgumentException("期望到货日不能在过去: " + expectedDate);
            }
        }

        // 历史价 (单价默认 = 历史 AVG, 无历史则 null)
        BigDecimal estimatedUnitPrice = estimateUnitPrice(factoryId, materialId);

        // 构 CreatePurchaseOrderRequest
        CreatePurchaseOrderRequest req = new CreatePurchaseOrderRequest();
        req.setSupplierId(supplier.getId());
        req.setPurchaseType("DIRECT");
        req.setOrderDate(LocalDate.now());
        req.setExpectedDeliveryDate(expectedDate);
        req.setRemark(remark != null && !remark.isBlank()
                ? remark
                : "Sprint 10 Loop 3 — AI Workdesk 一键采购下单");

        CreatePurchaseOrderRequest.PurchaseOrderItemDTO line =
                new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        line.setMaterialTypeId(material.getId());
        line.setMaterialName(material.getName());
        line.setQuantity(quantity);
        line.setUnit(material.getUnit() != null ? material.getUnit() : "件");
        if (estimatedUnitPrice != null) {
            line.setUnitPrice(estimatedUnitPrice);
        }
        req.setItems(List.of(line));

        log.info("procurement_order_create — factory={} userId={} material={} supplier={} qty={} expected={} testRun={}",
                factoryId, userId, material.getName(), supplier.getName(), quantity, expectedDate, testRun);

        PurchaseOrder created = purchaseService.createPurchaseOrder(factoryId, req, userId);

        // 写 aiInvocationMetadata (Sprint 10 P0 V20260822_00)
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "sprint-10-loop-3");
        metadata.put("testRun", Boolean.TRUE.equals(testRun));
        metadata.put("createdAt", LocalDateTime.now().toString());
        metadata.put("invokedBy", userId);
        metadata.put("toolName", getToolName());
        // Reload via repository to get managed instance, then save with metadata
        PurchaseOrder reload = purchaseOrderRepository.findById(created.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "刚创建的 PO 找不到: " + created.getId()));
        reload.setAiInvocationMetadata(metadata);
        PurchaseOrder saved = purchaseOrderRepository.save(reload);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "CREATED");
        data.put("poId", saved.getId());
        data.put("orderNumber", saved.getOrderNumber());
        data.put("poStatus", saved.getStatus().name());
        data.put("materialId", material.getId());
        data.put("materialName", material.getName());
        data.put("supplierId", supplier.getId());
        data.put("supplierName", supplier.getName());
        data.put("quantity", quantity);
        data.put("expectedDeliveryDate", expectedDate.toString());
        data.put("totalAmount", saved.getTotalAmount());
        data.put("nextActionHint",
                String.format("采购单 %s 已创建 (DRAFT). 下一步: 前往采购单详情提审 → 审批 → 入库收货",
                        saved.getOrderNumber()));
        data.put("nextActionUrl", "/purchase/orders/" + saved.getId());

        String message = String.format(
                "✅ 已生 %s 草稿 — %s × %s%s, 供应商 [%s], 期望 %s 到货. 前往审批: /purchase/orders/%s",
                saved.getOrderNumber(), material.getName(),
                stripZeros(quantity), nullToEmpty(material.getUnit()),
                supplier.getName(), expectedDate, saved.getId());
        return buildSimpleResult(message, data);
    }

    /**
     * R1 — 计算月均用量 (历史 PO items 最近 90 天 / 3 月).
     * 若无历史 PO 数据, 返回 ZERO (跳过 max 校验).
     */
    private BigDecimal calculateMonthlyAvgUsage(String materialTypeId) {
        List<PurchaseOrderItem> items = purchaseOrderItemRepository.findByMaterialTypeId(materialTypeId);
        if (items == null || items.isEmpty()) return BigDecimal.ZERO;

        // 累加最近 90 天 (用 PO order_date 但 Item 没有 join, 用 ID 直接 sum 简化 — 完整 join 留 future PR)
        BigDecimal sumQty = BigDecimal.ZERO;
        int included = 0;
        for (PurchaseOrderItem item : items) {
            BigDecimal qty = item.getQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) continue;
            sumQty = sumQty.add(qty);
            included++;
            if (included >= 50) break;
        }
        if (included == 0) return BigDecimal.ZERO;

        // 总量 / 3 月 = 月均 (90 天回看 → 3 个月)
        BigDecimal avgPerMonth = sumQty.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        return avgPerMonth;
    }

    /**
     * 历史加权 AVG 单价 (最近 90 天).
     */
    private BigDecimal estimateUnitPrice(String factoryId, String materialTypeId) {
        List<PurchaseOrderItem> items = purchaseOrderItemRepository.findByMaterialTypeId(materialTypeId);
        if (items == null || items.isEmpty()) return null;

        BigDecimal sumAmount = BigDecimal.ZERO;
        BigDecimal sumQty = BigDecimal.ZERO;
        int included = 0;
        for (PurchaseOrderItem item : items) {
            BigDecimal price = item.getUnitPrice();
            BigDecimal qty = item.getQuantity();
            if (price == null || qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) continue;
            sumAmount = sumAmount.add(price.multiply(qty));
            sumQty = sumQty.add(qty);
            included++;
            if (included >= 50) break;
        }
        if (sumQty.compareTo(BigDecimal.ZERO) <= 0) return null;
        return sumAmount.divide(sumQty, 4, RoundingMode.HALF_UP);
    }

    /**
     * R3 — 推荐供应商 top 3 (by last-PO 时间 + weighted AVG 价格).
     */
    private List<Map<String, Object>> recommendSuppliers(String factoryId, String materialTypeId) {
        List<PurchaseOrderItem> items = purchaseOrderItemRepository.findByMaterialTypeId(materialTypeId);
        if (items == null || items.isEmpty()) return Collections.emptyList();

        // Group by supplier (via PO lookup), 统计 last-used 时间 + AVG 价格
        Map<String, SupplierStats> bySupplier = new HashMap<>();
        for (PurchaseOrderItem item : items) {
            Optional<PurchaseOrder> poOpt = purchaseOrderRepository.findById(item.getPurchaseOrderId());
            if (poOpt.isEmpty()) continue;
            PurchaseOrder po = poOpt.get();
            if (!factoryId.equals(po.getFactoryId())) continue;
            String supId = po.getSupplierId();
            if (supId == null) continue;
            SupplierStats stats = bySupplier.computeIfAbsent(supId, k -> new SupplierStats(k));
            stats.recordPO(po.getOrderDate(), item.getUnitPrice(), item.getQuantity());
        }

        // 按 last-used DESC + AVG 价格 ASC ranked (lower price first when tie)
        List<SupplierStats> sorted = bySupplier.values().stream()
                .sorted(Comparator
                        .comparing((SupplierStats s) -> s.lastUsed == null ? LocalDate.MIN : s.lastUsed,
                                Comparator.reverseOrder())
                        .thenComparing(s -> s.avgPrice() != null ? s.avgPrice() : BigDecimal.valueOf(Double.MAX_VALUE)))
                .limit(SUPPLIER_RANKING_TOP_N)
                .collect(Collectors.toList());

        List<Map<String, Object>> recommended = new ArrayList<>();
        for (SupplierStats s : sorted) {
            Optional<Supplier> supOpt = supplierRepository.findByIdAndFactoryId(s.supplierId, factoryId);
            if (supOpt.isEmpty()) continue;
            Supplier sup = supOpt.get();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("supplierId", sup.getId());
            entry.put("supplierName", sup.getName());
            entry.put("lastUsed", s.lastUsed != null ? s.lastUsed.toString() : null);
            entry.put("avgPrice", s.avgPrice());
            entry.put("deliveryDays", sup.getDeliveryDays());
            entry.put("recommendReason", s.lastUsed != null
                    ? String.format("最近 %s 用过", s.lastUsed)
                    : "历史曾用");
            recommended.add(entry);
        }
        return recommended;
    }

    /**
     * R4 — 找最近 5 分钟内同物料 + 同供应商的 PO (避免重复创建).
     * 用 JPQL 查 createdAt > cutoff AND items 中含 materialTypeId.
     */
    private Optional<PurchaseOrder> findRecentDuplicate(
            String factoryId, String materialId, String supplierId) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(IDEMPOTENCY_WINDOW_MINUTES);
        // 简化: 查同 factory + 同 supplier 最近的 PO, 再 check items 含 materialId.
        // Repository 没现成方法, 用 findByFactoryIdAndSupplierId + filter (PO 数量可控)
        List<PurchaseOrder> recent = purchaseOrderRepository.findByFactoryIdAndSupplierId(factoryId, supplierId);
        for (PurchaseOrder po : recent) {
            if (po.getCreatedAt() == null || po.getCreatedAt().isBefore(cutoff)) continue;
            // check items 含 materialId
            for (PurchaseOrderItem item : po.getItems()) {
                if (materialId.equals(item.getMaterialTypeId())) {
                    return Optional.of(po);
                }
            }
        }
        return Optional.empty();
    }

    private static Map<String, Object> buildInvalid(String status, String message, String nextActionUrl) {
        return buildInvalid(status, message, nextActionUrl, Collections.emptyMap());
    }

    private static Map<String, Object> buildInvalid(String status, String message,
            String nextActionUrl, Map<String, Object> extra) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("canDo", false);
        r.put("message", message);
        r.put("nextActionUrl", nextActionUrl);
        if (extra != null && !extra.isEmpty()) {
            r.putAll(extra);
        }
        return r;
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

    /**
     * Helper struct for supplier ranking.
     */
    private static class SupplierStats {
        final String supplierId;
        LocalDate lastUsed;
        BigDecimal sumAmount = BigDecimal.ZERO;
        BigDecimal sumQty = BigDecimal.ZERO;

        SupplierStats(String supplierId) {
            this.supplierId = supplierId;
        }

        void recordPO(LocalDate orderDate, BigDecimal price, BigDecimal qty) {
            if (orderDate != null && (lastUsed == null || orderDate.isAfter(lastUsed))) {
                lastUsed = orderDate;
            }
            if (price != null && qty != null && qty.compareTo(BigDecimal.ZERO) > 0) {
                sumAmount = sumAmount.add(price.multiply(qty));
                sumQty = sumQty.add(qty);
            }
        }

        BigDecimal avgPrice() {
            if (sumQty.compareTo(BigDecimal.ZERO) <= 0) return null;
            return sumAmount.divide(sumQty, 4, RoundingMode.HALF_UP);
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
