package com.cretas.aims.ai.tool.impl.production;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Sprint 10 Loop 5 — 创建生产批次 Tool (WRITE + Preview).
 *
 * <p>核心防呆灵魂 Tool: 接 ProductionDemandQueryTool 输出, 用户 1-click confirm 后创建
 * ProductionBatch (status=PLANNED).</p>
 *
 * <p>4 大防呆 (per fool-proof-design.md):</p>
 * <ul>
 *   <li>R1 max — preview 返 maxAllowed (= 净缺量), execute() 校验 quantity ≤ maxAllowed</li>
 *   <li>R2 context — message 必带 productName + orderNumber + 计划数量</li>
 *   <li>R3 dropdown — productionLine 限 enum (DEDICATED_LINE_A/B/SHARED_LINE/OTHER)</li>
 *   <li>R4 idempotent — businessKey = factoryId + productTypeId + scheduledDate + 5min,
 *       重复触发返 409 风格 (status=DUPLICATE + existingBatchId + actionHint)</li>
 * </ul>
 *
 * <p>标记 ai_invocation_metadata = {source: "sprint-10-loop-5", testRun, createdAt}.</p>
 */
@Slf4j
@Component
public class ProductionBatchCreateTool extends AbstractBusinessTool {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Set<String> VALID_LINES = Set.of(
            "DEDICATED_LINE_A", "DEDICATED_LINE_B", "SHARED_LINE", "OTHER");
    private static final int DEDUP_WINDOW_MINUTES = 5;
    private static final Set<ProductionBatchStatus> IN_PROGRESS_BATCH_STATUSES = Set.of(
            ProductionBatchStatus.PLANNED,
            ProductionBatchStatus.PLANNING,
            ProductionBatchStatus.IN_PROGRESS,
            ProductionBatchStatus.PRODUCING,
            ProductionBatchStatus.PAUSED
    );
    private static final Set<SalesOrderStatus> ACTIVE_SO_STATUSES = Set.of(
            SalesOrderStatus.CONFIRMED,
            SalesOrderStatus.FINANCE_APPROVED,
            SalesOrderStatus.PROCESSING,
            SalesOrderStatus.PARTIAL_DELIVERED
    );

    @Autowired
    private ProductionBatchRepository productionBatchRepository;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesOrderItemRepository salesOrderItemRepository;

    @Override
    public String getToolName() {
        return "production_batch_create";
    }

