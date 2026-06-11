package com.cretas.aims.event.listener;

import com.cretas.aims.event.ProductionCostUpdatedEvent;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SP3: 生产完工后自动回填 SalesOrderItem.costUnitPrice.
 *
 * <p>事件源: {@link ProductionCostUpdatedEvent}
 * <p>隔离级别: {@code REQUIRES_NEW} 防止 doomed-tx 传播
 * (see feedback_failsoft_catch_cannot_save_doomed_tx).
 * <p>幂等: 仅当 costUnitPrice 为 null 时写入; 已有值不覆盖.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCostBackfillListener {

    private final ProductionBatchRepository batchRepo;
    private final ProductionPlanRepository planRepo;
    private final SalesOrderItemRepository salesOrderItemRepository;

    @Async
    @EventListener
    @Transactional(transactionManager = "primaryTransactionManager",
                   propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onProductionCostUpdated(ProductionCostUpdatedEvent event) {
        try {
            Long batchId = event.getProductionBatchId();
            if (batchId == null) return;

            var batchOpt = batchRepo.findById(batchId);
            if (batchOpt.isEmpty()) {
                log.warn("[SP3-Backfill] 批次不存在: batchId={}", batchId);
                return;
            }
            var batch = batchOpt.get();
            String planId = batch.getProductionPlanId();
            if (planId == null) return;

            var planOpt = planRepo.findById(planId);
            if (planOpt.isEmpty()) return;
            var plan = planOpt.get();
            String sourceOrderId = plan.getSourceOrderId();
            if (sourceOrderId == null) return;

            String productTypeId = event.getProductTypeId();
            if (productTypeId == null) return;

            var items = salesOrderItemRepository.findBySalesOrderId(sourceOrderId);
            int updated = 0;
            for (var item : items) {
                if (productTypeId.equals(item.getProductTypeId())
                        && item.getCostUnitPrice() == null) {
                    item.setCostUnitPrice(event.getActualUnitCost());
                    salesOrderItemRepository.save(item);
                    updated++;
                }
            }
            if (updated > 0) {
                log.info("[SP3-Backfill] 回填 costUnitPrice={} → salesOrderId={}, productTypeId={}, 共{}行",
                        event.getActualUnitCost(), sourceOrderId, productTypeId, updated);
            }
        } catch (Exception e) {
            // fail-soft: 回填失败不影响主流程
            log.error("[SP3-Backfill] 回填失败: {}", e.getMessage(), e);
        }
    }
}
