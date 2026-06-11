package com.cretas.aims.event.listener;

import com.cretas.aims.event.ProductionCostUpdatedEvent;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.CostVarianceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * SP3: 生产成本更新后触发超支百分比预警.
 *
 * <p>当 actualUnitCost 超过 BOM 标准成本 × (1 + threshold%) 时记录告警日志.
 * <p>隔离级别: {@code REQUIRES_NEW} 防止 doomed-tx 传播.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCostAlarmListener {

    private final BomRecipeService bomRecipeService;
    private final CostVarianceService costVarianceService;

    @Async
    @EventListener
    @Transactional(transactionManager = "primaryTransactionManager",
                   propagation = Propagation.REQUIRES_NEW,
                   readOnly = true)
    public void onProductionCostUpdated(ProductionCostUpdatedEvent event) {
        try {
            String factoryId = event.getFactoryId();
            String productTypeId = event.getProductTypeId();
            BigDecimal actualUnitCost = event.getActualUnitCost();
            if (factoryId == null || productTypeId == null || actualUnitCost == null) return;

            var recipeOpt = bomRecipeService.getCurrentRecipe(factoryId, productTypeId);
            if (recipeOpt.isEmpty()) return;
            BigDecimal standardCost = recipeOpt.get().getTotalCost();
            if (standardCost == null || standardCost.compareTo(BigDecimal.ZERO) <= 0) return;

            BigDecimal variancePct = costVarianceService.computeVariancePct(actualUnitCost, standardCost);
            if (variancePct == null) return;

            BigDecimal threshold = costVarianceService.resolveThreshold(factoryId, productTypeId);
            boolean exceeded = variancePct.compareTo(threshold) > 0;

            if (exceeded) {
                log.warn("[SP3-Alarm] 成本超支预警: factoryId={}, productTypeId={}, " +
                                "标准成本={}, 实际成本={}, 超支={}%, 阈值={}%",
                        factoryId, productTypeId, standardCost, actualUnitCost,
                        variancePct, threshold);
            } else {
                log.info("[SP3-Alarm] 成本在控: factoryId={}, productTypeId={}, " +
                                "标准={}, 实际={}, 超支={}%",
                        factoryId, productTypeId, standardCost, actualUnitCost, variancePct);
            }
        } catch (Exception e) {
            // fail-soft: 告警失败不影响主流程
            log.error("[SP3-Alarm] 超支检查失败: {}", e.getMessage(), e);
        }
    }
}
