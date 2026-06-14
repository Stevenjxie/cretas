package com.cretas.aims.service.alerts;

import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.event.InventoryStockChangedEvent;
import com.cretas.aims.service.alerts.listener.AlertInventoryLowListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;

/**
 * AlertInventoryLowListener 单元测试 — Phase 2 Canvas-Alerts.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@DisplayName("AlertInventoryLowListener 单元测试")
@ExtendWith(MockitoExtension.class)
class AlertInventoryLowListenerTest {

    @Mock
    private AlertEngineService alertEngineService;

    @Mock
    private com.cretas.aims.service.alerts.LowStockDualAlertService lowStockDualAlertService;

    @InjectMocks
    private AlertInventoryLowListener listener;

    @Test
    @DisplayName("onInventoryStockChanged: 转发到 triggerAlert + 包 message")
    void forwardsAndIncludesPrettyMessage() {
        InventoryStockChangedEvent event = new InventoryStockChangedEvent(
                this, "F001", "mat-1", "冻猪蹄",
                new BigDecimal("25"), new BigDecimal("30"), "kg", "OUT");
        when(alertEngineService.triggerAlert(any(), any(), any(), any(), any()))
                .thenReturn(java.util.List.of(java.util.UUID.randomUUID()));

        listener.onInventoryStockChanged(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(alertEngineService).triggerAlert(
                eq("F001"),
                eq(AlertType.INVENTORY_LOW),
                eq("MATERIAL"),
                eq("mat-1"),
                captor.capture());
        Map<String, Object> context = captor.getValue();
        assertEquals(new BigDecimal("25"), context.get("currentStock"));
        assertEquals(new BigDecimal("30"), context.get("minStockLevel"));
        assertEquals("冻猪蹄", context.get("materialName"));
        String message = (String) context.get("message");
        assertNotNull(message);
        assertTrue(message.contains("冻猪蹄"));
        assertTrue(message.contains("25"));
        assertTrue(message.contains("30"));
    }

    @Test
    @DisplayName("F-034: onInventoryStockChanged 调用 LowStockDualAlertService.checkAndNotify")
    void callsLowStockDualAlertService() {
        InventoryStockChangedEvent event = new InventoryStockChangedEvent(
                this, "F006", "mat-2", "猪舌",
                new BigDecimal("15"), new BigDecimal("40"), "kg", "OUT");
        when(alertEngineService.triggerAlert(any(), any(), any(), any(), any()))
                .thenReturn(java.util.List.of());

        listener.onInventoryStockChanged(event);

        verify(lowStockDualAlertService).checkAndNotify(
                eq("F006"), eq("mat-2"),
                eq(new BigDecimal("15")), eq(new BigDecimal("40")), eq("kg"));
    }

    @Test
    @DisplayName("F-034: listener side effects run only AFTER_COMMIT")
    void listenerRunsAfterCommit() throws Exception {
        Method method = AlertInventoryLowListener.class
                .getDeclaredMethod("onInventoryStockChanged", InventoryStockChangedEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    }
}
