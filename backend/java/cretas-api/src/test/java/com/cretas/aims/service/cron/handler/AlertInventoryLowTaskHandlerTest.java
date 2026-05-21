package com.cretas.aims.service.cron.handler;

import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.service.alerts.AlertEngineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AlertInventoryLowTaskHandler}.
 *
 * <p>Verifies the handler can be driven from Canvas-Cron context, scopes by
 * factoryId when provided, and writes scannedFactories back to the context map.
 *
 * @since 2026-05-21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AlertInventoryLowTaskHandler 单元测试")
class AlertInventoryLowTaskHandlerTest {

    @Mock
    private AlertRuleRepository ruleRepository;

    @Mock
    private AlertEngineService alertEngineService;

    @InjectMocks
    private AlertInventoryLowTaskHandler handler;

    private AlertRule rule(String factoryId, AlertType type, boolean enabled) {
        AlertRule r = AlertRule.builder().build();
        r.setId(UUID.randomUUID());
        r.setFactoryId(factoryId);
        r.setAlertType(type);
        r.setEnabled(enabled);
        return r;
    }

    @Test
    @DisplayName("execute: 多工厂规则全部扫描时返回 scannedFactories=N")
    void scansAllFactories_whenNoScopeProvided() {
        when(ruleRepository.findAll()).thenReturn(List.of(
                rule("F001", AlertType.INVENTORY_LOW, true),
                rule("F002", AlertType.INVENTORY_LOW, true),
                rule("F003", AlertType.INVENTORY_LOW, false),  // disabled — skipped
                rule("F004", AlertType.INVENTORY_EXPIRING, true) // wrong type — skipped
        ));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("taskCode", "alert_inventory_low_scan");

        assertDoesNotThrow(() -> handler.execute(ctx));
        assertEquals(2, ctx.get("scannedFactories"));
    }

    @Test
    @DisplayName("execute: Canvas-Cron 传 factoryId 时只扫该工厂")
    void scopesToSingleFactory_whenFactoryIdInContext() {
        when(ruleRepository.findAll()).thenReturn(List.of(
                rule("F001", AlertType.INVENTORY_LOW, true),
                rule("F002", AlertType.INVENTORY_LOW, true)
        ));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", "F001");

        handler.execute(ctx);
        assertEquals(1, ctx.get("scannedFactories"));
    }

    @Test
    @DisplayName("execute: ruleRepository 抛异常时包装为 RuntimeException")
    void wrapsRepositoryFailureAsRuntimeException() {
        when(ruleRepository.findAll()).thenThrow(new RuntimeException("DB down"));

        Map<String, Object> ctx = new HashMap<>();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(ctx));
        assertTrue(ex.getMessage().contains("INVENTORY_LOW scan failed"));
    }
}
