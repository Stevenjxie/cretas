package com.cretas.aims.service.rules;

import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.entity.rules.RuleActionType;
import com.cretas.aims.entity.rules.RuleExecutionLog;
import com.cretas.aims.entity.rules.RuleScope;
import com.cretas.aims.repository.rules.BusinessRuleRepository;
import com.cretas.aims.repository.rules.RuleExecutionLogRepository;
import com.cretas.aims.service.rules.impl.RuleEngineImpl;
import com.cretas.aims.service.rules.integration.WorkflowEngineFacade;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link RuleEngine} acceptance criteria §8.2-§8.4.
 *
 * <p>Wires the <b>real</b> {@link RuleEngineImpl} + <b>real</b> {@link SandboxedSpelEvaluator}
 * + <b>real</b> {@link WorkflowEngineFacade}, with Mockito repositories — avoiding the
 * full {@code @SpringBootTest} fan-out (AI bean graph / DashScope / gRPC) while still
 * exercising actual SpEL evaluation, Spring {@link org.springframework.beans.BeanWrapper}
 * reflection, and audit-log construction logic.
 *
 * <p>This complements {@link com.cretas.aims.service.rules.impl.RuleEngineImplTest}
 * (which mocks the SpelEvaluator spy) by using the real SpEL parser, so any change
 * to expression semantics surfaces here.
 *
 * @author Cretas Team
 * @since 2026-05-18
 */
@DisplayName("RuleEngine integration — REJECT short-circuit + MODIFY chain + audit persistence")
class RuleEngineIntegrationTest {

    @Data
    static class OrderInput {
        private String orderNumber;
        private BigDecimal totalAmount;
        private BigDecimal discount;
    }

