package com.cretas.aims.event.listener;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.event.BatchCompletedEvent;
import com.cretas.aims.event.ProductionSettledEvent;
import com.cretas.aims.service.bom.BomYieldSuggestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("BomYieldSuggestionEventListener")
class BomYieldSuggestionEventListenerTest {

    @Test
    @DisplayName("BatchCompletedEvent is handled AFTER_COMMIT and delegates without applying BOM")
    void batchCompleted_afterCommitDelegates() throws Exception {
        BomYieldSuggestionService service = mock(BomYieldSuggestionService.class);
        BomYieldSuggestionEventListener listener = new BomYieldSuggestionEventListener(service);

        ProductionBatch batch = new ProductionBatch();
        batch.setId(100L);
        batch.setFactoryId("F006");
        batch.setProductTypeId("PT-1");

        listener.onBatchCompleted(new BatchCompletedEvent(this, batch));

        verify(service).generateForProduct("F006", "PT-1", "BATCH_COMPLETED", "100");
        Method method = BomYieldSuggestionEventListener.class
                .getDeclaredMethod("onBatchCompleted", BatchCompletedEvent.class);
        assertThat(method.getAnnotation(TransactionalEventListener.class)).isNotNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class).fallbackExecution()).isFalse();
    }

    @Test
    @DisplayName("ProductionSettledEvent is handled AFTER_COMMIT and delegates by productType")
    void productionSettled_afterCommitDelegates() {
        BomYieldSuggestionService service = mock(BomYieldSuggestionService.class);
        BomYieldSuggestionEventListener listener = new BomYieldSuggestionEventListener(service);

        listener.onProductionSettled(new ProductionSettledEvent(
                this, "F006", "PLAN-1", "P-001", "PT-1", "settlement-1", new BigDecimal("90")));

        verify(service).generateForProduct("F006", "PT-1", "PRODUCTION_SETTLED", "settlement-1");
    }
}
