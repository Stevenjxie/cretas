package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.service.alerts.AlertEngineService;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hardening tests for {@link CanvasAlertController} — fixes 4 bugs from
 * independent QA report (2026-05-19 Canvas Phase 2-5 marathon):
 * <ul>
 *   <li><b>B-A7 P0</b>: SpEL injection — {@code T(java.lang.Runtime).getRuntime().exec(...)}
 *       was accepted raw at POST {@code /alerts/rules}.</li>
 *   <li><b>B-A4/A5 P1</b>: Empty {@code notifyChannels} / {@code notifyRoles} arrays
 *       silently accepted, producing useless rules (no one gets the alert).</li>
 *   <li><b>B-A3 P2</b>: 256-char {@code ruleName} returned generic 409
 *       "数据处理异常" instead of specific 400 with length hint.</li>
 *   <li><b>B-A8 P2</b>: PATCH {@code /alerts/rules/{id}} returned 405 — only PUT
 *       was registered. Added PATCH alias mirroring canvas-rules pattern (PR #44).</li>
 * </ul>
 *
 * <p>Tests use real {@link SandboxedSpelEvaluator} instance (not mock) — the
 * sandbox itself has tests in {@code SandboxedSpelEvaluatorTest}; here we
 * verify Controller invokes it on the right paths with the right error
 * envelope ({@code errorCode="VALIDATION"} / {@code code=400}).
 *
 * @since 2026-05-19 (Canvas QA hotfix)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanvasAlertController 加固测试 (Canvas QA 2026-05-19)")
class CanvasAlertControllerTest {

    @Mock AlertRuleRepository ruleRepository;
    @Mock AlertEngineService alertEngineService;

    // Use real SpEL evaluator — verifies actual sandbox behavior, not a stub.
    private final SandboxedSpelEvaluator spelEvaluator = new SandboxedSpelEvaluator();

    private CanvasAlertController controller;

    @BeforeEach
    void setUp() {
        controller = new CanvasAlertController(ruleRepository, alertEngineService, spelEvaluator);
    }

    private Map<String, Object> validBaseBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("ruleName", "测试规则");
        body.put("alertType", "INVENTORY_LOW");
        body.put("notifyChannels", List.of("EMAIL", "IN_APP"));
        body.put("notifyRoles", List.of("warehouse_admin"));
        body.put("severity", "MID");
        return body;
    }

    // ==================== B-A7 P0: SpEL injection ====================

    @Test
    @DisplayName("B-A7 P0: POST 拒绝 T(java.lang.Runtime).getRuntime().exec(...) RCE 注入")
    void testSpelInjectionRejected_runtimeExec() {
        Map<String, Object> body = validBaseBody();
        body.put("triggerConditionSpel", "T(java.lang.Runtime).getRuntime().exec(\"calc\")");

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertNotNull(resp);
        assertEquals(400, resp.getCode());
        assertFalse(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("SpEL"),
                "message 必须含 'SpEL' 关键字让前端能定位错误");
        // CRITICAL: rule must NOT be persisted
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("B-A7 P0: POST 拒绝 new ProcessBuilder() 构造器注入")
    void testSpelInjectionRejected_processBuilder() {
        Map<String, Object> body = validBaseBody();
        body.put("triggerConditionSpel", "new java.lang.ProcessBuilder('ls').start()");

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("B-A7 P0: POST 拒绝 #obj.getClass() 反射逃逸")
    void testSpelInjectionRejected_getClassReflection() {
        Map<String, Object> body = validBaseBody();
        body.put("triggerConditionSpel", "#own.getClass().getMethods()");

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("B-A7 P0: PUT 拒绝 RCE 注入 (update path 也必须 gate)")
    void testSpelInjectionRejected_onUpdate() {
        UUID ruleId = UUID.randomUUID();
        AlertRule existing = new AlertRule();
        existing.setId(ruleId);
        existing.setFactoryId("F006");
        existing.setRuleName("已有规则");
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("triggerConditionSpel", "T(java.lang.Runtime).getRuntime().exec(\"rm\")");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(
                "F006", ruleId.toString(), body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("B-A7 P0: 合法 SpEL 表达式通过 — #context.amount > 10000")
    void testValidSpelAccepted() {
        Map<String, Object> body = validBaseBody();
        body.put("triggerConditionSpel", "#context.amount > 10000");

        when(ruleRepository.findByFactoryIdAndRuleName("F006", "测试规则"))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any(AlertRule.class))).thenAnswer(inv -> {
            AlertRule r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertEquals(200, resp.getCode());
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        verify(ruleRepository).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("B-A7 P0: null SpEL 通过 (AlertRule javadoc: NULL = use built-in default logic)")
    void testNullSpelAccepted() {
        Map<String, Object> body = validBaseBody();
        // triggerConditionSpel not set — should be allowed

        when(ruleRepository.findByFactoryIdAndRuleName("F006", "测试规则"))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any(AlertRule.class))).thenAnswer(inv -> {
            AlertRule r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertEquals(200, resp.getCode());
        verify(ruleRepository).save(any(AlertRule.class));
    }

    // ==================== B-A4/A5: Empty arrays ====================

    @Test
    @DisplayName("B-A4 P1: POST 拒绝空 notifyChannels")
    void testEmptyNotifyChannelsRejected() {
        Map<String, Object> body = validBaseBody();
        body.put("notifyChannels", List.of());  // empty array

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("notifyChannels"),
                "message 必须明示哪个字段为空");
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("B-A5 P1: POST 拒绝空 notifyRoles")
    void testEmptyNotifyRolesRejected() {
        Map<String, Object> body = validBaseBody();
        body.put("notifyRoles", List.of());  // empty array

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("notifyRoles"));
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("B-A4 P1: POST 拒绝 null notifyChannels (字段缺失)")
    void testMissingNotifyChannelsRejected() {
        Map<String, Object> body = validBaseBody();
        body.remove("notifyChannels");  // field absent

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("B-A4/A5: PUT 拒绝显式传入空 notifyChannels")
    void testEmptyNotifyChannelsRejectedOnUpdate() {
        UUID ruleId = UUID.randomUUID();
        AlertRule existing = new AlertRule();
        existing.setId(ruleId);
        existing.setFactoryId("F006");
        existing.setRuleName("已有规则");
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("notifyChannels", List.of());  // explicit empty

        ApiResponse<Map<String, Object>> resp = controller.updateRule(
                "F006", ruleId.toString(), body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }

    // ==================== B-A3: rule name length ====================

    @Test
    @DisplayName("B-A3 P2: POST 256 字符 ruleName 返 400 with 长度 hint (非 409 generic)")
    void testLongRuleNameRejected() {
        String longName = "a".repeat(256);  // 256 chars, max is 255
        Map<String, Object> body = validBaseBody();
        body.put("ruleName", longName);

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertEquals(400, resp.getCode(),
                "必须返 400 VALIDATION, 不是 409 PG overflow");
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("255"),
                "message 必须含具体长度限制 (255)");
        assertTrue(resp.getMessage().contains("256"),
                "message 必须含当前长度 (256) 帮用户定位");
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("B-A3 P2: PUT 256 字符 ruleName 同样返 400 (update path)")
    void testLongRuleNameRejectedOnUpdate() {
        UUID ruleId = UUID.randomUUID();
        AlertRule existing = new AlertRule();
        existing.setId(ruleId);
        existing.setFactoryId("F006");
        existing.setRuleName("旧名");
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("ruleName", "x".repeat(300));

        ApiResponse<Map<String, Object>> resp = controller.updateRule(
                "F006", ruleId.toString(), body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("B-A3: 255 字符 ruleName 通过 (边界值)")
    void testMaxLengthRuleNameAccepted() {
        String maxName = "n".repeat(255);  // exactly 255, OK
        Map<String, Object> body = validBaseBody();
        body.put("ruleName", maxName);

        when(ruleRepository.findByFactoryIdAndRuleName("F006", maxName))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any(AlertRule.class))).thenAnswer(inv -> {
            AlertRule r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertEquals(200, resp.getCode());
        verify(ruleRepository).save(any(AlertRule.class));
    }

    // ==================== B-A8: PATCH alias ====================

    @Test
    @DisplayName("B-A8 P2: PATCH /alerts/rules/{id} 工作 (alias of PUT)")
    void testPatchAliasWorks() {
        UUID ruleId = UUID.randomUUID();
        AlertRule existing = new AlertRule();
        existing.setId(ruleId);
        existing.setFactoryId("F006");
        existing.setRuleName("旧名");
        existing.setEnabled(true);
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(existing));
        when(ruleRepository.save(any(AlertRule.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("ruleName", "新名");

        ApiResponse<Map<String, Object>> resp = controller.patchRule(
                "F006", ruleId.toString(), body);

        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        verify(ruleRepository).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("B-A8: PATCH 同样应用 SpEL 校验 (与 PUT 一致)")
    void testPatchAliasGatesInjection() {
        UUID ruleId = UUID.randomUUID();
        AlertRule existing = new AlertRule();
        existing.setId(ruleId);
        existing.setFactoryId("F006");
        existing.setRuleName("旧名");
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("triggerConditionSpel", "T(java.lang.Runtime).getRuntime().exec(\"id\")");

        ApiResponse<Map<String, Object>> resp = controller.patchRule(
                "F006", ruleId.toString(), body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("B-A8: PATCH 长 ruleName 同样拒绝 (与 PUT 一致)")
    void testPatchAliasGatesLongName() {
        UUID ruleId = UUID.randomUUID();
        AlertRule existing = new AlertRule();
        existing.setId(ruleId);
        existing.setFactoryId("F006");
        existing.setRuleName("旧名");
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("ruleName", "x".repeat(300));

        ApiResponse<Map<String, Object>> resp = controller.patchRule(
                "F006", ruleId.toString(), body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(ruleRepository, never()).save(any(AlertRule.class));
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
        UUID ruleId = UUID.randomUUID();
        AlertRule existing = new AlertRule();
        existing.setId(ruleId);
        existing.setFactoryId("F006");
        existing.setRuleName("现有规则");
        existing.setVersion(1L);   // DB has been updated by Admin A
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 0);   // stale client snapshot (Jackson commonly deserializes as Integer)
        body.put("ruleName", "试图覆盖的名字");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(
                "F006", ruleId.toString(), body);

        assertNotNull(resp);
        assertEquals(409, resp.getCode(), "must be 409 CONFLICT, not silent 200");
        assertTrue(resp.getMessage().contains("数据已被其他用户修改"),
                "message must surface the lost-update semantics: " + resp.getMessage());
        assertTrue(resp.getMessage().contains("v=1") || resp.getMessage().contains("服务端 v=1"),
                "message must surface server version: " + resp.getMessage());
        assertTrue(resp.getMessage().contains("v=0") || resp.getMessage().contains("客户端 v=0"),
                "message must surface client version: " + resp.getMessage());
        assertEquals("请刷新页面查看最新数据后再编辑", resp.getActionHint(),
                "4-in-1 UX (a): actionHint must direct user to refresh");
        assertEquals("warning", resp.getSeverity(),
                "4-in-1 UX (c): severity warning for sticky toast");
        // CRITICAL: save NEVER called — stale write blocked before it could overwrite
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("AUD-4: PUT with current version (req v=1, db v=1) → 200 happy path (regression)")
    void testCurrentVersionAccepted() {
        // Companion happy path — when client is up-to-date, save proceeds normally.
        // Guards against the version check false-positive blocking legitimate updates.
        UUID ruleId = UUID.randomUUID();
        AlertRule existing = new AlertRule();
        existing.setId(ruleId);
        existing.setFactoryId("F006");
        existing.setRuleName("现有规则");
        existing.setVersion(1L);
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(existing));
        when(ruleRepository.save(any(AlertRule.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 1);   // matches DB
        body.put("ruleName", "新名字");

        ApiResponse<Map<String, Object>> resp = controller.updateRule(
                "F006", ruleId.toString(), body);

        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        verify(ruleRepository).save(any(AlertRule.class));
    }

    @Test
    @DisplayName("AUD-4: PATCH applies same stale-version check (PUT/PATCH parity, Rule 16)")
    void testPatchAliasGatesStaleVersion() {
        UUID ruleId = UUID.randomUUID();
        AlertRule existing = new AlertRule();
        existing.setId(ruleId);
        existing.setFactoryId("F006");
        existing.setRuleName("现有规则");
        existing.setVersion(2L);
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 0);   // stale

        ApiResponse<Map<String, Object>> resp = controller.patchRule(
                "F006", ruleId.toString(), body);

        assertEquals(409, resp.getCode(), "Rule 16: PATCH path also gates stale version");
        assertEquals("请刷新页面查看最新数据后再编辑", resp.getActionHint());
        verify(ruleRepository, never()).save(any(AlertRule.class));
    }
}
