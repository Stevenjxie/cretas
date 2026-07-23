package com.cretas.aims.service.orchestration;

import com.cretas.aims.event.ProductionCompletedEvent;
import com.cretas.aims.service.inventory.TransferService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProductionPlanTransferLifecycleListenerTest {

    @Test
    void finalProductionCompletion_closesOnlyOpenTransfersLinkedToThePlan() {
        TransferService transferService = mock(TransferService.class);
        ProductionPlanTransferLifecycleListener listener =
                new ProductionPlanTransferLifecycleListener(transferService);
        ProductionCompletedEvent event = new ProductionCompletedEvent(
                this, "F006", "plan-1", "PLAN-001", "sku-1", new BigDecimal("5"));

        listener.onProductionCompleted(event);

        verify(transferService).closeOpenTransfersForProductionPlan(
                "F006", "plan-1", 0L, "生产计划已正式完成，关闭未完成的备料调拨关联");
    }
}
