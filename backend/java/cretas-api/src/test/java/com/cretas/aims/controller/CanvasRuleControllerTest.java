package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.entity.rules.RuleActionType;
import com.cretas.aims.entity.rules.RuleScope;
import com.cretas.aims.repository.rules.BusinessRuleRepository;
import com.cretas.aims.repository.rules.RuleExecutionLogRepository;
import com.cretas.aims.service.rules.RuleEngine;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CanvasRuleController} PUT semantics — Canvas-Rules Phase 4a.
 *
 * <p>B-BR2 regression coverage (2026-05-19 Canvas Phase 2-5 QA finding): PUT with
 * {@code scope: null} or {@code actionType: null} previously NPE'd at
 * {@code raw.toUpperCase()}. The F1 fix (PR #44 review I1) added explicit null-check
 * before {@code .toUpperCase()}; these tests lock that behavior in.
 *
 * <p>B-A7 P0 SpEL injection coverage (2026-05-19 Canvas Phase 2-5 QA finding,
 * sister of PR #48 on CanvasAlertController): POST/PUT with
 * {@code conditionSpel = "T(java.lang.Runtime).getRuntime().exec(...)"} was
 * persisted raw without validation. The fix injects {@link SandboxedSpelEvaluator}
 * and calls {@code validateSyntax()} on write paths before persist; these tests
 * lock that behavior in.
 *
 * <p>PATCH semantics: explicit null = "no change". explicit empty string = 400 VALIDATION.
 *
 * <p>Tests use a real {@link SandboxedSpelEvaluator} instance (not mock) — the
 * sandbox itself has tests in {@code SandboxedSpelEvaluatorTest}; here we
 * verify Controller invokes it on the right paths with the right error
 * envelope ({@code errorCode="VALIDATION"} / {@code code=400}).
 *
 * @since 2026-05-19
 */
@DisplayName("CanvasRuleController PUT null-field handling + SpEL injection (B-BR2 + B-A7)")
@ExtendWith(MockitoExtension.class)
class CanvasRuleControllerTest {

    @Mock private BusinessRuleRepository ruleRepository;
    @Mock private RuleExecutionLogRepository logRepository;
    @Mock private RuleEngine ruleEngine;

    // Use real SpEL evaluator — verifies actual sandbox behavior, not a stub.
    // Mirrors CanvasAlertControllerTest pattern from PR #48.
    private final SandboxedSpelEvaluator spelEvaluator = new SandboxedSpelEvaluator();

    private CanvasRuleController controller;

    @BeforeEach
    void setUp() {
        controller = new CanvasRuleController(ruleRepository, logRepository, ruleEngine, spelEvaluator);
    }

    private static final String FACTORY_ID = "F006";

    private BusinessRule existingRule() {
        return BusinessRule.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .ruleCode("test_rule")
                .ruleName("Test rule")
                .scope(RuleScope.ORDER)
                .conditionSpel("#input.amount > 100")
                .actionType(RuleActionType.LOG)
                .actionConfigJson(new HashMap<>(Map.of("level", "INFO", "message", "test")))
                .priority(100)
                .enabled(true)
                .build();
    }

    private void stubFind(BusinessRule rule) {
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(BusinessRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== B-BR2: PUT {scope: null} (F1 regression) ====================

    @Test
    @DisplayName("B-BR2: PUT {scope: null} treated as 'no change' — no NPE, scope unchanged")
    void putWithScopeNullHandledGracefully() {
        BusinessRule rule = existingRule();
        stubFind(rule);
        Map<String, Object> body = new HashMap<>();
        body.put("scope", null);

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertNotNull(resp);
        assertTrue(resp.getSuccess(), "scope=null must NOT 500 NPE");
        assertEquals(Integer.valueOf(200), resp.getCode());
        assertEquals(RuleScope.ORDER, rule.getScope(),
                "scope=null (PATCH semantics) → existing scope preserved");
    }

    @Test
    @DisplayName("B-BR2: PUT {scope: ''} returns 400 VALIDATION (not silent 500)")
    void putWithScopeEmptyStringReturns400() {
        BusinessRule rule = existingRule();
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        Map<String, Object> body = new HashMap<>();
        body.put("scope", "");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertNotNull(resp);
        assertFalse(resp.getSuccess());
        assertEquals(Integer.valueOf(400), resp.getCode());
        assertTrue(String.valueOf(resp.getMessage()).contains("scope"),
                "Error message must mention 'scope' field");
        verify(ruleRepository, never()).save(any(BusinessRule.class));
    }

    @Test
    @DisplayName("B-BR2 sister: PUT {actionType: null} treated as 'no change' — no NPE")
    void putWithActionTypeNullHandledGracefully() {
        BusinessRule rule = existingRule();
        stubFind(rule);
        Map<String, Object> body = new HashMap<>();
        body.put("actionType", null);

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertNotNull(resp);
        assertTrue(resp.getSuccess(), "actionType=null must NOT 500 NPE");
        assertEquals(RuleActionType.LOG, rule.getActionType(),
                "actionType=null (PATCH semantics) → existing actionType preserved");
    }

    @Test
    @DisplayName("B-BR2 sister: PUT {actionType: ''} returns 400 VALIDATION (not silent 500)")
    void putWithActionTypeEmptyStringReturns400() {
        BusinessRule rule = existingRule();
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        Map<String, Object> body = new HashMap<>();
        body.put("actionType", "");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertFalse(resp.getSuccess());
        assertEquals(Integer.valueOf(400), resp.getCode());
        assertTrue(String.valueOf(resp.getMessage()).contains("actionType"));
    }

    @Test
    @DisplayName("B-BR2 valid case: PUT {scope: 'INVENTORY'} updates scope")
    void putWithValidScopeUpdates() {
        BusinessRule rule = existingRule();
        stubFind(rule);
        Map<String, Object> body = new HashMap<>();
        body.put("scope", "INVENTORY");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertTrue(resp.getSuccess());
        assertEquals(RuleScope.INVENTORY, rule.getScope());
    }

    @Test
    @DisplayName("B-BR2: PUT {scope: 'invalid'} returns 400 with friendly hint")
    void putWithInvalidScopeReturns400() {
        BusinessRule rule = existingRule();
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        Map<String, Object> body = new HashMap<>();
        body.put("scope", "INVALID_SCOPE_VALUE");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertFalse(resp.getSuccess());
        assertEquals(Integer.valueOf(400), resp.getCode());
    }

    @Test
    @DisplayName("B-BR2: PUT empty body {} no-op (all PATCH semantics, no NPE)")
    void putWithEmptyBodyIsNoop() {
        BusinessRule rule = existingRule();
        stubFind(rule);
        Map<String, Object> body = new LinkedHashMap<>();

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertTrue(resp.getSuccess());
        // Nothing changed.
        assertEquals(RuleScope.ORDER, rule.getScope());
        assertEquals(RuleActionType.LOG, rule.getActionType());
        assertEquals("test_rule", rule.getRuleCode());
    }

    @Test
    @DisplayName("B-BR2: PUT with multiple null fields — none NPE, all preserved")
    void putWithMultipleNullFieldsAllNoop() {
        BusinessRule rule = existingRule();
        stubFind(rule);
        Map<String, Object> body = new HashMap<>();
        body.put("scope", null);
        body.put("actionType", null);
        body.put("priority", null);
        body.put("enabled", null);  // booleanField returns default (current value)

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertTrue(resp.getSuccess(), "All-null PATCH body must NOT NPE");
        assertEquals(RuleScope.ORDER, rule.getScope());
        assertEquals(RuleActionType.LOG, rule.getActionType());
        assertEquals(Integer.valueOf(100), rule.getPriority());
        assertEquals(Boolean.TRUE, rule.getEnabled());
    }

    // ==================== B-A7 P0: SpEL injection (sister of PR #48) ====================

    private Map<String, Object> validCreateBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("ruleCode", "test_rule_new");
        body.put("ruleName", "Test rule new");
        body.put("scope", "ORDER");
        body.put("actionType", "LOG");
        body.put("priority", 99);
        return body;
    }

    @Test
    @DisplayName("B-A7 P0: POST 拒绝 T(java.lang.Runtime).getRuntime().exec(...) RCE 注入")
    void testSpelInjectionInCreateRejected() {
        Map<String, Object> body = validCreateBody();
        body.put("conditionSpel", "T(java.lang.Runtime).getRuntime().exec(\"calc\")");

        ApiResponse<Map<String, Object>> resp = controller.createRule(FACTORY_ID, body);

        assertNotNull(resp);
        assertEquals(Integer.valueOf(400), resp.getCode());
        assertFalse(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("SpEL"),
                "message 必须含 'SpEL' 关键字让前端能定位错误");
        // CRITICAL: rule must NOT be persisted
        verify(ruleRepository, never()).save(any(BusinessRule.class));
    }

    @Test
    @DisplayName("B-A7 P0: PUT 拒绝 T(java.lang.Runtime).getRuntime().exec(...) RCE 注入 (update path 也必须 gate)")
    void testSpelInjectionInUpdateRejected() {
        BusinessRule rule = existingRule();
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        Map<String, Object> body = new HashMap<>();
        body.put("conditionSpel", "T(java.lang.Runtime).getRuntime().exec(\"rm\")");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertNotNull(resp);
        assertEquals(Integer.valueOf(400), resp.getCode());
        assertFalse(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("SpEL"));
        verify(ruleRepository, never()).save(any(BusinessRule.class));
    }

    @Test
    @DisplayName("B-A7 P0: 合法 SpEL 表达式通过 — #order.amount > 100 (regression)")
    void testValidSpelStillAccepted() {
        // Use property-access syntax (#var.field) not map-indexing (#var['field']) —
        // sandbox dry-run with empty variables swallows "Property or field ... cannot be found"
        // / "on null" errors (these are expected when running with no context) but does NOT
        // swallow "Cannot index into a null value" (raised by ['...'] on null). This mirrors
        // the same constraint observed in CanvasAlertControllerTest (PR #48) which used
        // "#context.amount > 10000" for the same reason.
        Map<String, Object> body = validCreateBody();
        body.put("conditionSpel", "#order.amount > 100");

        when(ruleRepository.findByFactoryIdAndRuleCode(FACTORY_ID, "test_rule_new"))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any(BusinessRule.class))).thenAnswer(inv -> {
            BusinessRule r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });

        ApiResponse<Map<String, Object>> resp = controller.createRule(FACTORY_ID, body);

        assertNotNull(resp);
        assertEquals(Integer.valueOf(200), resp.getCode());
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        verify(ruleRepository).save(any(BusinessRule.class));
    }

    // ==================== AUD-9 P3: GET pagination + sort ====================

    /**
     * Helper: build N rules so we can verify pagination shape (totalElements / totalPages /
     * page / size all surface on the Page<Map> wrapper).
     */
    private List<BusinessRule> buildRules(int n) {
        List<BusinessRule> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(BusinessRule.builder()
                    .id(UUID.randomUUID())
                    .factoryId(FACTORY_ID)
                    .ruleCode("rule_" + i)
                    .ruleName("Rule " + i)
                    .scope(RuleScope.ORDER)
                    .conditionSpel("#input.amount > " + i)
                    .actionType(RuleActionType.LOG)
                    .actionConfigJson(new HashMap<>())
                    .priority(i)
                    .enabled(true)
                    .build());
        }
        return list;
    }

    @Test
    @DisplayName("AUD-9: GET /canvas-rules returns Page envelope (content + totalElements + totalPages)")
    void testListReturnsPageObject() {
        List<BusinessRule> rules = buildRules(5);
        Pageable expected = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "priority"));
        when(ruleRepository.findByFactoryId(eq(FACTORY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rules, expected, 5));

        ApiResponse<Page<Map<String, Object>>> resp =
                controller.listRules(FACTORY_ID, null, null, expected);

        assertNotNull(resp);
        assertTrue(resp.getSuccess());
        assertEquals(Integer.valueOf(200), resp.getCode());

        Page<Map<String, Object>> page = resp.getData();
        assertNotNull(page, "Page envelope must not be null");
        assertEquals(5, page.getContent().size(), "content size == rules count");
        assertEquals(5L, page.getTotalElements(), "totalElements surfaces");
        assertEquals(1, page.getTotalPages(), "totalPages calculated");
        assertEquals(0, page.getNumber(), "page number (0-indexed)");
        assertEquals(20, page.getSize(), "page size echo");
        // Each content item is a serialized map (NOT raw entity)
        Map<String, Object> first = page.getContent().get(0);
        assertEquals("rule_0", first.get("ruleCode"));
        assertEquals("Rule 0", first.get("ruleName"));
    }

    @Test
    @DisplayName("AUD-9: pagination request reaches repository — verify Pageable propagation")
    void testPaginationRespected() {
        // Client asks for page=1, size=2 — controller should forward exactly that.
        List<BusinessRule> page2Rules = buildRules(2);
        Pageable requested = PageRequest.of(1, 2, Sort.by(Sort.Direction.ASC, "priority"));
        when(ruleRepository.findByFactoryId(eq(FACTORY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(page2Rules, requested, 10));

        ApiResponse<Page<Map<String, Object>>> resp =
                controller.listRules(FACTORY_ID, null, null, requested);

        assertTrue(resp.getSuccess());
        Page<Map<String, Object>> result = resp.getData();
        assertEquals(2, result.getContent().size());
        assertEquals(10L, result.getTotalElements());
        assertEquals(5, result.getTotalPages(), "10 elements / 2 per page = 5 pages");
        assertEquals(1, result.getNumber(), "echoes requested page=1");
        assertEquals(2, result.getSize(), "echoes requested size=2");

        // Verify the repository was called with the user's Pageable (size==2, page==1).
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(ruleRepository).findByFactoryId(eq(FACTORY_ID), captor.capture());
        Pageable actual = captor.getValue();
        assertEquals(1, actual.getPageNumber(), "page propagated");
        assertEquals(2, actual.getPageSize(), "size propagated");
    }

    @Test
    @DisplayName("AUD-9: sort=ruleCode,DESC respected (Pageable Sort propagated to repository)")
    void testSortRespected() {
        Pageable requested = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "ruleCode"));
        when(ruleRepository.findByFactoryId(eq(FACTORY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(new ArrayList<>(), requested, 0));

        controller.listRules(FACTORY_ID, null, null, requested);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(ruleRepository).findByFactoryId(eq(FACTORY_ID), captor.capture());
        Sort actualSort = captor.getValue().getSort();
        Sort.Order ruleCodeOrder = actualSort.getOrderFor("ruleCode");
        assertNotNull(ruleCodeOrder, "ruleCode sort order present in Pageable forwarded to repo");
        assertEquals(Sort.Direction.DESC, ruleCodeOrder.getDirection(),
                "DESC direction propagated");
    }

    @Test
    @DisplayName("AUD-9 + scope/enabled filter: filter params route to the correct repo method")
    void testListRoutesFilteredFiltersToCorrectRepoMethod() {
        Pageable requested = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "priority"));
        // both scope + enabled → findByFactoryIdAndScopeAndEnabled
        when(ruleRepository.findByFactoryIdAndScopeAndEnabled(
                eq(FACTORY_ID), eq(RuleScope.INVENTORY), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(buildRules(1), requested, 1));

        ApiResponse<Page<Map<String, Object>>> resp =
                controller.listRules(FACTORY_ID, "INVENTORY", true, requested);

        assertTrue(resp.getSuccess());
        assertEquals(1L, resp.getData().getTotalElements());
        // No fallback method called
        verify(ruleRepository, never()).findByFactoryId(any(), any(Pageable.class));
        verify(ruleRepository, never()).findByFactoryIdAndScope(any(), any(), any(Pageable.class));
        verify(ruleRepository, never()).findByFactoryIdAndEnabled(any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("AUD-8 mitigation: size > 100 capped to 100 before reaching repository")
    void testPageSizeCappedAt100() {
        // Client attempts size=999999 → controller must cap at 100.
        Pageable requested = PageRequest.of(0, 999999, Sort.by(Sort.Direction.ASC, "priority"));
        when(ruleRepository.findByFactoryId(eq(FACTORY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(new ArrayList<>(), PageRequest.of(0, 100), 0));

        controller.listRules(FACTORY_ID, null, null, requested);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(ruleRepository).findByFactoryId(eq(FACTORY_ID), captor.capture());
        assertEquals(100, captor.getValue().getPageSize(),
                "abusive size=999999 must be clamped to 100");
    }

    // ==================== AUD-5 / B-A3 sister: ruleName length pre-check ====================

    @Test
    @DisplayName("AUD-5/B-A3 sister: POST with 256-char ruleName returns 400 VALIDATION (NOT 409)")
    void testRuleNameLengthRejectedOnCreate() {
        Map<String, Object> body = validCreateBody();
        // 256 chars — one past entity @Column(length=255)
        body.put("ruleName", "x".repeat(256));

        ApiResponse<Map<String, Object>> resp = controller.createRule(FACTORY_ID, body);

        assertNotNull(resp);
        assertFalse(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals(Integer.valueOf(400), resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("255"),
                "Error message should cite the max length (255)");
        assertTrue(resp.getMessage().contains("256"),
                "Error message should cite the user's actual length (256)");
        // Critical: no persist + no duplicate check (length precedes ruleCode lookup)
        verify(ruleRepository, never()).save(any(BusinessRule.class));
    }

    @Test
    @DisplayName("AUD-5/B-A3 sister: POST with 255-char ruleName (boundary) accepted")
    void testRuleNameBoundary255Accepted() {
        Map<String, Object> body = validCreateBody();
        body.put("ruleName", "x".repeat(255));  // exactly at max

        when(ruleRepository.findByFactoryIdAndRuleCode(FACTORY_ID, "test_rule_new"))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any(BusinessRule.class))).thenAnswer(inv -> {
            BusinessRule r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        ApiResponse<Map<String, Object>> resp = controller.createRule(FACTORY_ID, body);

        assertNotNull(resp);
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()), "255-char boundary must pass");
        assertEquals(Integer.valueOf(200), resp.getCode());
        verify(ruleRepository).save(any(BusinessRule.class));
    }

    @Test
    @DisplayName("AUD-5/B-A3 sister: PUT with 256-char ruleName returns 400 (regression on update path)")
    void testRuleNameLengthRejectedOnUpdate() {
        BusinessRule rule = existingRule();
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        Map<String, Object> body = new HashMap<>();
        body.put("ruleName", "y".repeat(256));

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertNotNull(resp);
        assertFalse(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals(Integer.valueOf(400), resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("255"));
        assertEquals("Test rule", rule.getRuleName(),
                "ruleName must NOT be mutated when validation fails");
        verify(ruleRepository, never()).save(any(BusinessRule.class));
    }

    @Test
    @DisplayName("AUD-5/B-A3 sister: PUT with 100-char ruleName accepted (regression)")
    void testValidRuleNameUpdateAccepted() {
        BusinessRule rule = existingRule();
        stubFind(rule);
        Map<String, Object> body = new HashMap<>();
        String newName = "z".repeat(100);
        body.put("ruleName", newName);

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertTrue(resp.getSuccess(), "100-char update should pass");
        assertEquals(newName, rule.getRuleName());
    }

    // ==================== AUD-4 (PR #94 follow-up): explicit version check ====================

    @Test
    @DisplayName("AUD-4: PUT with stale version (req v=0, db v=1) → 409 with refresh hint")
    void testStaleVersionRejectedWith409() {
        // Edge audit 2026-05-20 finding repro: two factory admins open the same rule in
        // 2 tabs, both see v=1. Admin A saves first (DB now v=2). Admin B clicks save
        // with stale v=1 snapshot — without this check the controller would silently
        // overwrite Admin A's work because loadRule re-reads v=2 and save just increments
        // to v=3. The explicit check fires before any field update so 409 surfaces instead.
        BusinessRule rule = existingRule();
        rule.setVersion(1L);   // DB has been updated by Admin A
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 0);   // stale client snapshot
        body.put("ruleName", "覆盖尝试");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertNotNull(resp);
        assertEquals(Integer.valueOf(409), resp.getCode(), "must be 409 CONFLICT, not silent 200");
        assertEquals("CONFLICT", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("数据已被其他用户修改"),
                "message must surface the lost-update semantics: " + resp.getMessage());
        assertTrue(resp.getMessage().contains("v=1") || resp.getMessage().contains("服务端 v=1"),
                "message must surface server version (1): " + resp.getMessage());
        assertTrue(resp.getMessage().contains("v=0") || resp.getMessage().contains("客户端 v=0"),
                "message must surface client version (0): " + resp.getMessage());
        assertEquals("请刷新页面查看最新数据后再编辑", resp.getActionHint(),
                "4-in-1 UX (a): actionHint must direct user to refresh");
        assertEquals("warning", resp.getSeverity(),
                "4-in-1 UX (c): severity warning for sticky toast");
        assertEquals("Test rule", rule.getRuleName(),
                "ruleName must NOT be mutated when stale version blocks update");
        // CRITICAL: save NEVER called — stale write blocked before it could overwrite
        verify(ruleRepository, never()).save(any(BusinessRule.class));
    }

    @Test
    @DisplayName("AUD-4: PUT with current version (req v=1, db v=1) → 200 happy path (regression)")
    void testCurrentVersionAccepted() {
        // Companion happy path — when client is up-to-date, save proceeds normally.
        BusinessRule rule = existingRule();
        rule.setVersion(1L);
        stubFind(rule);

        Map<String, Object> body = new HashMap<>();
        body.put("version", 1);   // matches DB
        body.put("ruleName", "新名字");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertNotNull(resp);
        assertTrue(resp.getSuccess(), "matching version → 200");
        assertEquals("新名字", rule.getRuleName(), "field updates applied normally");
        verify(ruleRepository).save(any(BusinessRule.class));
    }

    @Test
    @DisplayName("AUD-4: PUT without version (null) → 200 lenient (legacy clients keep working)")
    void testNullVersionLenientPassthrough() {
        // AUD-4 is a Lost Update fix — when clients DON'T send version they get the
        // pre-AUD-4 behavior (last-writer-wins). This lenient path lets AI Tool callers
        // and curl scripts continue working without forcing a version round-trip.
        BusinessRule rule = existingRule();
        rule.setVersion(5L);
        stubFind(rule);

        Map<String, Object> body = new HashMap<>();
        // intentionally no "version" key
        body.put("ruleName", "leniency-test");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(FACTORY_ID, rule.getId(), body);

        assertNotNull(resp);
        assertTrue(resp.getSuccess(), "missing version field → lenient pass");
        verify(ruleRepository).save(any(BusinessRule.class));
    }

    // ==================== Bug #4 (2026-05-20): DELETE 409 generic → 4位一体 ====================
    // Root cause: previous deleteRule called ruleRepository.delete(rule) which relies on
    // @SQLDelete (literal UPDATE business_rules SET deleted_at = NOW() WHERE id = ?). With
    // @Version added by V20260626_02 (AUD-4 P1), Hibernate's delete path expects version
    // bump but the @SQLDelete UPDATE doesn't touch the version column → some flush
    // permutations raise DataIntegrityViolationException → GlobalExceptionHandler's
    // else-branch surfaces the generic "数据处理异常" 409 with no actionHint. Sister chat
    // pattern (CanvasAlertController.deleteRule) uses manual softDelete()+save() which
    // runs standard Hibernate UPDATE WHERE id=? AND version=?, increments version, and
    // surfaces opt-lock conflicts via ObjectOptimisticLockingFailureException (specific
    // 409). This block locks in the new behavior.

    @Test
    @DisplayName("Bug #4: DELETE → 200 + softDelete called + save invoked (no repository.delete)")
    void testDeleteUsesSoftDeletePlusSavePattern() {
        BusinessRule rule = existingRule();
        rule.setVersion(0L);
        // pre-condition: rule NOT yet soft-deleted
        assertEquals(null, rule.getDeletedAt(),
                "test fixture must start with deletedAt=null");
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(BusinessRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<Map<String, Object>> resp = controller.deleteRule(FACTORY_ID, rule.getId());

        assertNotNull(resp);
        assertTrue(resp.getSuccess(), "happy-path delete must return 200");
        assertEquals(Integer.valueOf(200), resp.getCode());
        assertEquals("规则已删除", resp.getMessage());
        // softDelete() set deletedAt in memory before save was called
        assertNotNull(rule.getDeletedAt(),
                "softDelete() must have set deletedAt in-memory");
        // CRITICAL: verify we go through save() (Hibernate UPDATE w/ version check)
        // and NOT repository.delete() (which triggers @SQLDelete without version bump).
        verify(ruleRepository).save(any(BusinessRule.class));
        verify(ruleRepository, never()).delete(any(BusinessRule.class));
        // response includes deletedAt for client to display
        assertNotNull(resp.getData());
        assertNotNull(resp.getData().get("deletedAt"));
        assertEquals(rule.getId().toString(), resp.getData().get("ruleId"));
    }

    @Test
    @DisplayName("Bug #4: DELETE already-deleted rule → 200 idempotent + alreadyDeleted=true (no save)")
    void testDeleteAlreadyDeletedIsIdempotent() {
        // Repro of the "stuck record" scenario from F006 prod: a previous DELETE call
        // half-completed (e.g. the @SQLDelete UPDATE succeeded but a subsequent flush
        // raised DataIntegrityViolationException). The row sits in DB with deletedAt
        // set but visible to admins via direct query. Subsequent DELETE attempts must
        // NOT 409 — they must idempotently confirm the row is deleted.
        BusinessRule rule = existingRule();
        java.time.LocalDateTime priorDeletedAt = java.time.LocalDateTime.now().minusMinutes(5);
        rule.setDeletedAt(priorDeletedAt);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        ApiResponse<Map<String, Object>> resp = controller.deleteRule(FACTORY_ID, rule.getId());

        assertNotNull(resp);
        assertTrue(resp.getSuccess(),
                "Repeat DELETE on already-deleted rule must return success (idempotent)");
        assertEquals(Integer.valueOf(200), resp.getCode());
        assertEquals("规则已是删除状态", resp.getMessage(),
                "Message must signal idempotent outcome, not pretend the call did work");
        assertEquals(Boolean.TRUE, resp.getData().get("alreadyDeleted"),
                "Data envelope must include alreadyDeleted=true so callers can branch");
        assertEquals(priorDeletedAt.toString(), resp.getData().get("deletedAt"),
                "deletedAt must echo the ORIGINAL deletion time, not now()");
        // CRITICAL: no save invoked (don't bump version on no-op)
        verify(ruleRepository, never()).save(any(BusinessRule.class));
        verify(ruleRepository, never()).delete(any(BusinessRule.class));
    }

    @Test
    @DisplayName("Bug #4: DELETE opt-lock conflict → 409 CONFLICT + specific refresh hint (NOT generic '数据处理异常')")
    void testDeleteOptimisticLockSurfacedAs409WithHint() {
        // Repro: parallel session bumped version between our load and save. Hibernate's
        // save() raises ObjectOptimisticLockingFailureException; controller maps it to
        // a specific 409 with actionHint instead of letting it bubble to
        // GlobalExceptionHandler which would emit a vanilla 409 with no actionHint
        // (caller-side ElMessageBox.confirm would then misclassify as "并发编辑冲突"
        // dialog instead of the specific delete-failed flow).
        BusinessRule rule = existingRule();
        rule.setVersion(0L);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(BusinessRule.class)))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                        BusinessRule.class, rule.getId()));

        ApiResponse<Map<String, Object>> resp = controller.deleteRule(FACTORY_ID, rule.getId());

        assertNotNull(resp);
        assertFalse(Boolean.TRUE.equals(resp.getSuccess()), "must NOT report success");
        assertEquals(Integer.valueOf(409), resp.getCode(), "must be 409 CONFLICT");
        assertEquals("CONFLICT", resp.getErrorCode());
        // 4位一体 (a): message 具体, NOT generic "数据处理异常"
        assertTrue(resp.getMessage().contains("规则已被其他用户修改或删除"),
                "Message must be specific to canvas-rule delete conflict, not generic. Got: "
                        + resp.getMessage());
        assertFalse(resp.getMessage().contains("数据处理异常"),
                "Message must NEVER be the generic GlobalExceptionHandler fallback");
        // 4位一体 (d): actionHint含 next action
        assertNotNull(resp.getActionHint(), "actionHint must be present (fool-proof Rule 5)");
        assertTrue(resp.getActionHint().contains("刷新"),
                "actionHint must direct user to refresh: " + resp.getActionHint());
        assertEquals("warning", resp.getSeverity(),
                "4位一体 (c): severity warning for FE sticky toast (duration:0)");
    }

    @Test
    @DisplayName("Bug #4: DELETE on FK violation → 409 DATA_INTEGRITY (specific, not generic '数据处理异常')")
    void testDeleteDataIntegrityViolationSurfacedAs409WithHint() {
        // Defensive: future migrations may add inbound FK to business_rules with
        // ON DELETE RESTRICT (e.g. if rule_execution_logs FK switches to NOT NULL +
        // RESTRICT). With manual softDelete()+save() this branch wouldn't fire today
        // (UPDATE never triggers FK ON DELETE), but if a CHECK or NOT NULL constraint
        // is violated by some future schema migration, this exception path catches it
        // and emits a specific message.
        BusinessRule rule = existingRule();
        rule.setVersion(0L);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(BusinessRule.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "could not execute statement; SQL [...] constraint violation"));

        ApiResponse<Map<String, Object>> resp = controller.deleteRule(FACTORY_ID, rule.getId());

        assertNotNull(resp);
        assertFalse(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals(Integer.valueOf(409), resp.getCode());
        assertEquals("DATA_INTEGRITY", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("无法删除规则"),
                "Message must be specific to canvas-rule delete: " + resp.getMessage());
        assertFalse(resp.getMessage().contains("数据处理异常"),
                "Must NEVER be the generic GlobalExceptionHandler fallback");
        assertNotNull(resp.getActionHint());
        assertEquals("warning", resp.getSeverity());
    }

    @Test
    @DisplayName("Bug #4 regression: DELETE happy-path success message + data shape preserved")
    void testDeleteHappyPathDataShape() {
        // Lock in the response data shape so FE callers continue to find ruleId / ruleCode
        // at the same keys. This protects against accidental rename during further refactor.
        BusinessRule rule = existingRule();
        rule.setVersion(2L);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(BusinessRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<Map<String, Object>> resp = controller.deleteRule(FACTORY_ID, rule.getId());

        assertTrue(resp.getSuccess());
        Map<String, Object> data = resp.getData();
        assertNotNull(data);
        assertEquals(rule.getId().toString(), data.get("ruleId"));
        assertEquals(rule.getRuleCode(), data.get("ruleCode"));
        assertNotNull(data.get("deletedAt"),
                "deletedAt must be in response so FE can display when row was removed");
        // alreadyDeleted absent on fresh-delete (only present on idempotent path)
        assertFalse(data.containsKey("alreadyDeleted")
                        && Boolean.TRUE.equals(data.get("alreadyDeleted")),
                "alreadyDeleted=true must NOT be set on fresh delete");
    }
}
