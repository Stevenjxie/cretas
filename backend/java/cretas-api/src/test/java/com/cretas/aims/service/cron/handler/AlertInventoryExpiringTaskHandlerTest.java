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
 * Unit tests for {@link AlertInventoryExpiringTaskHandler}.
 *
 * @since 2026-05-21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AlertInventoryExpiringTaskHandler 单元测试")
class AlertInventoryExpiringTaskHandlerTest {

    @Mock
    private AlertRuleRepository ruleRepository;

    @Mock
    private AlertEngineService alertEngineService;

    @InjectMocks
    private AlertInventoryExpiringTaskHandler handler;

    private AlertRule rule(String factoryId, AlertType type, boolean enabled) {
        AlertRule r = AlertRule.builder().build();
        r.setId(UUID.randomUUID());
        r.setFactoryId(factoryId);
        r.setAlertType(type);
        r.setEnabled(enabled);
        return r;
    }

    @Test
    @DisplayName("execute: 只扫 INVENTORY_EXPIRING enabled 规则")
    void filtersToInventoryExpiringEnabledOnly() {
        when(ruleRepository.findAll()).thenReturn(List.of(
                rule("F001", AlertType.INVENTORY_EXPIRING, true),
                rule("F002", AlertType.INVENTORY_EXPIRING, false),
                rule("F003", AlertType.INVENTORY_LOW, true)
        ));

        Map<String, Object> ctx = new HashMap<>();
        handler.execute(ctx);

        assertEquals(1, ctx.get("scannedFactories"));
    }

    @Test
    @DisplayName("execute: 空 rule 列表时 scannedFactories=0")
    void zeroScannedWhenNoMatchingRules() {
        when(ruleRepository.findAll()).thenReturn(List.of(
                rule("F001", AlertType.INVENTORY_LOW, true)
        ));

        Map<String, Object> ctx = new HashMap<>();
        handler.execute(ctx);

        assertEquals(0, ctx.get("scannedFactories"));
    }
}
