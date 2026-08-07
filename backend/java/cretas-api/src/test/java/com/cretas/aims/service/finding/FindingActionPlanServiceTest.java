package com.cretas.aims.service.finding;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.grounding.GroundedNumberValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link FindingActionPlanService} 的单测。
 *
 * <p>本文件的 UT-FAP-01..07 原本在 {@code FindingActionPlanToolTest} 上 —— 2026-08-07
 * 把生成逻辑从那个 Tool 抽成本 service 时**跟着逻辑搬过来**，不是重写。搬的原因是
 * 那个 Tool 的领域硬编码为 {@code inventory}，餐饮拿不到自己的行动建议。
 */
@ExtendWith(MockitoExtension.class)
class FindingActionPlanServiceTest {

    private static final String FACTORY_ID = "F006";

    @Mock
    private FindingService findingService;

    @Mock
    private DashScopeClient dashScopeClient;

    /** 用真的校验器 —— 这条链路的价值就在它身上, mock 掉等于没测。 */
    private final GroundedNumberValidator validator = new GroundedNumberValidator();

    private FindingActionPlanService service() {
        return new FindingActionPlanService(findingService, dashScopeClient, validator);
    }

    private static Finding lowStock(String name, int current, int safety, int gap) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("currentStock", current);
        facts.put("safetyStock", safety);
        facts.put("gap", gap);
        facts.put("unit", "kg");
        return new Finding("LOW_STOCK", "inventory", Finding.Severity.WARNING, 50,
                "M-" + name, name, facts);
    }

    private static Finding puzzleDish(String name, double unitMargin) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("unitMargin", unitMargin);
        facts.put("qty", 143188);
        return new Finding("DISH_PUZZLE_HIGH_MARGIN_LOW_VOLUME", "restaurant",
                Finding.Severity.WARNING, 75, name, name, facts);
    }

    private static FindingService.Result result(List<Finding> findings, List<String> failed) {
        return new FindingService.Result(findings, List.of("低库存"), findings.size(),
                Map.of("LOW_STOCK", findings.size()), failed);
    }

    @Test
    @DisplayName("UT-FAP-02: 建议里的数字都有据时正常返回")
    void returnsGroundedPlan() {
        when(findingService.detectInline(FACTORY_ID, "inventory"))
                .thenReturn(result(List.of(lowStock("鲈鱼", 12, 50, 38)), List.of()));
        when(dashScopeClient.chatLowTemp(anyString(), anyString()))
                .thenReturn("· 鲈鱼剩 12kg，低于安全线 50kg\n· 尽快补 38kg");

        Map<String, Object> r = service().generate(FACTORY_ID, "inventory");

        assertEquals(true, r.get("hasPlan"));
        assertTrue(((String) r.get("plan")).contains("38kg"));
        assertEquals(1, ((Number) r.get("basedOnFindings")).intValue());
        assertEquals("inventory", r.get("domain"));
    }

    @Test
    @DisplayName("UT-FAP-03: 🔴 模型编了数字 -> 拒绝返回, 不把文案发出去")
    void rejectsFabricatedNumbers() {
        when(findingService.detectInline(FACTORY_ID, "inventory"))
                .thenReturn(result(List.of(lowStock("鲈鱼", 12, 50, 38)), List.of()));
        when(dashScopeClient.chatLowTemp(anyString(), anyString()))
                .thenReturn("· 尽快补 38kg\n· 预计避免损失 21000 元");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service().generate(FACTORY_ID, "inventory"));

        assertTrue(e.getMessage().contains("21000"),
                "编了数字的行动建议比没有建议更糟 —— 它会被照着执行: " + e.getMessage());
    }

    @Test
    @DisplayName("UT-FAP-04: 🔴 发现层没跑完 -> 拒绝生成, 不拿残缺事实指挥行动")
    void refusesWhenDetectionIncomplete() {
        when(findingService.detectInline(FACTORY_ID, "inventory"))
                .thenReturn(result(List.of(lowStock("鲈鱼", 12, 50, 38)), List.of("临期")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service().generate(FACTORY_ID, "inventory"));

        assertTrue(e.getMessage().contains("临期"), e.getMessage());
        verifyNoInteractions(dashScopeClient);
    }

    @Test
    @DisplayName("UT-FAP-05: 无异常时不调模型, 明说无需建议")
    void noFindingsSkipsLlm() {
        when(findingService.detectInline(FACTORY_ID, "inventory"))
                .thenReturn(result(List.of(), List.of()));

        Map<String, Object> r = service().generate(FACTORY_ID, "inventory");

        assertEquals(false, r.get("hasPlan"));
        assertNull(r.get("plan"));
        assertTrue(((String) r.get("message")).contains("无需"));
        verifyNoInteractions(dashScopeClient);
    }

    @Test
    @DisplayName("UT-FAP-06: 🔴 模型返回空 -> 拒绝, 不返回空方案冒充成功")
    void rejectsEmptyModelOutput() {
        when(findingService.detectInline(FACTORY_ID, "inventory"))
                .thenReturn(result(List.of(lowStock("鲈鱼", 12, 50, 38)), List.of()));
        when(dashScopeClient.chatLowTemp(anyString(), anyString())).thenReturn("   ");

        assertThrows(IllegalStateException.class,
                () -> service().generate(FACTORY_ID, "inventory"));
    }

    @Test
    @DisplayName("UT-FAP-07: 提示词明确禁止数字编号 —— 严格校验的前提")
    void promptForbidsNumberedLists() {
        when(findingService.detectInline(FACTORY_ID, "inventory"))
                .thenReturn(result(List.of(lowStock("鲈鱼", 12, 50, 38)), List.of()));
        when(dashScopeClient.chatLowTemp(anyString(), anyString())).thenReturn("· 补 38kg");

        service().generate(FACTORY_ID, "inventory");

        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        verify(dashScopeClient).chatLowTemp(sys.capture(), anyString());
        String prompt = sys.getValue();
        assertTrue(prompt.contains("禁止使用"), prompt);
        assertTrue(prompt.contains("·"), "必须要求「·」分点, 否则序号会逼校验开后门: " + prompt);
    }

    // ── 2026-08-07 新增: 领域参数化 ─────────────────────────────────────

    @Test
    @DisplayName("UT-FAP-08: 🔴 领域真的被传下去 —— 餐饮不该拿到库存域的发现")
    void queriesTheRequestedDomain() {
        when(findingService.detectInline(FACTORY_ID, "restaurant"))
                .thenReturn(result(List.of(puzzleDish("罗氏虾", 78.57)), List.of()));
        when(dashScopeClient.chatLowTemp(anyString(), anyString()))
                .thenReturn("· 把罗氏虾放到推荐位");

        Map<String, Object> r = service().generate(FACTORY_ID, "restaurant");

        assertEquals("restaurant", r.get("domain"));
        // 这条是本次改动的**根因断言**: 原来的 Tool 里 DOMAIN 是硬编码的 "inventory",
        // 餐饮租户走它拿到的是库存域发现。never() 那句钉住它不会再回去。
        verify(findingService).detectInline(FACTORY_ID, "restaurant");
        verify(findingService, never()).detectInline(FACTORY_ID, "inventory");
    }

    @Test
    @DisplayName("UT-FAP-09: 🔴 身份随领域走 —— 对店长不能自称「食品加工厂的生产助理」")
    void personaFollowsDomain() {
        when(findingService.detectInline(FACTORY_ID, "restaurant"))
                .thenReturn(result(List.of(puzzleDish("罗氏虾", 78.57)), List.of()));
        when(dashScopeClient.chatLowTemp(anyString(), anyString())).thenReturn("· 放推荐位");

        service().generate(FACTORY_ID, "restaurant");

        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        verify(dashScopeClient).chatLowTemp(sys.capture(), anyString());
        String prompt = sys.getValue();
        assertTrue(prompt.contains("餐厅"), prompt);
        assertFalse(prompt.contains("加工厂"),
                "餐饮与工厂是两个产品, 身份错了不只是措辞问题: " + prompt);
        // 四条硬性要求对两个领域必须逐字相同 —— 各写一份是让两边漂开的起点。
        assertTrue(prompt.contains("禁止使用"), prompt);
    }

    @Test
    @DisplayName("UT-FAP-10: 🔴 无发现但有规则判不了 -> 不能说成「没有异常」")
    void skippedRulesSurviveTheNoIssuePath() {
        FindingService.Result withSkip = new FindingService.Result(
                List.of(), List.of("菜品毛利谜题"), 0, Map.of(), List.of(),
                List.of(new FindingService.SkippedRule("食材损耗离群", "两期名单不可比")));
        when(findingService.detectInline(FACTORY_ID, "restaurant")).thenReturn(withSkip);

        Map<String, Object> r = service().generate(FACTORY_ID, "restaurant");

        assertEquals(false, r.get("hasPlan"));
        @SuppressWarnings("unchecked")
        List<FindingService.SkippedRule> skipped =
                (List<FindingService.SkippedRule>) r.get("skippedRules");
        assertEquals(1, skipped.size());
        assertTrue(((String) r.get("message")).contains("数据不足"),
                "「真的没有」和「判不了」必须是两句话, 否则三态在这个出口塌回两态: "
                        + r.get("message"));
        verifyNoInteractions(dashScopeClient);
    }
}
