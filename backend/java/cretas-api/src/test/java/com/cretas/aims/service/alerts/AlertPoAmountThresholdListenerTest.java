package com.cretas.aims.service.alerts;

import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.event.PurchaseOrderCreatedEvent;
import com.cretas.aims.service.alerts.listener.AlertPoAmountThresholdListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertPoAmountThresholdListener 单元测试 — Phase 2 Canvas-Alerts.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@DisplayName("AlertPoAmountThresholdListener 单元测试")
@ExtendWith(MockitoExtension.class)
class AlertPoAmountThresholdListenerTest {

    @Mock
    private AlertEngineService alertEngineService;

    @InjectMocks
    private AlertPoAmountThresholdListener listener;

    @Test
    @DisplayName("onPurchaseOrderCreated: 转发到 triggerAlert with PO context")
    void forwardsEventToTriggerAlert() {
        PurchaseOrderCreatedEvent event = new PurchaseOrderCreatedEvent(
                this, "F001", "po-1", "PO-20260518-0001",
                "supplier-1", new BigDecimal("75000"));
        when(alertEngineService.triggerAlert(any(), any(), any(), any(), any()))
                .thenReturn(java.util.List.of(java.util.UUID.randomUUID()));

        listener.onPurchaseOrderCreated(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(alertEngineService).triggerAlert(
                eq("F001"),
                eq(AlertType.PO_AMOUNT_THRESHOLD),
                eq("PURCHASE_ORDER"),
                eq("po-1"),
                captor.capture());
        Map<String, Object> context = captor.getValue();
        assertEquals(new BigDecimal("75000"), context.get("amount"));
        assertEquals("PO-20260518-0001", context.get("orderNumber"));
        assertEquals("supplier-1", context.get("supplierId"));
        assertNotNull(context.get("message"));
    }

    @Test
    @DisplayName("onPurchaseOrderCreated: 异常被吃掉, 不影响 publish 流")
    void swallowsExceptionsToAvoidBlockingBusiness() {
        PurchaseOrderCreatedEvent event = new PurchaseOrderCreatedEvent(
                this, "F001", "po-fail", "PO-X", "sup-X", new BigDecimal("10000"));
        when(alertEngineService.triggerAlert(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("simulated downstream failure"));

        // Should not propagate — listener catches Exception internally.
        listener.onPurchaseOrderCreated(event);
    }
}