    @Override
    public String getDescription() {
        return "为待产订单创建生产批次. WRITE + Preview. 防呆灵魂 Tool — R1 max (≤ 净缺量) + "
                + "R2 context (productName + orderNumber) + R3 productionLine dropdown + "
                + "R4 idempotent (5min 窗口去重). 用户问 '起产 X 件 productY' / '创建生产任务' 时调用.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("productTypeId", Map.of(
                "type", "string",
                "description", "产品类型ID (ProductType.id, 必填)"));
        properties.put("productName", Map.of(
                "type", "string",
                "description", "产品名称 (可选, 冗余显示)"));
        properties.put("quantity", Map.of(
                "type", "number",
                "description", "实际起产量, 正数, ≤ 当前净缺量"));
        properties.put("unit", Map.of(
                "type", "string",
                "description", "单位 (kg / 箱 / 件), 默认 kg"));
        properties.put("scheduledDate", Map.of(
                "type", "string",
                "description", "排程开工日, YYYY-MM-DD, 默认明天"));
        properties.put("productionLine", Map.of(
                "type", "string",
                "description", "生产线 enum: DEDICATED_LINE_A / DEDICATED_LINE_B / SHARED_LINE / OTHER"));
        properties.put("salesOrderId", Map.of(
                "type", "string",
                "description", "关联的销售订单ID (可选, 优先用于幂等 + R2 context)"));
        properties.put("testRun", Map.of(
                "type", "boolean",
                "description", "标记测试运行 (Playwright E2E 用), 默认 false"));
        properties.put("notes", Map.of(
                "type", "string",
                "description", "备注"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("productTypeId", "quantity"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("productTypeId", "quantity");
    }

    @Override
    public ActionType getActionType() {
        return ActionType.WRITE;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("production", "batch", "create");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    // ==================== Preview (R1 max + R4 dedup probe) ====================

    @Override
    protected Map<String, Object> doPreview(String factoryId,
                                              Map<String, Object> params,
                                              Map<String, Object> context) {
        ValidationContext vc = validateAndCompute(factoryId, params);
        if (vc.errorResult != null) {
            return vc.errorResult;
        }

        // 查同 key 已有批次?
        DuplicateCheck dup = checkDuplicate(factoryId, vc.productTypeId, vc.scheduledDate);
        Map<String, Object> result = new LinkedHashMap<>();
        if (dup.exists) {
            result.put("status", "DUPLICATE");
            result.put("canDo", false);
            result.put("existingBatchId", dup.batchId);
            result.put("existingBatchNumber", dup.batchNumber);
            result.put("actionHint", "/production/batches/" + dup.batchId);
            result.put("message", String.format(
                    "幂等校验失败 — 5 分钟内已为 %s 创建批次 %s, 是否查看?",
                    vc.productName != null ? vc.productName : vc.productTypeId,
                    dup.batchNumber));
            return result;
        }

        // R1 max 显式
        result.put("status", "PREVIEW");
        result.put("canDo", true);
        result.put("productTypeId", vc.productTypeId);
        result.put("productName", vc.productName);
        result.put("requestedQuantity", vc.quantity);
        result.put("maxAllowed", vc.maxAllowed);
        result.put("netShortage", vc.netShortage);
        result.put("inProgressQuantity", vc.inProgress);
        result.put("scheduledDate", vc.scheduledDate.toString());
        result.put("productionLine", vc.productionLine);
        result.put("unit", vc.unit);
        result.put("salesOrderId", vc.salesOrderId);
        result.put("salesOrderNumber", vc.salesOrderNumber);

        String msg = String.format(
                "预览创建批次 — %s (产品 %s), 计划起产 %s %s, 排程 %s, 生产线 %s. " +
                        "当前净缺量 %s, 在产 %s. 确认后创建 PLANNED 批次.",
                vc.productName != null ? vc.productName : "(未命名)",
                vc.productTypeId,
                vc.quantity.stripTrailingZeros().toPlainString(),
                vc.unit,
                vc.scheduledDate,
                vc.productionLine,
                vc.netShortage.stripTrailingZeros().toPlainString(),
                vc.inProgress.stripTrailingZeros().toPlainString());
        result.put("message", msg);
        return result;
    }

    // ==================== Execute (创建) ====================

    @Override
    @Transactional
    protected Map<String, Object> doExecute(String factoryId,
                                              Map<String, Object> params,
                                              Map<String, Object> context) throws Exception {
        ValidationContext vc = validateAndCompute(factoryId, params);
        if (vc.errorResult != null) {
            return vc.errorResult;
        }

        // R4 idempotent — 二次校验防 race
        DuplicateCheck dup = checkDuplicate(factoryId, vc.productTypeId, vc.scheduledDate);
        if (dup.exists) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "DUPLICATE");
            result.put("count", 0);
            result.put("existingBatchId", dup.batchId);
            result.put("existingBatchNumber", dup.batchNumber);
            result.put("actionHint", "/production/batches/" + dup.batchId);
            result.put("message", String.format(
                    "已为 %s 创建批次 %s (5 分钟去重窗口), 不重复创建. 前往查看: /production/batches/%s",
                    vc.productName != null ? vc.productName : vc.productTypeId,
                    dup.batchNumber, dup.batchId));
            return result;
        }

        // 实际创建
        Boolean testRun = getBoolean(params, "testRun", false);
        Long userId = context.get("userId") != null
                ? ((Number) context.get("userId")).longValue() : 0L;

        String batchNumber = generateBatchNumber(factoryId);
        Map<String, Object> aiMeta = new LinkedHashMap<>();
        aiMeta.put("source", "sprint-10-loop-5");
        aiMeta.put("testRun", Boolean.TRUE.equals(testRun));
        aiMeta.put("createdAt", LocalDateTime.now().toString());
        if (vc.salesOrderId != null) {
            aiMeta.put("salesOrderId", vc.salesOrderId);
        }

        ProductionBatch batch = ProductionBatch.builder()
                .factoryId(factoryId)
                .batchNumber(batchNumber)
                .productTypeId(vc.productTypeId)
                .productName(vc.productName)
                .plannedQuantity(vc.quantity)
                .quantity(vc.quantity)
                .unit(vc.unit)
                .status(ProductionBatchStatus.PLANNED)
                .equipmentName(vc.productionLine)
                .startTime(vc.scheduledDate.atStartOfDay())
                .notes(buildNotes(vc, getString(params, "notes")))
                .createdBy(userId)
                .aiInvocationMetadata(aiMeta)
                .build();

        ProductionBatch saved = productionBatchRepository.save(batch);
        log.info("Sprint 10 Loop 5 — 创建生产批次成功: factoryId={}, batchId={}, batchNumber={}, "
                        + "productTypeId={}, quantity={}, line={}, testRun={}",
                factoryId, saved.getId(), saved.getBatchNumber(),
                vc.productTypeId, vc.quantity, vc.productionLine, testRun);

        // 构 R2 message + actionHint
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "CREATED");
        result.put("count", 1);
        result.put("batchId", saved.getId());
        result.put("batchNumber", saved.getBatchNumber());
        result.put("productTypeId", saved.getProductTypeId());
        result.put("productName", saved.getProductName());
        result.put("quantity", saved.getPlannedQuantity());
        result.put("unit", saved.getUnit());
        result.put("scheduledDate", vc.scheduledDate.toString());
        result.put("productionLine", vc.productionLine);
        result.put("actionHint", "/production/batches/" + saved.getId());
        result.put("message", String.format(
                "已创批次 %s 计划起产 %s %s — 前往生产计划: /production/batches/%s",
                saved.getBatchNumber(),
                vc.quantity.stripTrailingZeros().toPlainString(),
                vc.unit,
                saved.getId()));
        return result;
    }

    // ==================== 内部 helper ====================

    private ValidationContext validateAndCompute(String factoryId, Map<String, Object> params) {
        ValidationContext vc = new ValidationContext();

        vc.productTypeId = getString(params, "productTypeId");
        if (vc.productTypeId == null || vc.productTypeId.isBlank()) {
            vc.errorResult = buildErr("productTypeId 必填", "INVALID_PARAM");
            return vc;
        }
        vc.productName = getString(params, "productName");
        vc.unit = Optional.ofNullable(getString(params, "unit")).filter(s -> !s.isBlank()).orElse("kg");

        BigDecimal qty = getBigDecimal(params, "quantity");
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            vc.errorResult = buildErr("quantity 必须大于 0", "INVALID_PARAM");
            return vc;
        }
        vc.quantity = qty;

        String lineStr = getString(params, "productionLine");
        if (lineStr == null || lineStr.isBlank()) {
            vc.productionLine = "DEDICATED_LINE_A";  // 默认
        } else {
            String upper = lineStr.trim().toUpperCase();
            if (!VALID_LINES.contains(upper)) {
                vc.errorResult = buildErr(
                        "productionLine 必须为: DEDICATED_LINE_A / DEDICATED_LINE_B / SHARED_LINE / OTHER",
                        "INVALID_PARAM");
                return vc;
            }
            vc.productionLine = upper;
        }

        String dateStr = getString(params, "scheduledDate");
        if (dateStr == null || dateStr.isBlank()) {
            vc.scheduledDate = LocalDate.now().plusDays(1);
        } else {
            try {
                vc.scheduledDate = LocalDate.parse(dateStr, DATE_FORMATTER);
            } catch (Exception e) {
                vc.errorResult = buildErr("scheduledDate 格式错误, 应为 YYYY-MM-DD", "INVALID_PARAM");
                return vc;
            }
        }

        vc.salesOrderId = getString(params, "salesOrderId");
        // 校验 SO + 提取 orderNumber (R2 context)
        if (vc.salesOrderId != null && !vc.salesOrderId.isBlank()) {
            salesOrderRepository.findById(vc.salesOrderId).ifPresent(so -> {
                if (factoryId.equals(so.getFactoryId())) {
                    vc.salesOrderNumber = so.getOrderNumber();
                }
            });
        }

        // 计算 maxAllowed (净缺量)
        computeMaxAllowed(factoryId, vc);
        if (vc.quantity.compareTo(vc.maxAllowed) > 0) {
            vc.errorResult = buildErr(String.format(
                    "起产量 %s 超过当前净缺量上限 %s — 已扣已发货 %s 和在产 %s. 请减少数量或先取消在产批次.",
                    vc.quantity.stripTrailingZeros().toPlainString(),
                    vc.maxAllowed.stripTrailingZeros().toPlainString(),
                    vc.totalDemand.stripTrailingZeros().toPlainString(),
                    vc.inProgress.stripTrailingZeros().toPlainString()),
                    "QUANTITY_EXCEEDS_MAX");
            return vc;
        }
        return vc;
    }

    private void computeMaxAllowed(String factoryId, ValidationContext vc) {
        BigDecimal demand = BigDecimal.ZERO;
        // 1. 累加同 productTypeId 的 active SO 行 pending
        List<SalesOrder> activeOrders = salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                factoryId, ACTIVE_SO_STATUSES, org.springframework.data.domain.PageRequest.of(0, 500));
        for (SalesOrder so : activeOrders) {
            List<SalesOrderItem> items = salesOrderItemRepository.findBySalesOrderId(so.getId());
            for (SalesOrderItem item : items) {
                if (!vc.productTypeId.equals(item.getProductTypeId())) continue;
                BigDecimal q = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
                BigDecimal d = item.getDeliveredQuantity() != null
                        ? item.getDeliveredQuantity() : BigDecimal.ZERO;
                BigDecimal pending = q.subtract(d);
                if (pending.compareTo(BigDecimal.ZERO) > 0) {
                    demand = demand.add(pending);
                }
            }
        }
        vc.totalDemand = demand;

        // 2. 累加同 productTypeId 的在产批次 remaining
        BigDecimal inProg = BigDecimal.ZERO;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        List<ProductionBatch> recent = productionBatchRepository
                .findByFactoryIdAndCreatedAtAfter(factoryId, cutoff);
        for (ProductionBatch b : recent) {
            if (!vc.productTypeId.equals(b.getProductTypeId())) continue;
            if (!IN_PROGRESS_BATCH_STATUSES.contains(b.getStatus())) continue;
            BigDecimal planned = b.getPlannedQuantity() != null
                    ? b.getPlannedQuantity() : BigDecimal.ZERO;
            BigDecimal actual = b.getActualQuantity() != null
                    ? b.getActualQuantity() : BigDecimal.ZERO;
            BigDecimal remaining = planned.subtract(actual).max(BigDecimal.ZERO);
            inProg = inProg.add(remaining);
        }
        vc.inProgress = inProg;
        vc.netShortage = demand.subtract(inProg).max(BigDecimal.ZERO);
        vc.maxAllowed = vc.netShortage;

        // 3. fillback productName 若调用方未传入: 从 SO 行项目第一个匹配的取
        if (vc.productName == null || vc.productName.isBlank()) {
            for (SalesOrder so : activeOrders) {
                for (SalesOrderItem item : salesOrderItemRepository.findBySalesOrderId(so.getId())) {
                    if (vc.productTypeId.equals(item.getProductTypeId())
                            && item.getProductName() != null
                            && !item.getProductName().isBlank()) {
                        vc.productName = item.getProductName();
                        break;
                    }
                }
                if (vc.productName != null) break;
            }
        }
    }

    /**
     * R4 幂等检测: 同 factoryId + productTypeId + scheduledDate 在 5 分钟窗口内有 PLANNED 批次?
     */
    private DuplicateCheck checkDuplicate(String factoryId, String productTypeId, LocalDate scheduledDate) {
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(DEDUP_WINDOW_MINUTES);
        List<ProductionBatch> recent = productionBatchRepository
                .findByFactoryIdAndCreatedAtAfter(factoryId, windowStart);
        for (ProductionBatch b : recent) {
            if (!productTypeId.equals(b.getProductTypeId())) continue;
            if (b.getStatus() != ProductionBatchStatus.PLANNED
                    && b.getStatus() != ProductionBatchStatus.PLANNING) continue;
            // scheduledDate 来自 startTime.toLocalDate()
            LocalDate batchDate = b.getStartTime() != null
                    ? b.getStartTime().atZone(ZoneId.systemDefault()).toLocalDate()
                    : null;
            if (batchDate == null) continue;
            if (batchDate.equals(scheduledDate)) {
                DuplicateCheck dc = new DuplicateCheck();
                dc.exists = true;
                dc.batchId = b.getId();
                dc.batchNumber = b.getBatchNumber();
                return dc;
            }
        }
        DuplicateCheck dc = new DuplicateCheck();
        dc.exists = false;
        return dc;
    }

    private String generateBatchNumber(String factoryId) {
        String prefix = "AI-B-" + LocalDate.now().toString().replace("-", "") + "-";
        String batchNumber = prefix + (System.currentTimeMillis() % 1000000);
        if (productionBatchRepository.existsByFactoryIdAndBatchNumber(factoryId, batchNumber)) {
            batchNumber = batchNumber + "-" + (int)(Math.random() * 1000);
        }
        return batchNumber;
    }

    private String buildNotes(ValidationContext vc, String userNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Sprint 10 Loop 5 AI 1-click 创建. ");
        if (vc.salesOrderNumber != null) {
            sb.append("关联 SO=").append(vc.salesOrderNumber).append(". ");
        }
        sb.append("Line=").append(vc.productionLine).append(". ");
        if (userNotes != null && !userNotes.isBlank()) {
            sb.append(userNotes);
        }
        return sb.toString();
    }

    private Map<String, Object> buildErr(String message, String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "INVALID");
        result.put("canDo", false);
        result.put("code", code);
        result.put("message", message);
        return result;
    }

    /** 内部 mutable 计算上下文 — preview / execute 共用. */
    private static class ValidationContext {
        String productTypeId;
        String productName;
        BigDecimal quantity;
        String unit;
        LocalDate scheduledDate;
        String productionLine;
        String salesOrderId;
        String salesOrderNumber;
        BigDecimal totalDemand = BigDecimal.ZERO;
        BigDecimal inProgress = BigDecimal.ZERO;
        BigDecimal netShortage = BigDecimal.ZERO;
        BigDecimal maxAllowed = BigDecimal.ZERO;
        Map<String, Object> errorResult;
    }

    private static class DuplicateCheck {
        boolean exists;
        Long batchId;
        String batchNumber;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
