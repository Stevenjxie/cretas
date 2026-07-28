package com.cretas.aims.ai.tool.impl.production;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sprint 10 Loop 5 — 缺产订单查询 Tool (READ).
 *
 * <p>For每个 productId 聚合所有未完成 SO 的 (quantity - deliveredQuantity - inProgressQuantity),
 * 返回 > 0 的产品 + 推荐起产量 + 推荐生产线.</p>
 *
 * <p>"未完成 SO" 定义: status IN (CONFIRMED / FINANCE_APPROVED / PROCESSING / PARTIAL_DELIVERED).</p>
 *
 * <p>"在产数量" 定义: 同 productTypeId 的 PLANNED / PLANNING / IN_PROGRESS / PRODUCING 批次的
 * (plannedQuantity - actualQuantity) 之和 — 即已计划但未交付的产量.</p>
 *
 * <p>防呆 R2: 每行带 productName + 待产量 + 推荐数量 + 推荐生产线 + 推荐排程日 — AI 输出可直接用
 * 不需用户追问.</p>
 */
@Slf4j
@Component
public class ProductionDemandQueryTool extends AbstractBusinessTool {

    private static final Set<SalesOrderStatus> ACTIVE_SO_STATUSES = Set.of(
            SalesOrderStatus.CONFIRMED,
            SalesOrderStatus.FINANCE_APPROVED,
            SalesOrderStatus.PROCESSING,
            SalesOrderStatus.PARTIAL_DELIVERED
    );

    private static final Set<ProductionBatchStatus> IN_PROGRESS_BATCH_STATUSES = Set.of(
            ProductionBatchStatus.PLANNED,
            ProductionBatchStatus.PLANNING,
            ProductionBatchStatus.IN_PROGRESS,
            ProductionBatchStatus.PRODUCING,
            ProductionBatchStatus.PAUSED
    );

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesOrderItemRepository salesOrderItemRepository;

    @Autowired
    private ProductionBatchRepository productionBatchRepository;

    @Override
    public String getToolName() {
        return "production_demand_query";
    }

