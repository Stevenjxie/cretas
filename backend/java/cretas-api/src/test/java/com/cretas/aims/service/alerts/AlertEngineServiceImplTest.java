package com.cretas.aims.service.alerts;

import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertSeverity;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.repository.alerts.AlertEventRepository;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.service.alerts.impl.AlertEngineServiceImpl;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AlertEngineServiceImpl 单元测试 — Phase 2 Canvas-Alerts.
 *
 * Coverage:
 * <ol>
 *   <li>triggerAlert — 无匹配规则 → empty list</li>
 *   <li>triggerAlert — SpEL true → 创建 AlertEvent</li>
 *   <li>triggerAlert — SpEL false → 不创建 event</li>
 *   <li>triggerAlert — 无 SpEL (默认 true) → 创建 event</li>
 *   <li>triggerAlert — dedup 命中 → 跳过</li>
 *   <li>acknowledge — OPEN → ACKNOWLEDGED</li>
 *   <li>acknowledge — 非 OPEN → throw IllegalStateException</li>
 *   <li>acknowledge — 事件不存在 → throw</li>
 *   <li>resolve — 任意状态 → RESOLVED</li>
 *   <li>resolve — 已 RESOLVED 幂等 (no-op)</li>
 *   <li>findEvents — status 过滤分页查询</li>
 *   <li>triggerAlert — multi rules 部分匹配</li>
 * </ol>
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@DisplayName("AlertEngineServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class AlertEngineServiceImplTest {

    @Mock
    private AlertRuleRepository ruleRepository;

    @Mock
    private AlertEventRepository eventRepository;

    @Mock
    private SandboxedSpelEvaluator spelEvaluator;

    @InjectMocks
    private AlertEngineServiceImpl service;

    private static final String FACTORY_ID = "F001";

    @BeforeEach
    void setUp() {
        // Default: eventRepository.save returns input with random UUID assigned.
        lenient().when(eventRepository.save(any(AlertEvent.class)))
                .thenAnswer(inv -> {
                    AlertEvent input = inv.getArgument(0);
                    if (input.getId() == null) input.setId(UUID.randomUUID());
                    return input;
                });
        // Default: dedup repo returns empty list (no duplicates).
        lenient().when(eventRepository
                .findByRuleIdAndBusinessEntityIdAndStatusInAndCreatedAtAfter(
                        any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
    }

    // ==================== triggerAlert ====================

    @Test
    @DisplayName("triggerAlert: 无匹配规则 → 返空 list, 不调 save")
    void triggerAlert_noMatchingRules_returnsEmpty() {
        when(ruleRepository.findByFactoryIdAndAlertTypeAndEnabledTrue(FACTORY_ID, AlertType.PO_AMOUNT_THRESHOLD))
                .thenReturn(Collections.emptyList());

        List<UUID> result = service.triggerAlert(
                FACTORY_ID, AlertType.PO_AMOUNT_THRESHOLD,
                "PURCHASE_ORDER", "po-1",
                Map.of("amount", new BigDecimal("100000")));

        assertTrue(result.isEmpty(), "no matching rules → empty list");
        verify(eventRepository, never()).save(any(AlertEvent.class));
    }

    @Test
    @DisplayName("triggerAlert: SpEL true → 创建 AlertEvent, 返非空 ids")
    void triggerAlert_spelTrue_createsEvent() {
        AlertRule rule = buildRule(AlertType.PO_AMOUNT_THRESHOLD,
                "#context.amount >= 50000", AlertSeverity.HIGH);
        when(ruleRepository.findByFactoryIdAndAlertTypeAndEnabledTrue(FACTORY_ID, AlertType.PO_AMOUNT_THRESHOLD))
                .thenReturn(List.of(rule));
        when(spelEvaluator.evaluateBoolean(eq("#context.amount >= 50000"), any())).thenReturn(true);

        List<UUID> result = service.triggerAlert(
                FACTORY_ID, AlertType.PO_AMOUNT_THRESHOLD,
                "PURCHASE_ORDER", "po-1",
                Map.of("amount", new BigDecimal("100000")));

        assertEquals(1, result.size(), "1 rule matched → 1 event");

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(eventRepository).save(captor.capture());
        AlertEvent saved = captor.getValue();
        assertEquals(FACTORY_ID, saved.getFactoryId());
        assertEquals(AlertSeverity.HIGH, saved.getSeverity());
        assertEquals(AlertEventStatus.OPEN, saved.getStatus());
        assertEquals(rule.getId(), saved.getRuleId());
        assertEquals("PURCHASE_ORDER", saved.getBusinessEntityType());
        assertEquals("po-1", saved.getBusinessEntityId());
        assertNotNull(saved.getMessage());
    }

    @Test
    @DisplayName("triggerAlert: SpEL false → 不创建 event")
    void triggerAlert_spelFalse_skips() {
        AlertRule rule = buildRule(AlertType.PO_AMOUNT_THRESHOLD,
                "#context.amount >= 50000", AlertSeverity.HIGH);
        when(ruleRepository.findByFactoryIdAndAlertTypeAndEnabledTrue(FACTORY_ID, AlertType.PO_AMOUNT_THRESHOLD))
                .thenReturn(List.of(rule));
        when(spelEvaluator.evaluateBoolean(any(), any())).thenReturn(false);

        List<UUID> result = service.triggerAlert(
                FACTORY_ID, AlertType.PO_AMOUNT_THRESHOLD,
                "PURCHASE_ORDER", "po-1",
                Map.of("amount", new BigDecimal("100")));

        assertTrue(result.isEmpty(), "SpEL false → no events");
        verify(eventRepository, never()).save(any(AlertEvent.class));
    }

    @Test
    @DisplayName("triggerAlert: 无 SpEL (null) → 默认 true, 创建 event")
    void triggerAlert_noSpel_defaultsTrue() {
        AlertRule rule = buildRule(AlertType.INVENTORY_LOW, null, AlertSeverity.MID);
        when(ruleRepository.findByFactoryIdAndAlertTypeAndEnabledTrue(FACTORY_ID, AlertType.INVENTORY_LOW))
                .thenReturn(List.of(rule));

        List<UUID> result = service.triggerAlert(
                FACTORY_ID, AlertType.INVENTORY_LOW,
                "MATERIAL", "mat-1", Map.of("currentStock", new BigDecimal("5")));

        assertEquals(1, result.size(), "no SpEL → fire default");
        verify(eventRepository).save(any(AlertEvent.class));
        verify(spelEvaluator, never()).evaluateBoolean(any(), any());
    }

    @Test
    @DisplayName("triggerAlert: dedup 窗口命中 → 跳过, 不创建 event")
    void triggerAlert_dedupHit_skips() {
        AlertRule rule = buildRule(AlertType.PO_AMOUNT_THRESHOLD,
                "#context.amount >= 100", AlertSeverity.MID);
        when(ruleRepository.findByFactoryIdAndAlertTypeAndEnabledTrue(FACTORY_ID, AlertType.PO_AMOUNT_THRESHOLD))
                .thenReturn(List.of(rule));
        when(spelEvaluator.evaluateBoolean(any(), any())).thenReturn(true);

        // Simulate: a recent OPEN event exists for same (rule, entity).
        AlertEvent existing = AlertEvent.builder()
                .id(UUID.randomUUID())
                .ruleId(rule.getId())
                .factoryId(FACTORY_ID)
                .businessEntityId("po-dup")
                .status(AlertEventStatus.OPEN)
                .severity(AlertSeverity.MID)
                .message("existing")
                .build();
        when(eventRepository
                .findByRuleIdAndBusinessEntityIdAndStatusInAndCreatedAtAfter(
                        eq(rule.getId()), eq("po-dup"), any(), any()))
                .thenReturn(List.of(existing));

        List<UUID> result = service.triggerAlert(
                FACTORY_ID, AlertType.PO_AMOUNT_THRESHOLD,
                "PURCHASE_ORDER", "po-dup",
                Map.of("amount", new BigDecimal("1000")));

        assertTrue(result.isEmpty(), "dedup hit → no new event");
        verify(eventRepository, never()).save(any(AlertEvent.class));
    }

    @Test
    @DisplayName("triggerAlert: 多个规则部分匹配 → 仅创建匹配 events")
    void triggerAlert_multiRules_partialMatch() {
        AlertRule rule1 = buildRule(AlertType.PO_AMOUNT_THRESHOLD,
                "#context.amount >= 100000", AlertSeverity.HIGH);
        AlertRule rule2 = buildRule(AlertType.PO_AMOUNT_THRESHOLD,
                "#context.amount >= 50000", AlertSeverity.MID);
        when(ruleRepository.findByFactoryIdAndAlertTypeAndEnabledTrue(FACTORY_ID, AlertType.PO_AMOUNT_THRESHOLD))
                .thenReturn(List.of(rule1, rule2));
        // rule1 SpEL false, rule2 SpEL true.
        when(spelEvaluator.evaluateBoolean(eq("#context.amount >= 100000"), any())).thenReturn(false);
        when(spelEvaluator.evaluateBoolean(eq("#context.amount >= 50000"), any())).thenReturn(true);

        List<UUID> result = service.triggerAlert(
                FACTORY_ID, AlertType.PO_AMOUNT_THRESHOLD,
                "PURCHASE_ORDER", "po-1",
                Map.of("amount", new BigDecimal("60000")));

        assertEquals(1, result.size(), "only rule2 matched");
        verify(eventRepository, times(1)).save(any(AlertEvent.class));
    }

    @Test
    @DisplayName("triggerAlert: factoryId null → 静默返 empty, 不抛")
    void triggerAlert_nullFactoryId_returnsEmpty() {
        List<UUID> result = service.triggerAlert(
                null, AlertType.PO_AMOUNT_THRESHOLD, "X", "x-1", Map.of());
        assertTrue(result.isEmpty());
        verifyNoInteractions(ruleRepository);
    }

    // ==================== acknowledge ====================

    @Test
    @DisplayName("acknowledge: OPEN → ACKNOWLEDGED, 填责任人 + 时间")
    void acknowledge_openEvent_setsAckedFields() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = AlertEvent.builder()
                .id(eventId)
                .ruleId(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .status(AlertEventStatus.OPEN)
                .severity(AlertSeverity.MID)
                .message("test")
                .build();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        AlertEvent result = service.acknowledge(eventId, 99L);

        assertEquals(AlertEventStatus.ACKNOWLEDGED, result.getStatus());
        assertEquals(99L, result.getAckedByUserId());
        assertNotNull(result.getAckedAt());
        verify(eventRepository).save(event);
    }

    @Test
    @DisplayName("acknowledge: 非 OPEN → throw IllegalStateException")
    void acknowledge_alreadyAcknowledged_throws() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = AlertEvent.builder()
                .id(eventId)
                .factoryId(FACTORY_ID)
                .status(AlertEventStatus.ACKNOWLEDGED)
                .severity(AlertSeverity.MID)
                .message("test")
                .build();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        assertThrows(IllegalStateException.class,
                () -> service.acknowledge(eventId, 99L));
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("acknowledge: 事件不存在 → throw IllegalArgumentException")
    void acknowledge_eventNotFound_throws() {
        UUID eventId = UUID.randomUUID();
        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.acknowledge(eventId, 99L));
    }

    // ==================== resolve ====================

    @Test
    @DisplayName("resolve: OPEN → RESOLVED, 填解决人 + 时间")
    void resolve_openEvent_setsResolvedFields() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = AlertEvent.builder()
                .id(eventId)
                .factoryId(FACTORY_ID)
                .status(AlertEventStatus.OPEN)
                .severity(AlertSeverity.MID)
                .message("test")
                .build();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        AlertEvent result = service.resolve(eventId, 77L);

        assertEquals(AlertEventStatus.RESOLVED, result.getStatus());
        assertEquals(77L, result.getResolvedByUserId());
        assertNotNull(result.getResolvedAt());
        verify(eventRepository).save(event);
    }

    @Test
    @DisplayName("resolve: 已 RESOLVED → 幂等 no-op (不报错)")
    void resolve_alreadyResolved_isNoop() {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = AlertEvent.builder()
                .id(eventId)
                .factoryId(FACTORY_ID)
                .status(AlertEventStatus.RESOLVED)
                .resolvedByUserId(11L)
                .resolvedAt(LocalDateTime.now().minusHours(1))
                .severity(AlertSeverity.MID)
                .message("test")
                .build();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        AlertEvent result = service.resolve(eventId, 77L);

        assertEquals(AlertEventStatus.RESOLVED, result.getStatus());
        // resolvedByUserId 不变 (no-op)
        assertEquals(11L, result.getResolvedByUserId());
        verify(eventRepository, never()).save(any());
    }

    // ==================== findEvents ====================

    @Test
    @DisplayName("findEvents: status null → 不过滤")
    void findEvents_nullStatus_callsNoStatusOverload() {
        service.findEvents(FACTORY_ID, null, org.springframework.data.domain.PageRequest.of(0, 10));
        verify(eventRepository).findByFactoryIdOrderByCreatedAtDesc(eq(FACTORY_ID), any());
        verify(eventRepository, never())
                .findByFactoryIdAndStatusOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    @DisplayName("findEvents: status OPEN → 按 status 过滤")
    void findEvents_openStatus_filtersOpen() {
        service.findEvents(FACTORY_ID, AlertEventStatus.OPEN,
                org.springframework.data.domain.PageRequest.of(0, 10));
        verify(eventRepository).findByFactoryIdAndStatusOrderByCreatedAtDesc(
                eq(FACTORY_ID), eq(AlertEventStatus.OPEN), any());
    }

    @Test
    @DisplayName("findEvents: factoryId null → throws")
    void findEvents_nullFactoryId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.findEvents(null, null,
                        org.springframework.data.domain.PageRequest.of(0, 10)));
    }

    // ==================== evaluateScheduled ====================

    @Test
    @DisplayName("evaluateScheduled: factoryId 给值 → 不抛, 不创建事件 (per Phase 2 stub)")
    void evaluateScheduled_phaseStub_returnsEmpty() {
        when(ruleRepository.findByFactoryIdAndEnabledTrue(FACTORY_ID))
                .thenReturn(Collections.emptyList());
        List<UUID> result = service.evaluateScheduled(FACTORY_ID);
        assertTrue(result.isEmpty());
    }

    // ==================== helpers ====================

    private AlertRule buildRule(AlertType type, String spel, AlertSeverity sev) {
        return AlertRule.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .alertType(type)
                .ruleName("test-rule-" + type)
                .triggerConditionSpel(spel)
                .severity(sev)
                .enabled(true)
                .build();
    }
}
