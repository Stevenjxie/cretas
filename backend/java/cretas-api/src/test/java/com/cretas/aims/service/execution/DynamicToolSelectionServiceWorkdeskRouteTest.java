package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ToolRouterService;
import com.cretas.aims.service.skill.SkillRouterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 9 P0.1 fix verification — Sprint 8 WORKDESK intents 用 tool_name=NULL +
 * Skill 路由, 但 trySkillRoute 用 Skill keyword 匹用户原文, 若 user input 不包含 Skill
 * triggers 就 fall through 到 buildNoToolResponse → "暂不支持此类型的意图执行: WORKDESK".
 *
 * <p>本 test 验证 {@link DynamicToolSelectionService#tryExplicitSkillRouteForIntent}
 * 通过 intent_code → skill_name (UPPER_SNAKE → lower-kebab) 直接 lookup Skill 绕过
 * keyword 匹配.
 */
class DynamicToolSelectionServiceWorkdeskRouteTest {

    private DynamicToolSelectionService service;
    private SkillRouterService mockSkillRouter;

    @BeforeEach
    void setUp() throws Exception {
        ToolRouterService mockToolRouter = mock(ToolRouterService.class);
        ToolRegistry mockToolRegistry = mock(ToolRegistry.class);
        ObjectMapper mapper = new ObjectMapper();

        service = new DynamicToolSelectionService(mockToolRouter, mockToolRegistry, mapper);

        // SkillRouterService is @Autowired(required=false), inject via reflection.
        mockSkillRouter = mock(SkillRouterService.class);
        Field skillRouterField = DynamicToolSelectionService.class.getDeclaredField("skillRouterService");
        skillRouterField.setAccessible(true);
        skillRouterField.set(service, mockSkillRouter);
    }

    // ==================== intentCodeToSkillName convention ====================

    @Test
    void intentCodeToSkillName_dailyCustomerFollowup_kebabLowercase() {
        assertThat(DynamicToolSelectionService.intentCodeToSkillName("DAILY_CUSTOMER_FOLLOWUP"))
                .isEqualTo("daily-customer-followup");
    }

    @Test
    void intentCodeToSkillName_monthlyFinancialClose_kebabLowercase() {
        assertThat(DynamicToolSelectionService.intentCodeToSkillName("MONTHLY_FINANCIAL_CLOSE"))
                .isEqualTo("monthly-financial-close");
    }

    @Test
    void intentCodeToSkillName_foodSafetyRecall_kebabLowercase() {
        assertThat(DynamicToolSelectionService.intentCodeToSkillName("FOOD_SAFETY_RECALL"))
                .isEqualTo("food-safety-recall");
    }

    @Test
    void intentCodeToSkillName_nullOrEmpty_returnsEmpty() {
        assertThat(DynamicToolSelectionService.intentCodeToSkillName(null)).isEmpty();
        assertThat(DynamicToolSelectionService.intentCodeToSkillName("")).isEmpty();
    }

    // ==================== tryExplicitSkillRouteForIntent — WORKDESK Skill 命中 ====================

    @Test
    void convertDynamicToolResult_doesNotExposeRouterFallbackDescription() throws Exception {
        Method method = DynamicToolSelectionService.class.getDeclaredMethod(
                "convertDynamicToolResultToResponse",
                Object.class,
                AIIntentConfig.class,
                ToolRouterService.SelectedTools.class);
        method.setAccessible(true);

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_WASTAGE_SUMMARY")
                .intentName("损耗汇总")
                .intentCategory("RESTAURANT")
                .build();
        ToolRouterService.SelectedTools selected = ToolRouterService.SelectedTools.builder()
                .tools(List.of(ToolRouterService.SelectedTools.SelectedTool.builder()
                        .toolName("restaurant_wastage_summary")
                        .order(1)
                        .build()))
                .executionOrder(ToolRouterService.SelectedTools.ExecutionOrder.SEQUENTIAL)
                .toolChainDescription("Fallback selection based on similarity")
                .build();
        Map<String, Object> result = Map.of(
                "restaurant_wastage_summary",
                Map.of("损耗记录数", 12, "说明", "本月损耗集中在备菜环节"));

        IntentExecuteResponse response = (IntentExecuteResponse) method.invoke(service, result, intent, selected);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getMessage()).doesNotContain("Fallback selection");
        assertThat(response.getMessage()).contains("损耗汇总");
        assertThat(response.getMessage()).contains("建议");
    }

    @Test
    void tryExplicitSkillRouteForIntent_workdeskSkillRegistered_executesSkillAndReturnsResponse() {
        when(mockSkillRouter.isSkillsEnabled()).thenReturn(true);

        SkillResult skillResult = SkillResult.builder()
                .success(true)
                .skillName("daily-customer-followup")
                .data(Map.of(
                        "high_priority", java.util.List.of("客户A"),
                        "medium_priority", java.util.List.of("客户B")))
                .message("🔴 高优先 (客户A): VIP + 商机停滞 14 天")
                .build();
        when(mockSkillRouter.executeSkill(eq("daily-customer-followup"), any(SkillContext.class)))
                .thenReturn(skillResult);

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("DAILY_CUSTOMER_FOLLOWUP")
                .intentName("今日客户跟进")
                .intentCategory("WORKDESK")
                .toolName(null)  // Sprint 8 P1 — NULL tool_name, depends on Skill route
                .build();

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                intent, "今日跟谁", "F006", 100L);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getIntentCode()).isEqualTo("DAILY_CUSTOMER_FOLLOWUP");
        assertThat(response.getIntentName()).isEqualTo("今日客户跟进");
        assertThat(response.getIntentCategory()).isEqualTo("WORKDESK");
        assertThat(response.getMessage()).contains("🔴 高优先");

        // 验证 SkillContext 含 factoryId + userId + userQuery
        ArgumentCaptor<SkillContext> ctxCaptor = ArgumentCaptor.forClass(SkillContext.class);
        verify(mockSkillRouter).executeSkill(eq("daily-customer-followup"), ctxCaptor.capture());
        SkillContext ctx = ctxCaptor.getValue();
        assertThat(ctx.getFactoryId()).isEqualTo("F006");
        assertThat(ctx.getUserId()).isEqualTo("100");
        assertThat(ctx.getUserQuery()).isEqualTo("今日跟谁");
    }

    @Test
    void tryExplicitSkillRouteForIntent_monthlyFinancialClose_routesToMonthlyClose() {
        when(mockSkillRouter.isSkillsEnabled()).thenReturn(true);
        when(mockSkillRouter.executeSkill(eq("monthly-financial-close"), any(SkillContext.class)))
                .thenReturn(SkillResult.builder()
                        .success(true)
                        .skillName("monthly-financial-close")
                        .data(Map.of("revenue", 100000))
                        .message("📊 期间状态: OPEN")
                        .build());

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("MONTHLY_FINANCIAL_CLOSE")
                .intentName("月度财务复盘")
                .intentCategory("WORKDESK")
                .toolName(null)
                .build();

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                intent, "5月经营怎么样", "F006", 200L);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(mockSkillRouter).executeSkill(eq("monthly-financial-close"), any(SkillContext.class));
    }

    // ==================== tryExplicitSkillRouteForIntent — guards ====================

    @Test
    void tryExplicitSkillRouteForIntent_skillNotRegistered_returnsNull() {
        when(mockSkillRouter.isSkillsEnabled()).thenReturn(true);
        when(mockSkillRouter.executeSkill(any(), any(SkillContext.class)))
                .thenReturn(SkillResult.builder()
                        .success(false)
                        .skillName("nonexistent-skill")
                        .message("未找到Skill: nonexistent-skill")
                        .build());

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("NONEXISTENT_INTENT")
                .intentName("不存在的意图")
                .intentCategory("WORKDESK")
                .toolName(null)
                .build();

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                intent, "随便问点啥", "F006", 100L);

        assertThat(response).isNull();
    }

    @Test
    void tryExplicitSkillRouteForIntent_skillsDisabled_returnsNullNeverInvokesExecute() {
        when(mockSkillRouter.isSkillsEnabled()).thenReturn(false);

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("DAILY_CUSTOMER_FOLLOWUP")
                .intentCategory("WORKDESK")
                .build();

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                intent, "今日跟谁", "F006", 100L);

        assertThat(response).isNull();
        verify(mockSkillRouter, never()).executeSkill(any(), any(SkillContext.class));
    }

    @Test
    void tryExplicitSkillRouteForIntent_nullIntent_returnsNull() {
        when(mockSkillRouter.isSkillsEnabled()).thenReturn(true);

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                null, "随便", "F006", 100L);

        assertThat(response).isNull();
        verify(mockSkillRouter, never()).executeSkill(any(), any(SkillContext.class));
    }

    @Test
    void tryExplicitSkillRouteForIntent_nullIntentCode_returnsNull() {
        when(mockSkillRouter.isSkillsEnabled()).thenReturn(true);

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode(null)
                .intentCategory("WORKDESK")
                .build();

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                intent, "随便", "F006", 100L);

        assertThat(response).isNull();
        verify(mockSkillRouter, never()).executeSkill(any(), any(SkillContext.class));
    }

    @Test
    void tryExplicitSkillRouteForIntent_skillRouterReturnsNull_returnsNull() {
        when(mockSkillRouter.isSkillsEnabled()).thenReturn(true);
        when(mockSkillRouter.executeSkill(any(), any(SkillContext.class)))
                .thenReturn(null);

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("DAILY_CUSTOMER_FOLLOWUP")
                .intentCategory("WORKDESK")
                .build();

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                intent, "今日跟谁", "F006", 100L);

        assertThat(response).isNull();
    }

    @Test
    void tryExplicitSkillRouteForIntent_skillExecutionThrows_surfacesFailedResponse() {
        // PR #182 (Sprint 10.5 P0 #1) changed Skill exception behavior — now surfaces
        // the real error to the user via FAILED IntentExecuteResponse instead of
        // returning null (which previously routed to the misleading
        // "暂不支持此类型的意图执行: WORKDESK" message). Test renamed + assertion updated.
        when(mockSkillRouter.isSkillsEnabled()).thenReturn(true);
        when(mockSkillRouter.executeSkill(any(), any(SkillContext.class)))
                .thenThrow(new RuntimeException("boom"));

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("DAILY_CUSTOMER_FOLLOWUP")
                .intentCategory("WORKDESK")
                .build();

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                intent, "今日跟谁", "F006", 100L);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getIntentCode()).isEqualTo("DAILY_CUSTOMER_FOLLOWUP");
        assertThat(response.getMessage()).contains("boom");
        assertThat(response.getFormattedText()).contains("boom");
    }

    /**
     * Sprint 9 P0.2 (2026-05-22): SkillRouterServiceImpl.executeSkill returns English
     * "Skill not found: xxx", not "未找到Skill: xxx". Test ensures we catch both forms.
     */
    @Test
    void tryExplicitSkillRouteForIntent_englishSkillNotFound_returnsNull() {
        when(mockSkillRouter.isSkillsEnabled()).thenReturn(true);
        when(mockSkillRouter.executeSkill(any(), any(SkillContext.class)))
                .thenReturn(SkillResult.builder()
                        .success(false)
                        .skillName("nonexistent-skill")
                        .message("Skill not found: nonexistent-skill")
                        .build());

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("NONEXISTENT_INTENT")
                .intentCategory("WORKDESK")
                .toolName(null)
                .build();

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                intent, "随便", "F006", 100L);

        assertThat(response).isNull();
    }

    /**
     * Sprint 9 P0.2 (2026-05-22) regression fix — Skill exists but execution failed
     * (LLM timeout / validateContext missing userId / tool unavailable etc.) should NOT return null
     * (which would fall through to "暂不支持此类型的意图执行: WORKDESK"). Instead return FAILED
     * response with actionable message.
     */
    @Test
    void tryExplicitSkillRouteForIntent_skillExistsButExecutionFailed_returnsFailedResponseNotNull() {
        when(mockSkillRouter.isSkillsEnabled()).thenReturn(true);
        when(mockSkillRouter.executeSkill(any(), any(SkillContext.class)))
                .thenReturn(SkillResult.builder()
                        .success(false)
                        .skillName("monthly-financial-close")
                        .message("Missing required context fields: userId")  // not "未找到" / not "Skill not found"
                        .build());

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("MONTHLY_FINANCIAL_CLOSE")
                .intentName("月度财务复盘")
                .intentCategory("WORKDESK")
                .toolName(null)
                .build();

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                intent, "本月经营怎么样", "F006", 200L);

        assertThat(response).isNotNull();  // 关键: 不是 null!
        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getIntentCode()).isEqualTo("MONTHLY_FINANCIAL_CLOSE");
        assertThat(response.getMessage()).contains("Missing required context fields: userId");
        // 不再 fall-through 到 "暂不支持"
        assertThat(response.getMessage()).doesNotContain("暂不支持");
    }

    /**
     * Sprint 9 P0.2 (2026-05-22) — Skill exists but execution failed with LLM timeout
     * (DashScope 429 / connection refused). Same treatment: return FAILED response.
     */
    @Test
    void tryExplicitSkillRouteForIntent_skillExistsButLlmTimeout_returnsFailedResponse() {
        when(mockSkillRouter.isSkillsEnabled()).thenReturn(true);
        when(mockSkillRouter.executeSkill(any(), any(SkillContext.class)))
                .thenReturn(SkillResult.builder()
                        .success(false)
                        .skillName("monthly-financial-close")
                        .message("Failed to call LLM: 429 Too Many Requests")
                        .build());

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("MONTHLY_FINANCIAL_CLOSE")
                .intentName("月度财务复盘")
                .intentCategory("WORKDESK")
                .toolName(null)
                .build();

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                intent, "本月经营", "F006", 200L);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMessage()).contains("LLM");
        assertThat(response.getFormattedText()).doesNotContain("暂不支持");
    }

    @Test
    void tryExplicitSkillRouteForIntent_nullUserId_passesNullToContext() {
        when(mockSkillRouter.isSkillsEnabled()).thenReturn(true);
        when(mockSkillRouter.executeSkill(any(), any(SkillContext.class)))
                .thenReturn(SkillResult.builder()
                        .success(true)
                        .skillName("daily-customer-followup")
                        .data(Map.of("a", 1))
                        .message("ok")
                        .build());

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("DAILY_CUSTOMER_FOLLOWUP")
                .intentCategory("WORKDESK")
                .build();

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                intent, "今日跟谁", "F006", null);

        assertThat(response).isNotNull();
        ArgumentCaptor<SkillContext> ctxCaptor = ArgumentCaptor.forClass(SkillContext.class);
        verify(mockSkillRouter).executeSkill(any(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().getUserId()).isNull();
    }
}
