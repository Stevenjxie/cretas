package com.cretas.aims.service.rules.impl;

import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.entity.rules.RuleActionType;
import com.cretas.aims.entity.rules.RuleExecutionLog;
import com.cretas.aims.entity.rules.RuleScope;
import com.cretas.aims.repository.rules.BusinessRuleRepository;
import com.cretas.aims.repository.rules.RuleExecutionLogRepository;
import com.cretas.aims.service.rules.RuleEvaluationResult;
import com.cretas.aims.service.rules.RuleModification;
import com.cretas.aims.service.rules.integration.WorkflowEngineFacade;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RuleEngineImpl} — Canvas-Rules Phase 4a §2.
 *
 * <p>Covers all 4 action types + priority short-circuit + condition error tolerance.
 */
@DisplayName("RuleEngineImpl Phase 4a 4 actions + priority + audit")
@ExtendWith(MockitoExtension.class)
class RuleEngineImplTest {

    @Mock private BusinessRuleRepository ruleRepository;
    @Mock private RuleExecutionLogRepository logRepository;
    @Mock private WorkflowEngineFacade workflowEngineFacade;

    /** Real SpEL evaluator — sandboxed but functional, to actually test SpEL behavior. */
    @Spy private SandboxedSpelEvaluator spelEvaluator = new SandboxedSpelEvaluator();

    @InjectMocks private RuleEngineImpl engine;

    private static final String FACTORY_ID = "F001";

    @Data
    static class OrderInput {
        private String orderNumber;
        private BigDecimal totalAmount;
        private BigDecimal discount;
        private String materialId;
        private boolean blacklisted;
        /** Phase 4a post-review I3: #input.id auto-fill for TRIGGER_WORKFLOW businessEntityId. */
        private String id;
    }

    private OrderInput sampleOrder(String num, BigDecimal amount) {
        OrderInput o = new OrderInput();
        o.setOrderNumber(num);
        o.setTotalAmount(amount);
        o.setDiscount(BigDecimal.ZERO);
        return o;
    }

    private BusinessRule rule(String code, int priority, RuleActionType type, String spel,
                              Map<String, Object> cfg) {
        return BusinessRule.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .ruleCode(code)
                .ruleName(code)
                .scope(RuleScope.ORDER)
                .conditionSpel(spel)
                .actionType(type)
                .actionConfigJson(cfg == null ? new LinkedHashMap<>() : cfg)
                .priority(priority)
                .enabled(true)
                .build();
    }

    // ==================== LOG ====================

