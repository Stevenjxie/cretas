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
 * Unit tests for {@link AlertSupplierPayableDueTaskHandler}.
 *
 * @since 2026-05-21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AlertSupplierPayableDueTaskHandler 单元测试")
class AlertSupplierPayableDueTaskHandlerTest {

    @Mock
    private AlertRuleRepository ruleRepository;

    @Mock
    private AlertEngineService alertEngineService;

    @InjectMocks
    private AlertSupplierPayableDueTaskHandler handler;

    private AlertRule rule(String factoryId, AlertType type, boolean enabled) {
        AlertRule r = AlertRule.builder().build();
        r.setId(UUID.randomUUID());
        r.setFactoryId(factoryId);
        r.setAlertType(type);
        r.setEnabled(enabled);
        return r;
    }

    @Test
    @DisplayName("execute: 全扫 SUPPLIER_PAYABLE_DUE enabled 规则")
    void scansSupplierPayableDueRules() {
        when(ruleRepository.findAll()).thenReturn(List.of(
                rule("F001", AlertType.SUPPLIER_PAYABLE_DUE, true),
                rule("F002", AlertType.SUPPLIER_PAYABLE_DUE, true),
                rule("F003", AlertType.SUPPLIER_PAYABLE_DUE, false)  // disabled
        ));

        Map<String, Object> ctx = new HashMap<>();
        handler.execute(ctx);

        assertEquals(2, ctx.get("scannedFactories"));
    }

    @Test
    @DisplayName("execute: repository 异常被 wrap 成 SUPPLIER_PAYABLE_DUE 错误信息")
    void wrapsRepositoryError() {
        when(ruleRepository.findAll()).thenThrow(new RuntimeException("conn timeout"));

        Map<String, Object> ctx = new HashMap<>();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(ctx));
        assertTrue(ex.getMessage().contains("SUPPLIER_PAYABLE_DUE scan failed"));
    }
}
