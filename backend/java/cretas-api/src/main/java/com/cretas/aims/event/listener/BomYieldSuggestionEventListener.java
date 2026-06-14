package com.cretas.aims.event.listener;

import com.cretas.aims.event.BatchCompletedEvent;
import com.cretas.aims.event.ProductionSettledEvent;
import com.cretas.aims.service.bom.BomYieldSuggestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class BomYieldSuggestionEventListener {

    private final BomYieldSuggestionService bomYieldSuggestionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBatchCompleted(BatchCompletedEvent event) {
        if (event == null || event.getBatch() == null) {
            log.warn("[BomYieldSuggestion] empty BatchCompletedEvent skipped");
            return;
        }
        bomYieldSuggestionService.generateForProduct(
                event.getFactoryId(),
                event.getBatch().getProductTypeId(),
                "BATCH_COMPLETED",
                String.valueOf(event.getBatchId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onProductionSettled(ProductionSettledEvent event) {
        if (event == null) {
            log.warn("[BomYieldSuggestion] empty ProductionSettledEvent skipped");
            return;
        }
        bomYieldSuggestionService.generateForProduct(
                event.getFactoryId(),
                event.getProductTypeId(),
                "PRODUCTION_SETTLED",
                event.getSettlementId());
    }
}