    @Override
    public String getDescription() {
        return "查询当前工厂所有缺产订单 — 列出 (SO 总量 - 已发货 - 在产) > 0 的产品 + 推荐起产量 + "
                + "推荐生产线. 用户问 '今天要起产什么?' / '订单缺产' / '排产建议' / '什么订单缺货要做' "
                + "时调用. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "maxRows", Map.of(
                                "type", "integer",
                                "description", "返回最多多少行 (默认 20)"
                        )
                ),
                "required", List.of()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("production", "demand", "planning");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        int maxRows = Optional.ofNullable(getInteger(params, "maxRows", 20)).orElse(20);
        if (maxRows <= 0 || maxRows > 100) {
            maxRows = 20;
        }

        // 1. 获取所有 active SO + 行项目
        List<SalesOrder> activeOrders = salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                factoryId, ACTIVE_SO_STATUSES, PageRequest.of(0, 500));
        if (activeOrders.isEmpty()) {
            return buildSimpleResult("当前无未完成销售订单, 无需排产", buildEmptyEnvelope());
        }

        // 2. 聚合各产品的 (quantity - deliveredQuantity)
        Map<String, ProductDemand> demandByProduct = new LinkedHashMap<>();
        for (SalesOrder so : activeOrders) {
            List<SalesOrderItem> items = salesOrderItemRepository.findBySalesOrderId(so.getId());
            for (SalesOrderItem item : items) {
                if (item.getProductTypeId() == null) continue;
                BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
                BigDecimal delivered = item.getDeliveredQuantity() != null
                        ? item.getDeliveredQuantity() : BigDecimal.ZERO;
                BigDecimal pending = qty.subtract(delivered);
                if (pending.compareTo(BigDecimal.ZERO) <= 0) continue;

                ProductDemand pd = demandByProduct.computeIfAbsent(
                        item.getProductTypeId(),
                        k -> new ProductDemand(k, item.getProductName(),
                                item.getUnit() != null ? item.getUnit() : "kg"));
                pd.addOrderLine(so, item, pending);
            }
        }

        if (demandByProduct.isEmpty()) {
            return buildSimpleResult("所有未完成订单已全部发货, 无需排产",
                    buildEmptyEnvelope());
        }

        // 3. 减去在产数量 (PLANNED/IN_PROGRESS 批次的 plannedQuantity - actualQuantity)
        deductInProgressProduction(factoryId, demandByProduct);

        // 4. 过滤 net > 0, 排序 + 限制行数
        List<ProductDemand> nonEmpty = demandByProduct.values().stream()
                .filter(pd -> pd.getNetShortage().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(ProductDemand::getNetShortage).reversed())
                .limit(maxRows)
                .collect(Collectors.toList());

        if (nonEmpty.isEmpty()) {
            return buildSimpleResult("所有缺货订单已有在产批次覆盖, 无需新排产",
                    buildEmptyEnvelope());
        }

        // 5. 构建结果
        List<Map<String, Object>> rows = nonEmpty.stream()
                .map(this::toRow)
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("totalCount", nonEmpty.size());
        data.put("products", rows);

        String msg = String.format("找到 %d 个待排产产品 (已扣已发货 + 在产)", nonEmpty.size());
        return buildSimpleResult(msg, data);
    }

    private void deductInProgressProduction(String factoryId, Map<String, ProductDemand> map) {
        // 近 30 天创建 + 状态为 active 的 batch
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        List<ProductionBatch> recent = productionBatchRepository
                .findByFactoryIdAndCreatedAtAfter(factoryId, cutoff);
        for (ProductionBatch b : recent) {
            if (b.getProductTypeId() == null) continue;
            if (!IN_PROGRESS_BATCH_STATUSES.contains(b.getStatus())) continue;
            ProductDemand pd = map.get(b.getProductTypeId());
            if (pd == null) continue;
            BigDecimal planned = b.getPlannedQuantity() != null
                    ? b.getPlannedQuantity() : BigDecimal.ZERO;
            BigDecimal actual = b.getActualQuantity() != null
                    ? b.getActualQuantity() : BigDecimal.ZERO;
            BigDecimal remaining = planned.subtract(actual).max(BigDecimal.ZERO);
            pd.addInProgress(remaining);
        }
    }

    private Map<String, Object> toRow(ProductDemand pd) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productTypeId", pd.getProductTypeId());
        row.put("productName", pd.getProductName() != null ? pd.getProductName() : "(未命名)");
        row.put("unit", pd.getUnit());
        row.put("totalDemand", pd.getTotalDemand());
        row.put("inProgressQuantity", pd.getInProgressQuantity());
        row.put("netShortage", pd.getNetShortage());
        row.put("recommendedQuantity", pd.getNetShortage());  // 默认推荐 = 净缺量
        row.put("recommendedLine", recommendLineFor(pd));     // 简单规则: 默认 DEDICATED_LINE_A
        row.put("orderCount", pd.getOrderLineCount());
        row.put("orderRefs", pd.getOrderRefs());              // 最多 5 个 (productName + orderNumber)
        return row;
    }

    /** 推荐生产线 — 简单规则: 默认 DEDICATED_LINE_A. 后续可接 ProductTypeMaster 或 BOM 配置. */
    private String recommendLineFor(ProductDemand pd) {
        return "DEDICATED_LINE_A";
    }

    private Map<String, Object> buildEmptyEnvelope() {
        Map<String, Object> data = new HashMap<>();
        data.put("totalCount", 0);
        data.put("products", List.of());
        return data;
    }

    /** 单一产品的需求聚合状态. */
    private static class ProductDemand {
        private final String productTypeId;
        private final String productName;
        private final String unit;
        private BigDecimal totalDemand = BigDecimal.ZERO;
        private BigDecimal inProgressQuantity = BigDecimal.ZERO;
        private final List<Map<String, Object>> orderRefs = new ArrayList<>();
        private int orderLineCount = 0;

        ProductDemand(String productTypeId, String productName, String unit) {
            this.productTypeId = productTypeId;
            this.productName = productName;
            this.unit = unit;
        }

        void addOrderLine(SalesOrder so, SalesOrderItem item, BigDecimal pending) {
            totalDemand = totalDemand.add(pending);
            orderLineCount++;
            if (orderRefs.size() < 5) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("salesOrderId", so.getId());
                ref.put("orderNumber", so.getOrderNumber());
                ref.put("customerName", so.getCustomerName());
                ref.put("pendingQuantity", pending);
                ref.put("requiredDeliveryDate", so.getRequiredDeliveryDate());
                orderRefs.add(ref);
            }
        }

        void addInProgress(BigDecimal qty) {
            inProgressQuantity = inProgressQuantity.add(qty);
        }

        BigDecimal getNetShortage() {
            return totalDemand.subtract(inProgressQuantity).max(BigDecimal.ZERO);
        }

        public String getProductTypeId() { return productTypeId; }
        public String getProductName() { return productName; }
        public String getUnit() { return unit; }
        public BigDecimal getTotalDemand() { return totalDemand; }
        public BigDecimal getInProgressQuantity() { return inProgressQuantity; }
        public int getOrderLineCount() { return orderLineCount; }
        public List<Map<String, Object>> getOrderRefs() { return orderRefs; }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
