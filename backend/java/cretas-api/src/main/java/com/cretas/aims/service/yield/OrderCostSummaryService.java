package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.OrderCostSummaryRowDTO;
import com.cretas.aims.dto.yield.OrderYieldSummaryDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 成本汇总页 (对标客户 M67 Excel「汇总页」): 给工厂在某下单日期区间内的每张订单一行成本汇总。
 * 复用 {@link OrderCostBreakdownService#compute} (单一权威成本) + {@link YieldReportService#getOrderYieldSummary} (整批出成率)。
 * 不新增数据/schema; 价格脱敏由 compute() 内部按 maskPrice 处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCostSummaryService {

    private final SalesOrderRepository salesOrderRepository;
    private final OrderCostBreakdownService orderCostBreakdownService;
    private final YieldReportService yieldReportService;
    private final ProductTypeRepository productTypeRepository;

    /** 单次汇总最多 200 张订单, 防止区间过大拖垮 (超出在日志告警, 不静默截断)。 */
    private static final int MAX_ORDERS = 200;

    public List<OrderCostSummaryRowDTO> summarize(String factoryId, LocalDate startDate, LocalDate endDate, boolean maskPrice) {
        List<SalesOrder> orders = salesOrderRepository.findByFactoryIdAndDateRange(factoryId, startDate, endDate);
        if (orders.size() > MAX_ORDERS) {
            log.warn("[M67CostSummary] factory={} 区间 {}~{} 订单 {} 张超过上限 {}, 仅汇总前 {} 张",
                    factoryId, startDate, endDate, orders.size(), MAX_ORDERS, MAX_ORDERS);
            orders = orders.subList(0, MAX_ORDERS);
        }
        List<OrderCostSummaryRowDTO> rows = new ArrayList<>();
        for (SalesOrder so : orders) {
            if (so.getId() == null) {
                continue;
            }
            OrderCostBreakdownDTO cb = orderCostBreakdownService.compute(factoryId, so.getId(), maskPrice);
            if (cb == null || !cb.isHasData()) {
                continue;   // 该订单无生产批次 → 不进汇总 (诚实, 不造 0 行)
            }
            OrderYieldSummaryDTO ys = yieldReportService.getOrderYieldSummary(factoryId, so.getId());
            ProductType product = resolveProduct(factoryId, cb.getProductSku());
            rows.add(OrderCostSummaryRowDTO.builder()
                    .orderId(so.getId())
                    .orderNumber(so.getOrderNumber())
                    .orderDate(so.getOrderDate())
                    .productName(product != null && product.getName() != null && !product.getName().isBlank()
                            ? product.getName() : cb.getProductName())
                    .skuCode(product == null ? null : product.getCode())
                    .boxCount(cb.getBoxCount())
                    .overallYieldRate(ys != null ? ys.getOverallYieldRate() : null)
                    .rawMaterialCost(cb.getRawMaterialCost())
                    .laborCost(cb.getLaborCost())
                    .seasoningCost(cb.getSeasoningCost())
                    .packagingCost(cb.getPackagingCost())
                    .totalCost(cb.getTotalCost())
                    .perBoxCost(cb.getPerBoxCost())
                    .knownCostSubtotal(cb.getKnownCostSubtotal())
                    .knownPerBoxCost(cb.getKnownPerBoxCost())
                    .calculationStatus(cb.getCalculationStatus())
                    .missingCostItems(cb.getMissingCostItems())
                    .build());
        }
        return rows;
    }

    /**
     * 解析成品主数据。{@code cb.getProductSku()} 实为 {@code product_type_id} (UUID), 这里换成
     * 操作员看得懂的 code + name。
     *
     * <p>订单跨多个产品时 compute() 已经返回 null (uniqueOrNull), 这里也就查不到 —— 保持"—",
     * 不挑一个产品冒充整张订单。
     */
    private ProductType resolveProduct(String factoryId, String productTypeId) {
        if (productTypeRepository == null || productTypeId == null || productTypeId.isBlank()) {
            return null;
        }
        return productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId).orElse(null);
    }
}
