package com.cretas.aims.service.alerts;

import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertSeverity;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.event.InventoryStockChangedEvent;
import com.cretas.aims.repository.alerts.AlertEventRepository;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.service.alerts.impl.AlertEngineServiceImpl;
import com.cretas.aims.service.alerts.listener.AlertInventoryLowListener;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration test — Real {@link SandboxedSpelEvaluator} + Real AlertEngineServiceImpl
 * with mocked repositories. Verifies that a published
 * {@link InventoryStockChangedEvent} flows through the listener into a saved
 * AlertEvent when the rule's SpEL expression evaluates to true.
 *
 * <p>This is the "1 alert type smoke test" required by spec §7 acceptance
 * criterion 2 (event-driven trigger working).
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@DisplayName("AlertEngine SpEL integration smoke")
@ExtendWith(MockitoExtension.class)
class AlertEngineSpelIntegrationTest {

    @Mock
    private AlertRuleRepository ruleRepository;

    @Mock
    private AlertEventRepository eventRepository;

    private AlertEngineServiceImpl engine;
    private AlertInventoryLowListener listener;

    @BeforeEach
    void setUp() {
        SandboxedSpelEvaluator realEvaluator = new SandboxedSpelEvaluator();
        engine = new AlertEngineServiceImpl();
        ReflectionTestUtils.setField(engine, "ruleRepository", ruleRepository);
        ReflectionTestUtils.setField(engine, "eventRepository", eventRepository);
        ReflectionTestUtils.setField(engine, "spelEvaluator", realEvaluator);

        listener = new AlertInventoryLowListener();
        ReflectionTestUtils.setField(listener, "alertEngineService", engine);

        org.mockito.Mockito.lenient().when(eventRepository.save(any(AlertEvent.class))).thenAnswer(inv -> {
            AlertEvent input = inv.getArgument(0);
            if (input.getId() == null) input.setId(UUID.randomUUID());
            return input;
        });
        org.mockito.Mockito.lenient().when(eventRepository
                .findByRuleIdAndBusinessEntityIdAndStatusInAndCreatedAtAfter(
                        any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("Real SpEL — INVENTORY_LOW rule (currentStock < minStockLevel) → fire alert")
    void inventoryLowRule_realSpel_firesAlert() {
        AlertRule rule = AlertRule.builder()
                .id(UUID.randomUUID())
                .factoryId("F001")
                .alertType(AlertType.INVENTORY_LOW)
                .ruleName("低库存预警")
                .triggerConditionSpel("#currentStock < #minStockLevel")
                .severity(AlertSeverity.HIGH)
                .enabled(true)
                .build();
        when(ruleRepository.findByFactoryIdAndAlertTypeAndEnabledTrue("F001", AlertType.INVENTORY_LOW))
                .thenReturn(List.of(rule));

        InventoryStockChangedEvent event = new InventoryStockChangedEvent(
                this, "F001", "mat-1", "冻猪蹄",
                new BigDecimal("25"), new BigDecimal("30"), "kg", "OUT");

        listener.onInventoryStockChanged(event);

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        org.mockito.Mockito.verify(eventRepository).save(captor.capture());
        AlertEvent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AlertEventStatus.OPEN);
        assertThat(saved.getSeverity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(saved.getFactoryId()).isEqualTo("F001");
        assertThat(saved.getBusinessEntityType()).isEqualTo("MATERIAL");
        assertThat(saved.getBusinessEntityId()).isEqualTo("mat-1");
        assertThat(saved.getMessage()).contains("冻猪蹄").contains("25").contains("30");
    }

    @Test
    @DisplayName("Real SpEL — currentStock >= minStockLevel → no alert")
    void inventoryAboveThreshold_noAlert() {
        AlertRule rule = AlertRule.builder()
                .id(UUID.randomUUID())
                .factoryId("F001")
                .alertType(AlertType.INVENTORY_LOW)
                .ruleName("低库存预警")
                .triggerConditionSpel("#currentStock < #minStockLevel")
                .severity(AlertSeverity.HIGH)
                .enabled(true)
                .build();
        when(ruleRepository.findByFactoryIdAndAlertTypeAndEnabledTrue("F001", AlertType.INVENTORY_LOW))
                .thenReturn(List.of(rule));

        InventoryStockChangedEvent event = new InventoryStockChangedEvent(
                this, "F001", "mat-1", "冻猪蹄",
                new BigDecimal("50"), new BigDecimal("30"), "kg", "IN");

        listener.onInventoryStockChanged(event);

        org.mockito.Mockito.verify(eventRepository, org.mockito.Mockito.never())
                .save(any(AlertEvent.class));
    }

    @Test
    @DisplayName("Real SpEL — invalid SpEL (RCE attempt) safely fails, no event")
    void invalidSpelInvocation_failsClosed() {
        AlertRule rule = AlertRule.builder()
                .id(UUID.randomUUID())
                .factoryId("F001")
                .alertType(AlertType.INVENTORY_LOW)
                .ruleName("malicious-rule")
                // Attempt T() reference — should be rejected by SandboxedSpelEvaluator.
                .triggerConditionSpel("T(java.lang.Runtime).getRuntime() != null")
                .severity(AlertSeverity.HIGH)
                .enabled(true)
                .build();
        when(ruleRepository.findByFactoryIdAndAlertTypeAndEnabledTrue("F001", AlertType.INVENTORY_LOW))
                .thenReturn(List.of(rule));

        InventoryStockChangedEvent event = new InventoryStockChangedEvent(
                this, "F001", "mat-1", "TestMat",
                new BigDecimal("1"), new BigDecimal("100"), "kg", "OUT");

        // Listener swallows the SpEL exception so business flow is not blocked.
        listener.onInventoryStockChanged(event);
        org.mockito.Mockito.verify(eventRepository, org.mockito.Mockito.never())
                .save(any(AlertEvent.class));
    }
}
