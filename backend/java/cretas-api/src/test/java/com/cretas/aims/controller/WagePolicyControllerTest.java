package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.HourlyRateRule;
import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.repository.WageCalculationRepository;
import com.cretas.aims.scheduler.WageMonthlyScheduler;
import com.cretas.aims.service.WageCalculationService;
import com.cretas.aims.service.WagePolicyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bug #4 (prod QA, 2026-05-20) regression tests for {@link WagePolicyController}.
 *
 * <p><b>Vulnerability before fix</b>: POST {@code /api/mobile/{factoryId}/wage-policy/policies}
 * with empty body {@code {}} returned 200 + created a record with entity defaults
 * ({@code mode=PIECE_RATE} from field initializer {@code = WageMode.PIECE_RATE},
 * {@code isActive=true}). Same Rule 17.1 anti-pattern on {@code POST /hourly-rules}
 * where missing {@code employeeId} / {@code effectiveFrom} would slip past Controller
 * and trip DB NOT NULL constraint =&gt; 500 leak instead of 400.
 *
 * <p><b>Fix</b>: Controller signature changed from {@code @Valid @RequestBody Entity}
 * to {@code @RequestBody Map<String, Object>} so raw JSON key presence can be checked
 * (distinguishes "user did NOT send mode" from "user sent mode=PIECE_RATE").
 * Reject empty {@code {}} body, reject missing required keys, return
 * {@code errorCode=VALIDATION} + actionHint.
 *
 * <p>Tests use pure Mockito with no Spring context — verify Controller validation
 * layer, not full Spring MVC binding.
 *
 * @since 2026-05-20 (prod QA Bug #4 hotfix)
 */
@DisplayName("WagePolicyController Bug #4 — 拒绝空 body + 必填字段 (Rule 17.1 mitigation)")
@ExtendWith(MockitoExtension.class)
class WagePolicyControllerTest {

    @Mock private WagePolicyService wagePolicyService;
    @Mock private WageCalculationService wageCalculationService;
    @Mock private WageCalculationRepository wageCalculationRepository;
    @Mock private WageMonthlyScheduler wageMonthlyScheduler;

    @InjectMocks
    private WagePolicyController controller;

    private static final String FACTORY_ID = "F006";

    // ==================== savePolicy: empty body / missing mode ====================

    @Test
    @DisplayName("Bug #4 P1: POST /policies with null body 返 400 VALIDATION, 不调 service")
    void testSavePolicyNullBodyRejected() {
        ApiResponse<WagePolicy> resp = controller.savePolicy(FACTORY_ID, null);

        assertNotNull(resp);
        assertEquals(400, resp.getCode());
        assertFalse(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("请求体不能为空"),
                "message 必须含 '请求体不能为空' 让前端能定位错误");
        assertNotNull(resp.getActionHint(), "必须含 actionHint (4 位一体 d)");
        // CRITICAL: service must NOT be called
        verify(wagePolicyService, never()).savePolicy(anyString(), any(WagePolicy.class));
    }

    @Test
    @DisplayName("Bug #4 P1: POST /policies with empty {} 返 400, 不创建 entity-default record")
    void testSavePolicyEmptyObjectRejected() {
        // 这是 QA 报告核心场景: curl -X POST -d '{}' → 历史上 200 + 创建一条 mode=PIECE_RATE 默认 policy
        ApiResponse<WagePolicy> resp = controller.savePolicy(FACTORY_ID, Collections.emptyMap());

        assertNotNull(resp);
        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("请求体不能为空"));
        verify(wagePolicyService, never()).savePolicy(anyString(), any(WagePolicy.class));
    }

    @Test
    @DisplayName("Bug #4 P1: POST /policies 缺 mode key 返 400 (用户只传 isActive 也被拒)")
    void testSavePolicyMissingModeKeyRejected() {
        // 用户传了 body 但没显式 mode → 不能让 entity default 静默接管
        Map<String, Object> body = new HashMap<>();
        body.put("isActive", true);
        body.put("notes", "test");

        ApiResponse<WagePolicy> resp = controller.savePolicy(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("工资模式") || resp.getMessage().contains("mode"));
        assertNotNull(resp.getActionHint());
        assertTrue(resp.getActionHint().contains("PIECE_RATE")
                        && resp.getActionHint().contains("HOURLY")
                        && resp.getActionHint().contains("MIXED"),
                "actionHint 必须列出全部 3 个合法值 (Rule 5 dead-end 改导航)");
        verify(wagePolicyService, never()).savePolicy(anyString(), any(WagePolicy.class));
    }

    @Test
    @DisplayName("Bug #4 P1: POST /policies mode=null 返 400")
    void testSavePolicyNullModeRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", null);

        ApiResponse<WagePolicy> resp = controller.savePolicy(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(wagePolicyService, never()).savePolicy(anyString(), any(WagePolicy.class));
    }

    @Test
    @DisplayName("Bug #4 P1: POST /policies mode 非法值 返 400")
    void testSavePolicyInvalidModeRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", "INVALID_MODE_XYZ");

        ApiResponse<WagePolicy> resp = controller.savePolicy(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("非法") || resp.getMessage().contains("INVALID"));
        verify(wagePolicyService, never()).savePolicy(anyString(), any(WagePolicy.class));
    }

    @Test
    @DisplayName("Bug #4 P1: POST /policies MIXED mode 缺 mixedFormulaHint 返 400")
    void testSavePolicyMixedModeRequiresFormulaHint() {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", "MIXED");

        ApiResponse<WagePolicy> resp = controller.savePolicy(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("MIXED") || resp.getMessage().contains("公式"));
        verify(wagePolicyService, never()).savePolicy(anyString(), any(WagePolicy.class));
    }

    @Test
    @DisplayName("Bug #4 P1: POST /policies MIXED mode 空白 mixedFormulaHint 返 400")
    void testSavePolicyMixedModeRejectsBlankFormulaHint() {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", "MIXED");
        body.put("mixedFormulaHint", "   ");

        ApiResponse<WagePolicy> resp = controller.savePolicy(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(wagePolicyService, never()).savePolicy(anyString(), any(WagePolicy.class));
    }

    // ==================== savePolicy: regression (合法请求仍接受) ====================

    @Test
    @DisplayName("Bug #4 regression: POST /policies PIECE_RATE 合法请求接受 200")
    void testSavePolicyValidPieceRateAccepted() {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", "PIECE_RATE");
        body.put("isActive", true);

        when(wagePolicyService.savePolicy(anyString(), any(WagePolicy.class)))
                .thenAnswer(inv -> {
                    WagePolicy p = inv.getArgument(1);
                    p.setId(1L);
                    return p;
                });

        ApiResponse<WagePolicy> resp = controller.savePolicy(FACTORY_ID, body);

        assertEquals(200, resp.getCode());
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        assertNotNull(resp.getData());
        assertEquals(WageMode.PIECE_RATE, resp.getData().getMode());
        assertEquals(FACTORY_ID, resp.getData().getFactoryId());
        verify(wagePolicyService).savePolicy(anyString(), any(WagePolicy.class));
    }

    @Test
    @DisplayName("Bug #4 regression: POST /policies HOURLY 合法请求接受 200")
    void testSavePolicyValidHourlyAccepted() {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", "HOURLY");
        body.put("employeeId", 100);

        when(wagePolicyService.savePolicy(anyString(), any(WagePolicy.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        ApiResponse<WagePolicy> resp = controller.savePolicy(FACTORY_ID, body);

        assertEquals(200, resp.getCode());
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals(WageMode.HOURLY, resp.getData().getMode());
        assertEquals(100L, resp.getData().getEmployeeId());
        verify(wagePolicyService).savePolicy(anyString(), any(WagePolicy.class));
    }

    @Test
    @DisplayName("Bug #4 regression: POST /policies MIXED + 公式 合法请求接受 200")
    void testSavePolicyValidMixedAccepted() {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", "MIXED");
        body.put("mixedFormulaHint", "基础工时 + 80% 计件提成");

        when(wagePolicyService.savePolicy(anyString(), any(WagePolicy.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        ApiResponse<WagePolicy> resp = controller.savePolicy(FACTORY_ID, body);

        assertEquals(200, resp.getCode());
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals(WageMode.MIXED, resp.getData().getMode());
        assertEquals("基础工时 + 80% 计件提成", resp.getData().getMixedFormulaHint());
        verify(wagePolicyService).savePolicy(anyString(), any(WagePolicy.class));
    }

    // ==================== saveHourlyRateRule: empty body / missing fields ====================

    @Test
    @DisplayName("Bug #4 P1: POST /hourly-rules with null body 返 400, 不调 service")
    void testSaveHourlyRuleNullBodyRejected() {
        ApiResponse<HourlyRateRule> resp = controller.saveHourlyRateRule(FACTORY_ID, null);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("请求体不能为空"));
        verify(wagePolicyService, never()).saveHourlyRateRule(anyString(), any(HourlyRateRule.class));
    }

    @Test
    @DisplayName("Bug #4 P1: POST /hourly-rules with empty {} 返 400")
    void testSaveHourlyRuleEmptyObjectRejected() {
        ApiResponse<HourlyRateRule> resp =
                controller.saveHourlyRateRule(FACTORY_ID, Collections.emptyMap());

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(wagePolicyService, never()).saveHourlyRateRule(anyString(), any(HourlyRateRule.class));
    }

    @Test
    @DisplayName("Bug #4 P1: POST /hourly-rules with employeeId=null 返 400")
    void testSaveHourlyRuleMissingEmployeeIdRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("effectiveFrom", "2026-05-01");
        body.put("baseHourlyRate", 50);

        ApiResponse<HourlyRateRule> resp = controller.saveHourlyRateRule(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("员工") || resp.getMessage().contains("employeeId"));
        verify(wagePolicyService, never()).saveHourlyRateRule(anyString(), any(HourlyRateRule.class));
    }

    @Test
    @DisplayName("Bug #4 P1: POST /hourly-rules with effectiveFrom=null 返 400")
    void testSaveHourlyRuleMissingEffectiveFromRejected() {
        // effectiveFrom 缺失是 QA 报告中 entity-defaults 漏网最典型场景
        Map<String, Object> body = new HashMap<>();
        body.put("employeeId", 100);
        body.put("baseHourlyRate", 50);

        ApiResponse<HourlyRateRule> resp = controller.saveHourlyRateRule(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("生效日期") || resp.getMessage().contains("effectiveFrom"));
        assertNotNull(resp.getActionHint());
        assertTrue(resp.getActionHint().contains("yyyy-MM-dd"));
        verify(wagePolicyService, never()).saveHourlyRateRule(anyString(), any(HourlyRateRule.class));
    }

    @Test
    @DisplayName("Bug #4 P1: POST /hourly-rules with baseHourlyRate=null 返 400")
    void testSaveHourlyRuleMissingBaseRateRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("employeeId", 100);
        body.put("effectiveFrom", "2026-05-01");

        ApiResponse<HourlyRateRule> resp = controller.saveHourlyRateRule(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("时薪") || resp.getMessage().contains("baseHourlyRate"));
        verify(wagePolicyService, never()).saveHourlyRateRule(anyString(), any(HourlyRateRule.class));
    }

    @Test
    @DisplayName("Bug #4 P1: POST /hourly-rules effectiveFrom 格式错误 返 400 (不漏成 500)")
    void testSaveHourlyRuleBadDateFormatReturns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("employeeId", 100);
        body.put("effectiveFrom", "not-a-date");
        body.put("baseHourlyRate", 50);

        ApiResponse<HourlyRateRule> resp = controller.saveHourlyRateRule(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(wagePolicyService, never()).saveHourlyRateRule(anyString(), any(HourlyRateRule.class));
    }

    // ==================== saveHourlyRateRule: regression ====================

    @Test
    @DisplayName("Bug #4 regression: POST /hourly-rules 合法请求接受 200")
    void testSaveHourlyRuleValidAccepted() {
        Map<String, Object> body = new HashMap<>();
        body.put("employeeId", 100);
        body.put("effectiveFrom", "2026-05-01");
        body.put("baseHourlyRate", 50);

        when(wagePolicyService.saveHourlyRateRule(anyString(), any(HourlyRateRule.class)))
                .thenAnswer(inv -> {
                    HourlyRateRule r = inv.getArgument(1);
                    r.setId(1L);
                    return r;
                });

        ApiResponse<HourlyRateRule> resp = controller.saveHourlyRateRule(FACTORY_ID, body);

        assertEquals(200, resp.getCode());
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        assertNotNull(resp.getData());
        assertEquals(100L, resp.getData().getEmployeeId());
        assertEquals(new BigDecimal("50"), resp.getData().getBaseHourlyRate());
        verify(wagePolicyService).saveHourlyRateRule(anyString(), any(HourlyRateRule.class));
    }

    @Test
    @DisplayName("Bug #4 regression: POST /hourly-rules Service 抛 IllegalArgument (¥500 上限) → 400 RULE_VIOLATION")
    void testSaveHourlyRuleServiceGuardReturnsStructuredError() {
        Map<String, Object> body = new HashMap<>();
        body.put("employeeId", 100);
        body.put("effectiveFrom", "2026-05-01");
        body.put("baseHourlyRate", 600);  // 超 ¥500 上限

        when(wagePolicyService.saveHourlyRateRule(anyString(), any(HourlyRateRule.class)))
                .thenThrow(new IllegalArgumentException(
                        "时薪超过上限 ¥500/h, 请重新核对 (防呆 Rule 1)"));

        ApiResponse<HourlyRateRule> resp = controller.saveHourlyRateRule(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("RULE_VIOLATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("500"),
                "Service 抛的 ¥500 上限 message 必须透传到前端");
        assertNotNull(resp.getActionHint());
    }
}
