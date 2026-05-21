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
 * Unit tests for {@link AlertCustomerPaymentOverdueTaskHandler}.
 *
 * @since 2026-05-21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AlertCustomerPaymentOverdueTaskHandler 单元测试")
class AlertCustomerPaymentOverdueTaskHandlerTest {

    @Mock
    private AlertRuleRepository ruleRepository;

    @Mock
    private AlertEngineService alertEngineService;

    @InjectMocks
    private AlertCustomerPaymentOverdueTaskHandler handler;

    private AlertRule rule(String factoryId, AlertType type, boolean enabled) {
        AlertRule r = AlertRule.builder().build();
        r.setId(UUID.randomUUID());
        r.setFactoryId(factoryId);
        r.setAlertType(type);
        r.setEnabled(enabled);
        return r;
    }

    @Test
    @DisplayName("execute: 只匹配 CUSTOMER_PAYMENT_OVERDUE 类型 enabled")
    void filtersToOverdueRulesOnly() {
        when(ruleRepository.findAll()).thenReturn(List.of(
                rule("F001", AlertType.CUSTOMER_PAYMENT_OVERDUE, true),
                rule("F002", AlertType.CUSTOMER_PAYMENT_OVERDUE, true),
                rule("F003", AlertType.SUPPLIER_PAYABLE_DUE, true)
        ));

        Map<String, Object> ctx = new HashMap<>();
        handler.execute(ctx);

        assertEquals(2, ctx.get("scannedFactories"));
    }

    @Test
    @DisplayName("execute: factoryId scope 限定单工厂扫描")
    void scopesToFactoryFromContext() {
        when(ruleRepository.findAll()).thenReturn(List.of(
                rule("F001", AlertType.CUSTOMER_PAYMENT_OVERDUE, true),
                rule("F002", AlertType.CUSTOMER_PAYMENT_OVERDUE, true)
        ));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", "F002");

        handler.execute(ctx);
        assertEquals(1, ctx.get("scannedFactories"));
    }
}