    private BusinessRuleRepository ruleRepo;
    private RuleExecutionLogRepository logRepo;
    private RuleEngine engine;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(BusinessRuleRepository.class);
        logRepo = mock(RuleExecutionLogRepository.class);
        engine = new RuleEngineImpl(ruleRepo, logRepo,
                new WorkflowEngineFacade(), new SandboxedSpelEvaluator());
    }

    private BusinessRule rule(UUID id, String code, int priority, RuleActionType type,
                              String spel, Map<String, Object> cfg) {
        return BusinessRule.builder()
                .id(id)
                .factoryId("F001")
                .ruleCode(code)
                .ruleName(code)
                .scope(RuleScope.ORDER)
                .conditionSpel(spel)
                .actionType(type)
                .actionConfigJson(cfg == null ? new LinkedHashMap<>() : new LinkedHashMap<>(cfg))
                .priority(priority)
                .enabled(true)
                .build();
    }

    @Test
    @DisplayName("§8.2 REJECT + audit: high-value PO triggers REJECT, log row persisted, downstream rule skipped")
    void reject_and_audit_persisted() {
        UUID rejectId = UUID.randomUUID();
        BusinessRule reject = rule(rejectId, "po_high_amount_reject", 10, RuleActionType.REJECT,
                "#input.totalAmount > 100000",
                Map.of("reason", "订单金额超过 10 万 — 需要 CFO 特批",
                       "actionHint", "/finance/special-approval"));
        BusinessRule lowerPriorityLog = rule(UUID.randomUUID(), "po_log_high", 100, RuleActionType.LOG,
                "#input.totalAmount > 50000",
                Map.of("level", "INFO", "message", "high value PO"));
        when(ruleRepo.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                "F001", RuleScope.ORDER)).thenReturn(List.of(reject, lowerPriorityLog));

        OrderInput order = new OrderInput();
        order.setOrderNumber("PO-INT-001");
        order.setTotalAmount(new BigDecimal("200000"));

        RuleEvaluationResult result = engine.evaluate("F001", RuleScope.ORDER, order);

        assertTrue(result.shouldReject(),
                "REJECT rule must short-circuit: shouldReject=true");
        assertEquals("po_high_amount_reject", result.rejectRuleCode());
        assertEquals("订单金额超过 10 万 — 需要 CFO 特批", result.rejectReason());
        assertEquals(1, result.executedRules().size(),
                "Lower-priority LOG rule must NOT execute after REJECT (priority short-circuit)");

        // §8.4 audit: only the rejecting rule writes a log row
        ArgumentCaptor<RuleExecutionLog> logCap = ArgumentCaptor.forClass(RuleExecutionLog.class);
        verify(logRepo, times(1)).save(logCap.capture());
        RuleExecutionLog logEntry = logCap.getValue();
        assertEquals(rejectId, logEntry.getRuleId());
        assertEquals("F001", logEntry.getFactoryId());
        assertEquals("REJECT", logEntry.getResultJson().get("action"));
        assertEquals("订单金额超过 10 万 — 需要 CFO 特批", logEntry.getResultJson().get("reason"));
        assertNotNull(logEntry.getExecutedAt());
        assertNotNull(logEntry.getInputJson());
        assertEquals("PO-INT-001", logEntry.getInputJson().get("orderNumber"),
                "Input sanitization should retain primitive fields like orderNumber");
    }

    @Test
    @DisplayName("§8.3 MODIFY + audit: VIP order discount applied via real BeanWrapper, log captures old + new")
    void modify_and_audit_persisted() {
        BusinessRule modify = rule(UUID.randomUUID(), "vip_5pct_discount", 50, RuleActionType.MODIFY,
                "#input.totalAmount > 50000",
                Map.of("field", "discount", "value", new BigDecimal("0.05")));
        when(ruleRepo.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                "F001", RuleScope.ORDER)).thenReturn(List.of(modify));

        OrderInput order = new OrderInput();
        order.setOrderNumber("SO-VIP-001");
        order.setTotalAmount(new BigDecimal("80000"));
        order.setDiscount(BigDecimal.ZERO);

        RuleEvaluationResult result = engine.evaluate("F001", RuleScope.ORDER, order);

        assertFalse(result.shouldReject());
        assertEquals(new BigDecimal("0.05"), order.getDiscount(),
                "MODIFY must reflectively set field on inputObject — caller observes change");

        ArgumentCaptor<RuleExecutionLog> logCap = ArgumentCaptor.forClass(RuleExecutionLog.class);
        verify(logRepo, times(1)).save(logCap.capture());
        Map<String, Object> rj = logCap.getValue().getResultJson();
        assertEquals("MODIFY", rj.get("action"));
        assertEquals("discount", rj.get("field"));
        assertEquals(BigDecimal.ZERO, rj.get("oldValue"));
        assertEquals(new BigDecimal("0.05"), rj.get("newValue"));
    }

    @Test
    @DisplayName("§8.4 audit: condition error on rule writes CONDITION_ERROR row, doesn't block other rules")
    void condition_error_persists_audit_row() {
        BusinessRule bad = rule(UUID.randomUUID(), "bad_cond", 10, RuleActionType.LOG,
                "#input.nonexistentField == true", Map.of());
        BusinessRule good = rule(UUID.randomUUID(), "good_cond", 20, RuleActionType.LOG,
                "#input.totalAmount > 10000", Map.of("message", "ok"));
        when(ruleRepo.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                "F001", RuleScope.ORDER)).thenReturn(List.of(bad, good));

        OrderInput order = new OrderInput();
        order.setOrderNumber("SO-E");
        order.setTotalAmount(new BigDecimal("20000"));

        RuleEvaluationResult result = engine.evaluate("F001", RuleScope.ORDER, order);

        assertFalse(result.shouldReject());
        assertEquals(1, result.executedRules().size(),
                "Only the 'good' rule actually executes — bad one is skipped");
        // Both rules write audit rows: bad → CONDITION_ERROR, good → LOG
        ArgumentCaptor<RuleExecutionLog> logCap = ArgumentCaptor.forClass(RuleExecutionLog.class);
        verify(logRepo, times(2)).save(logCap.capture());
        List<RuleExecutionLog> persisted = logCap.getAllValues();
        boolean sawConditionError = persisted.stream()
                .anyMatch(l -> "CONDITION_ERROR".equals(l.getResultJson().get("status")));
        boolean sawLogAction = persisted.stream()
                .anyMatch(l -> "LOG".equals(l.getResultJson().get("action")));
        assertTrue(sawConditionError, "bad rule must write a CONDITION_ERROR audit row");
        assertTrue(sawLogAction, "good rule must write its LOG audit row");
    }
}
