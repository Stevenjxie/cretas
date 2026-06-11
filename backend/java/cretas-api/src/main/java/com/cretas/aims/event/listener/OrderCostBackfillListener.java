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
 *
 * <p><b>跨路径修复 (2026-06-11, Fable E2E 断点1)</b>: SP5 合并计划场景下,
 * {@code plan.getSourceOrderId()} 只是主 SO; 次级 SO 的行项目过去永远不回填
 * costUnitPrice → 次级订单财审 actualCost 永远 null. 现回填遍历
 * {@code plan.getSourceOrderIds()} (全部关联 SO). 向后兼容: 单 SO
 * (sourceOrderIds 空/单元素) 行为不变 — 仍只回填主 SO.
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

            String productTypeId = event.getProductTypeId();
            if (productTypeId == null) return;

            // 断点1 修复: 合并计划覆盖多张 SO — 回填遍历 sourceOrderIds (全部关联 SO),
            // 不再只回填主 sourceOrderId. 兼容遗留数据: sourceOrderIds 为空时退回主 SO.
            java.util.List<String> targetOrderIds = new java.util.ArrayList<>();
            java.util.List<String> srcIds = plan.getSourceOrderIds();
            if (srcIds != null && !srcIds.isEmpty()) {
                for (String id : srcIds) {
                    if (id != null && !targetOrderIds.contains(id)) {
                        targetOrderIds.add(id);
                    }
                }
            }
            // 向后兼容: sourceOrderIds 缺失 (遗留计划未迁移) → 退回主 sourceOrderId
            if (targetOrderIds.isEmpty()) {
                String primary = plan.getSourceOrderId();
                if (primary != null) {
                    targetOrderIds.add(primary);
                }
            }
            if (targetOrderIds.isEmpty()) return;

            int updated = 0;
            for (String orderId : targetOrderIds) {
                var items = salesOrderItemRepository.findBySalesOrderId(orderId);
                for (var item : items) {
                    if (productTypeId.equals(item.getProductTypeId())
                            && item.getCostUnitPrice() == null) {
                        item.setCostUnitPrice(event.getActualUnitCost());
                        salesOrderItemRepository.save(item);
                        updated++;
                    }
                }
            }
            if (updated > 0) {
                log.info("[SP3-Backfill] 回填 costUnitPrice={} → salesOrderIds={}, productTypeId={}, 共{}行",
                        event.getActualUnitCost(), targetOrderIds, productTypeId, updated);
            }
        } catch (Exception e) {
            // fail-soft: 回填失败不影响主流程
            log.error("[SP3-Backfill] 回填失败: {}", e.getMessage(), e);
        }
    }
}
