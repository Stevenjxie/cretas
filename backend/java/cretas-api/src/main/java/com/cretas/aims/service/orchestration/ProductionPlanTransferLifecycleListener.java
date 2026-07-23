package com.cretas.aims.service.orchestration;

import com.cretas.aims.event.ProductionCompletedEvent;
import com.cretas.aims.service.inventory.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Closes only uncompleted, plan-linked preparation transfers after formal completion. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductionPlanTransferLifecycleListener {

    private static final long SYSTEM_USER_ID = 0L;
    private final TransferService transferService;

    @EventListener
    public void onProductionCompleted(ProductionCompletedEvent event) {
        int closed = transferService.closeOpenTransfersForProductionPlan(
                event.getFactoryId(), event.getPlanId(), SYSTEM_USER_ID,
                "生产计划已正式完成，关闭未完成的备料调拨关联");
        if (closed > 0) {
            log.info("Closed {} open rolling transfers for completed plan {}", closed, event.getPlanId());
        }
    }
}