    @Test
    @DisplayName("LOG: matched rule writes audit log row, no rejection")
    void log_action_writes_audit_log() {
        OrderInput order = sampleOrder("SO-001", new BigDecimal("150000"));
        BusinessRule r = rule("high_value_log", 50, RuleActionType.LOG,
                "#input.totalAmount > 100000",
                Map.of("level", "INFO", "message", "高额单据 {orderNumber}"));
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER)).thenReturn(List.of(r));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER, order);

        assertFalse(result.shouldReject());
        assertEquals(1, result.executedRules().size());
        ArgumentCaptor<RuleExecutionLog> cap = ArgumentCaptor.forClass(RuleExecutionLog.class);
        verify(logRepository, times(1)).save(cap.capture());
        assertEquals(r.getId(), cap.getValue().getRuleId());
        assertEquals(FACTORY_ID, cap.getValue().getFactoryId());
        assertEquals("LOG", cap.getValue().getResultJson().get("action"));
    }

    // ==================== REJECT (short-circuit) ====================

    @Test
    @DisplayName("REJECT: first match short-circuits — later rules NOT evaluated, "
               + "actionHint + severity propagate (Phase 4a post-review C1)")
    void reject_short_circuits() {
        OrderInput order = sampleOrder("PO-001", new BigDecimal("50000"));
        order.setBlacklisted(true);

        BusinessRule r1 = rule("po_blacklist", 10, RuleActionType.REJECT,
                "#input.blacklisted == true",
                Map.of("reason", "供应商在黑名单",
                       "actionHint", "/system/suppliers/SUP-001",
                       "severity", "blocking"));
        BusinessRule r2 = rule("po_high_log", 20, RuleActionType.LOG,
                "#input.totalAmount > 10000",
                Map.of("level", "INFO", "message", "高额单据"));
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER)).thenReturn(List.of(r1, r2));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER, order);

        assertTrue(result.shouldReject());
        assertEquals("po_blacklist", result.rejectRuleCode());
        assertEquals("供应商在黑名单", result.rejectReason());
        // Phase 4a post-review C1: admin-configured actionHint + severity propagate to result.
        assertEquals("/system/suppliers/SUP-001", result.rejectActionHint(),
                "actionHint must propagate from rule.actionConfigJson to RuleEvaluationResult");
        assertEquals("blocking", result.rejectSeverity(),
                "severity must propagate from rule.actionConfigJson to RuleEvaluationResult");
        assertEquals(1, result.executedRules().size(),
                "Lower-priority rule must NOT execute after REJECT short-circuit");
        // Only the rejecting rule writes an audit log row.
        ArgumentCaptor<RuleExecutionLog> cap = ArgumentCaptor.forClass(RuleExecutionLog.class);
        verify(logRepository, times(1)).save(cap.capture());
        // Audit log also carries actionHint + severity for forensics.
        assertEquals("/system/suppliers/SUP-001", cap.getValue().getResultJson().get("actionHint"));
        assertEquals("blocking", cap.getValue().getResultJson().get("severity"));
    }

    @Test
    @DisplayName("REJECT without actionHint config: result.rejectActionHint() is null, "
               + "severity defaults to 'warning' (Phase 4a post-review C1)")
    void reject_without_action_hint_defaults() {
        OrderInput order = sampleOrder("PO-002", new BigDecimal("50000"));
        BusinessRule r = rule("simple_reject", 10, RuleActionType.REJECT,
                "#input.totalAmount > 1000",
                Map.of("reason", "金额超限"));   // no actionHint, no severity
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER)).thenReturn(List.of(r));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER, order);

        assertTrue(result.shouldReject());
        assertEquals("simple_reject", result.rejectRuleCode());
        assertNull(result.rejectActionHint(), "Missing actionHint config → null in result");
        assertEquals("warning", result.rejectSeverity(),
                "Missing severity config → 'warning' default in result");
    }

    // ==================== MODIFY (reflective mutation) ====================

    @Test
    @DisplayName("MODIFY: mutates inputObject field via BeanWrapper")
    void modify_mutates_input_field() {
        OrderInput order = sampleOrder("SO-VIP-001", new BigDecimal("80000"));
        BusinessRule r = rule("vip_discount", 30, RuleActionType.MODIFY,
                "#input.totalAmount > 50000",
                Map.of("field", "discount", "value", new BigDecimal("0.05")));
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER)).thenReturn(List.of(r));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER, order);

        assertFalse(result.shouldReject());
        assertEquals(1, result.modifications().size());
        RuleModification mod = result.modifications().get(0);
        assertEquals("discount", mod.field());
        assertEquals(new BigDecimal("0.05"), mod.newValue());
        // Real mutation happened on the input object.
        assertEquals(new BigDecimal("0.05"), order.getDiscount(),
                "MODIFY must reflectively set the field on inputObject");
        verify(logRepository, times(1)).save(any());
    }

    // ==================== TRIGGER_WORKFLOW ====================

    @Test
    @DisplayName("TRIGGER_WORKFLOW: invokes WorkflowEngineFacade with explicit businessEntityId from config")
    void trigger_workflow_calls_facade() {
        OrderInput order = sampleOrder("INV-001", new BigDecimal("0"));
        order.setMaterialId("MAT-123");
        BusinessRule r = rule("low_stock_transfer", 40, RuleActionType.TRIGGER_WORKFLOW,
                "#input.totalAmount == 0",
                Map.of("workflowCode", "TRANSFER_REQUEST",
                       "businessEntityId", "MAT-123",
                       "ctx", Map.of("materialId", "#input.materialId")));
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER)).thenReturn(List.of(r));
        when(workflowEngineFacade.startWorkflow(eq(FACTORY_ID), eq("TRANSFER_REQUEST"),
                eq("MAT-123"), anyMap(), any())).thenReturn(null);

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER, order);

        assertFalse(result.shouldReject());
        ArgumentCaptor<Map<String, Object>> ctxCap = ArgumentCaptor.forClass(Map.class);
        verify(workflowEngineFacade, times(1)).startWorkflow(
                eq(FACTORY_ID), eq("TRANSFER_REQUEST"), eq("MAT-123"), ctxCap.capture(), any());
        assertEquals("MAT-123", ctxCap.getValue().get("materialId"),
                "ctx values starting with '#' must be SpEL-resolved against inputObject");
        verify(logRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("TRIGGER_WORKFLOW: businessEntityId auto-fills from #input.id when missing in config "
               + "(Phase 4a post-review I3)")
    void trigger_workflow_auto_fills_business_entity_id_from_input_id() {
        OrderInput order = sampleOrder("SO-001", new BigDecimal("0"));
        order.setId("ORDER-UUID-42");  // SpEL #input.id resolves to this
        order.setMaterialId("MAT-456");
        BusinessRule r = rule("auto_id_workflow", 40, RuleActionType.TRIGGER_WORKFLOW,
                "#input.totalAmount == 0",
                Map.of("workflowCode", "ORDER_APPROVAL",
                       // No businessEntityId — engine should auto-fill from #input.id
                       "ctx", Map.of("materialId", "#input.materialId")));
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER)).thenReturn(List.of(r));
        when(workflowEngineFacade.startWorkflow(eq(FACTORY_ID), eq("ORDER_APPROVAL"),
                eq("ORDER-UUID-42"), anyMap(), any())).thenReturn(null);

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER, order);

        assertFalse(result.shouldReject());
        verify(workflowEngineFacade, times(1)).startWorkflow(
                eq(FACTORY_ID), eq("ORDER_APPROVAL"), eq("ORDER-UUID-42"), anyMap(), any());
    }

    @Test
    @DisplayName("TRIGGER_WORKFLOW: skips (does NOT invoke facade) when businessEntityId "
               + "is empty AND #input.id is null — prevents Phase 1 unique constraint collision "
               + "(Phase 4a post-review I3)")
    void trigger_workflow_skips_when_no_business_entity_id() {
        OrderInput order = sampleOrder("SO-002", new BigDecimal("0"));
        // order.id is null — no auto-fill source
        BusinessRule r = rule("missing_id_workflow", 40, RuleActionType.TRIGGER_WORKFLOW,
                "#input.totalAmount == 0",
                Map.of("workflowCode", "ORDER_APPROVAL"));  // No businessEntityId, no #input.id
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER)).thenReturn(List.of(r));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER, order);

        assertFalse(result.shouldReject());
        // Facade NOT invoked — would have violated uq_aw_instances_active constraint.
        verify(workflowEngineFacade, never()).startWorkflow(anyString(), anyString(),
                anyString(), anyMap(), any());
        // Skip is still audited so admin can spot misconfigured rules.
        ArgumentCaptor<RuleExecutionLog> cap = ArgumentCaptor.forClass(RuleExecutionLog.class);
        verify(logRepository, times(1)).save(cap.capture());
        assertEquals("SKIPPED_NO_BUSINESS_ENTITY_ID",
                cap.getValue().getResultJson().get("status"));
    }

    // ==================== Priority + non-short-circuit cumulative ====================

    @Test
    @DisplayName("LOG + MODIFY (no REJECT): both apply, both logged, in priority order")
    void cumulative_log_and_modify() {
        OrderInput order = sampleOrder("SO-002", new BigDecimal("60000"));
        BusinessRule r1 = rule("log_high", 10, RuleActionType.LOG,
                "#input.totalAmount > 50000",
                Map.of("level", "INFO", "message", "log first"));
        BusinessRule r2 = rule("modify_discount", 20, RuleActionType.MODIFY,
                "#input.totalAmount > 30000",
                Map.of("field", "discount", "value", new BigDecimal("0.03")));
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER)).thenReturn(List.of(r1, r2));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER, order);

        assertFalse(result.shouldReject());
        assertEquals(2, result.executedRules().size());
        assertEquals(1, result.modifications().size());
        assertEquals(new BigDecimal("0.03"), order.getDiscount());
        verify(logRepository, times(2)).save(any());
    }

    // ==================== Condition error tolerance ====================

    @Test
    @DisplayName("Condition error on one rule does not block other rules")
    void condition_error_skips_one_rule_only() {
        OrderInput order = sampleOrder("SO-003", new BigDecimal("20000"));
        // Bad rule references nonexistent field — should throw inside SpEL, be caught + skipped.
        BusinessRule bad = rule("bad_cond", 10, RuleActionType.LOG,
                "#input.nonexistentField == true", Map.of());
        BusinessRule good = rule("good_cond", 20, RuleActionType.LOG,
                "#input.totalAmount > 10000", Map.of("message", "ok"));
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER))
                .thenReturn(new ArrayList<>(List.of(bad, good)));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER, order);

        assertFalse(result.shouldReject());
        assertEquals(1, result.executedRules().size(), "Only the 'good' rule actually executes");
        assertEquals(good.getId(), result.executedRules().get(0));
        // Bad rule writes a CONDITION_ERROR audit row; good rule writes its LOG audit row.
        verify(logRepository, times(2)).save(any());
    }

    // ==================== preview() (no side effects) ====================

    @Test
    @DisplayName("preview: matched condition returns true with NO log row written, NO mutation")
    void preview_no_side_effects() {
        OrderInput order = sampleOrder("SO-PREV", new BigDecimal("150000"));
        BusinessRule r = rule("preview_log", 50, RuleActionType.LOG,
                "#input.totalAmount > 100000", Map.of("message", "high"));
        when(ruleRepository.findById(r.getId())).thenReturn(java.util.Optional.of(r));

        boolean matched = engine.preview(FACTORY_ID, r.getId(), order);

        assertTrue(matched);
        verify(logRepository, never()).save(any());
        // Original order untouched.
        assertEquals(BigDecimal.ZERO, order.getDiscount());
    }

    @Test
    @DisplayName("preview: condition false returns false")
    void preview_unmatched() {
        OrderInput order = sampleOrder("SO-LOW", new BigDecimal("100"));
        BusinessRule r = rule("preview_low", 50, RuleActionType.LOG,
                "#input.totalAmount > 100000", Map.of());
        when(ruleRepository.findById(r.getId())).thenReturn(java.util.Optional.of(r));

        boolean matched = engine.preview(FACTORY_ID, r.getId(), order);
        assertFalse(matched);
        verify(logRepository, never()).save(any());
    }

    // ==================== Empty rules / null guards ====================

    @Test
    @DisplayName("Empty rule list: no audit, no mutation, returns ok")
    void empty_rules_returns_ok() {
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER)).thenReturn(List.of());

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER,
                sampleOrder("SO-NULL", BigDecimal.ZERO));

        assertFalse(result.shouldReject());
        assertTrue(result.executedRules().isEmpty());
        verify(logRepository, never()).save(any());
    }

    @Test
    @DisplayName("Empty SpEL condition treated as 'always true'")
    void empty_condition_always_true() {
        OrderInput order = sampleOrder("SO-Y", BigDecimal.ZERO);
        BusinessRule r = rule("unconditional_log", 10, RuleActionType.LOG, "", Map.of());
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER)).thenReturn(List.of(r));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER, order);

        assertFalse(result.shouldReject());
        assertEquals(1, result.executedRules().size());
        verify(logRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("null factoryId: returns ok without DB query")
    void null_factory_no_query() {
        RuleEvaluationResult result = engine.evaluate(null, RuleScope.ORDER,
                sampleOrder("SO-X", BigDecimal.ONE));
        assertFalse(result.shouldReject());
        verify(ruleRepository, never()).findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                anyString(), any());
    }

    // ==================== B-BR1 fix: SpEL scope-alias variables ====================

    @Test
    @DisplayName("B-BR1: ORDER scope condition '#order.totalAmount > X' resolves "
               + "(scope alias #order points to same object as #input)")
    void spel_order_alias_resolved_for_order_scope() {
        OrderInput order = sampleOrder("SO-ORDER-ALIAS", new BigDecimal("150000"));
        // Rule uses the human-readable #order alias from the dialog hint copy.
        BusinessRule r = rule("order_alias_match", 10, RuleActionType.LOG,
                "#order.totalAmount > 100000",
                Map.of("level", "INFO", "message", "alias-test"));
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER)).thenReturn(List.of(r));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER, order);

        assertFalse(result.shouldReject());
        assertEquals(1, result.executedRules().size(),
                "#order alias must resolve to inputObject for ORDER scope rules");
        verify(logRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("B-BR1: ORDER scope #input alias still works (legacy backwards compat)")
    void spel_input_alias_still_works_for_order_scope() {
        OrderInput order = sampleOrder("SO-INPUT-ALIAS", new BigDecimal("150000"));
        BusinessRule r = rule("input_alias_match", 10, RuleActionType.LOG,
                "#input.totalAmount > 100000",
                Map.of("level", "INFO"));
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.ORDER)).thenReturn(List.of(r));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.ORDER, order);

        assertFalse(result.shouldReject());
        assertEquals(1, result.executedRules().size(),
                "#input must remain available alongside #order alias");
    }

    @Test
    @DisplayName("B-BR1: CUSTOM scope only has #input, scope aliases NOT bound")
    void spel_custom_scope_has_only_input_alias() {
        OrderInput order = sampleOrder("SO-CUSTOM", new BigDecimal("150000"));
        BusinessRule r = rule("custom_with_order_alias", 10, RuleActionType.LOG,
                "#order.totalAmount > 100000",  // #order undefined for CUSTOM scope
                Map.of("level", "INFO"));
        r.setScope(RuleScope.CUSTOM);
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.CUSTOM)).thenReturn(List.of(r));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.CUSTOM, order);

        // Condition error tolerated — rule should be skipped (not block), audit log written.
        assertFalse(result.shouldReject());
        assertEquals(0, result.executedRules().size(),
                "CUSTOM scope must NOT bind #order alias — rule using it silently fails (or errors out)");
    }

    @Test
    @DisplayName("B-BR1: preview() uses scope alias for #order on ORDER-scope rule")
    void preview_uses_scope_alias_for_order_scope() {
        OrderInput order = sampleOrder("SO-PREV-ORDER", new BigDecimal("200000"));
        BusinessRule r = rule("preview_order_alias", 10, RuleActionType.LOG,
                "#order.totalAmount > 100000",
                Map.of("level", "INFO"));
        when(ruleRepository.findById(r.getId())).thenReturn(java.util.Optional.of(r));

        boolean matched = engine.preview(FACTORY_ID, r.getId(), order);

        assertTrue(matched, "preview must bind #order alias on ORDER-scope rule");
    }

    @Test
    @DisplayName("B-BR1: INVENTORY scope binds #inventory alias")
    void spel_inventory_alias_resolved_for_inventory_scope() {
        OrderInput inv = sampleOrder("INV-001", new BigDecimal("5"));  // reuse OrderInput, totalAmount=qty
        BusinessRule r = rule("low_stock", 10, RuleActionType.LOG,
                "#inventory.totalAmount < 10",
                Map.of("level", "WARN"));
        r.setScope(RuleScope.INVENTORY);
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.INVENTORY)).thenReturn(List.of(r));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.INVENTORY, inv);

        assertEquals(1, result.executedRules().size(),
                "#inventory alias must resolve to inputObject for INVENTORY scope rules");
    }

    @Test
    @DisplayName("B-BR1: CUSTOMER scope binds #customer alias")
    void spel_customer_alias_resolved_for_customer_scope() {
        OrderInput cust = sampleOrder("CUST-001", new BigDecimal("999"));
        BusinessRule r = rule("vip_customer", 10, RuleActionType.LOG,
                "#customer.totalAmount > 500",
                Map.of("level", "INFO"));
        r.setScope(RuleScope.CUSTOMER);
        when(ruleRepository.findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
                FACTORY_ID, RuleScope.CUSTOMER)).thenReturn(List.of(r));

        RuleEvaluationResult result = engine.evaluate(FACTORY_ID, RuleScope.CUSTOMER, cust);

        assertEquals(1, result.executedRules().size(),
                "#customer alias must resolve to inputObject for CUSTOMER scope rules");
    }
}
