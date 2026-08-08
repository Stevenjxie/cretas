package com.cretas.aims.service.execution;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit tests for {@link RestaurantFindingHintAppender}. */
@ExtendWith(MockitoExtension.class)
class RestaurantFindingHintAppenderTest {

    private static final String FACTORY_ID = "MOCK_REST";
    private static final String ANSWER = "近7天损耗合计 ¥71.5 万。";

    @InjectMocks
    private RestaurantFindingHintAppender appender;

    @Mock
    private FindingService findingService;

    @Mock
    private FindingTextRenderer findingTextRenderer;

    private static FindingService.Result oneFinding() {
        Finding f = new Finding("WASTAGE_TYPE_CONCENTRATION", "restaurant",
                Finding.Severity.INFO, 70, "变质", "变质",
                Map.of("cost", 265047.0, "share", 37.1, "windowDays", 7));
        return new FindingService.Result(List.of(f), List.of("损耗类型集中度"), 1,
                Map.of("WASTAGE_TYPE_CONCENTRATION", 1), List.of(), List.of());
    }

    @Test
    @DisplayName("UT-RFH-01: 把顺带提示拼到回答末尾，原回答逐字保留")
    void appendsHintAfterAnswer() {
        when(findingService.detectInline(eq(FACTORY_ID), anyCollection(), any())).thenReturn(oneFinding());
        when(findingTextRenderer.renderInline(any()))
                .thenReturn("⚠️ 顺带 1 件事：\n · 变质损耗近7天 ¥265047.0，占全店损耗 37.1%");

        String out = appender.append(ANSWER, FACTORY_ID, false);

        assertTrue(out.startsWith(ANSWER), "原回答必须逐字保留在最前: " + out);
        assertTrue(out.contains("变质"), out);
        assertTrue(out.contains("37.1"), out);
    }

    @Test
    @DisplayName("UT-RFH-02: 查发现层时**同时**带上 restaurant 与 inventory 两个域")
    void usesRestaurantAndInventoryDomains() {
        // 🔴 2026-08-08 改: 原来只查 "restaurant" 单域, 而低库存发现由
        //    LowStockFindingProvider 提供、domain 是 "inventory",
        //    FindingServiceImpl 又是逐字 equals 比对 ——
        //    **库存异常永远到不了店长眼前**。能力在、数据通道在, 只差这根线。
        // ⛔ 一次调用传两个域, 不是调两次: inline 上限要在合并后的全集上截断,
        //    分别截断会让两个域各占名额, 把真正最要紧的那条挤掉。
        when(findingService.detectInline(anyString(), anyCollection(), any())).thenReturn(oneFinding());
        when(findingTextRenderer.renderInline(any())).thenReturn("x");

        appender.append(ANSWER, FACTORY_ID, false);

        ArgumentCaptor<java.util.Collection<String>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(findingService).detectInline(eq(FACTORY_ID), captor.capture(), any());
        assertTrue(captor.getValue().contains("restaurant"), "缺 restaurant 域");
        assertTrue(captor.getValue().contains("inventory"), "缺 inventory 域(库存异常带不出来)");
    }

    @Test
    @DisplayName("UT-RFH-03: 🔴 澄清反问不挂提示 —— 在「你想看哪家门店」下面接发现是坏体验")
    void skipsWhenAwaitingClarification() {
        String clarification = "你想看哪家门店的损耗？";

        String out = appender.append(clarification, FACTORY_ID, true);

        assertEquals(clarification, out);
        verifyNoInteractions(findingService);
        verifyNoInteractions(findingTextRenderer);
    }

    @Test
    @DisplayName("UT-RFH-04: 渲染出空串时不留下尾随空行")
    void emptyHintLeavesAnswerUntouched() {
        when(findingService.detectInline(anyString(), anyCollection(), any())).thenReturn(
                new FindingService.Result(List.of(), List.of(), 0, Map.of(), List.of(), List.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("");

        assertEquals(ANSWER, appender.append(ANSWER, FACTORY_ID, false));
    }

    @Test
    @DisplayName("UT-RFH-05: 空/null 回答原样返回，不去查发现层")
    void blankAnswerIsPassedThrough() {
        assertNull(appender.append(null, FACTORY_ID, false));
        assertEquals("  ", appender.append("  ", FACTORY_ID, false));
        verifyNoInteractions(findingService);
    }

    @Test
    @DisplayName("UT-RFH-06: 🔴 发现层炸了不能拖垮主回答 —— 原回答必须原样送达")
    void findingFailureNeverBreaksTheAnswer() {
        when(findingService.detectInline(anyString(), anyCollection(), any()))
                .thenThrow(new IllegalStateException("boom"));

        assertEquals(ANSWER, appender.append(ANSWER, FACTORY_ID, false),
                "顺带提示是附加物, 它坏了不该让店长连主回答都拿不到");
    }

    @Test
    @DisplayName("UT-RFH-07: 规则失败的话术由 renderer 负责，appender 不吞它")
    void ruleFailureTextIsForwarded() {
        when(findingService.detectInline(anyString(), anyCollection(), any())).thenReturn(
                new FindingService.Result(List.of(), List.of(), 0, Map.of(),
                        List.of("损耗类型集中度"), List.of()));
        when(findingTextRenderer.renderInline(any()))
                .thenReturn("⚠️ 另有 损耗类型集中度 检查失败，暂无法判断。");

        String out = appender.append(ANSWER, FACTORY_ID, false);

        assertTrue(out.contains("检查失败"),
                "规则失败必须说出来, 静默吞掉就是把失败渲染成正常: " + out);
    }

    @Test
    @DisplayName("顺带提示覆盖的域, 必须包含店长会关心的每一类发现")
    void hintDomainsCoverEveryProviderTheBossCaresAbout() throws Exception {
        // 🔴 2026-08-08 实测的真实缺口: LowStockFindingProvider 早就存在,
        //    但它的 domain 是 "inventory", 而这里写死 "restaurant" 单域,
        //    `FindingServiceImpl` 又是逐字 equals 比对 ——
        //    **库存异常永远到不了店长眼前**。能力在、数据通道在, 只差这根线。
        //
        // ⛔ 这条闸判的是**接线**, 不是数据: MOCK_REST 的 material_batches 今天
        //    是 0 行, 所以接上之后依然不会有提示 —— 那是对的(没异常就不带),
        //    但线必须先接上, 数据来了才会自动生效。
        //
        // ⇒ 判据: 新增一个店长会关心的 FindingProvider 时, 如果它的 domain
        //   不在这张表里, 这条直接红 —— 不靠谁记得。
        java.lang.reflect.Field f =
                RestaurantFindingHintAppender.class.getDeclaredField("DOMAINS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<String> domains = (java.util.List<String>) f.get(null);

        assertTrue(domains.contains("restaurant"),
                "缺 restaurant 域: 谜题菜品/损耗集中/损耗占比突增都取不到");
        assertTrue(domains.contains("inventory"),
                "缺 inventory 域: 店长问任何问题都该看到食材快没了");
    }

    @Test
    @DisplayName("UT-RFH-20: 🔴 答案里已经讲过的发现, 不在末尾重复")
    void dropsFindingsTheAnswerAlreadyCovered() {
        // Steve 2026-08-08: 「得确保 AI 知道当前是不是在问这个问题, 如果是的话
        // 那就不用提示」。老板问「损耗怎么样」, 答案已经把**变质**摆出来了,
        // 末尾再挂一条「变质损耗占比过高」—— 他刚读完的那段里就有这个数字。
        when(findingService.detectInline(eq(FACTORY_ID), anyCollection(), any())).thenReturn(oneFinding());

        String answerAboutWastage = "近30天损耗总览: 变质 ¥26.5 万、加工损耗 ¥8.1 万。";
        String out = appender.append(answerAboutWastage, FACTORY_ID, false);

        assertEquals(answerAboutWastage, out, "答案已覆盖的发现不该再挂一遍");
        verify(findingTextRenderer).renderInline(argThat(
                r -> r.findings().isEmpty()
                        && !r.checkedRules().isEmpty()));
    }

    @Test
    @DisplayName("UT-RFH-21: ⛔ 跨域但答案没提的, **必须**照常提示")
    void keepsCrossDomainFindingsTheAnswerDidNotMention() {
        // 判据用的是「对象名在不在答案里」而**不是域匹配**:
        // 老板问营收, 挂「折扣/损耗」是跨域的, 但它恰恰改变了他对刚才那个
        // 营收数字的理解 —— 域匹配会把这类最该说的误杀掉。
        when(findingService.detectInline(eq(FACTORY_ID), anyCollection(), any())).thenReturn(oneFinding());
        when(findingTextRenderer.renderInline(any())).thenReturn("⚠️ 变质损耗占比过高");

        String answerAboutRevenue = "最近30天营收 ¥7,812 万，单量 18.5 万单。";
        String out = appender.append(answerAboutRevenue, FACTORY_ID, false);

        assertTrue(out.contains("变质损耗占比过高"), "答案没提的, 跨域也要说");
        verify(findingTextRenderer).renderInline(argThat(r -> r.findings().size() == 1));
    }

    @Test
    @DisplayName("UT-RFH-22: ⛔ 全部被去掉时**保留 checkedRules** —— 「查过了」不能塌成「没查」")
    void keepsCheckedRulesWhenEverythingWasAlreadyCovered() {
        when(findingService.detectInline(eq(FACTORY_ID), anyCollection(), any())).thenReturn(oneFinding());

        appender.append("损耗里变质最多。", FACTORY_ID, false);

        verify(findingTextRenderer).renderInline(argThat(
                r -> r.findings().isEmpty()
                        && r.checkedRules().contains("损耗类型集中度")
                        && r.totalCount() == 1));
    }

    @Test
    @DisplayName("UT-RFH-23: 🔴 同步提示必须用 ACT_NOW 排序 —— 不是默认的严重度主导")
    void syncHintUsesActNowOrdering() {
        // 同步提示是打断式的、只有 2 个名额, 要的是「你现在能做点什么」。
        // 默认排序(IMPACT_FIRST)下**最高的 WARNING 是 299、最低的 CRITICAL 是 300**,
        // 已经无可挽回的事会稳定霸占那 2 个名额 —— 与顺带提示的目的正好相反。
        // ⇒ 这条钉住「用了哪个排序」, 否则以后有人改回默认不会被任何断言发现。
        when(findingService.detectInline(anyString(), anyCollection(), any()))
                .thenReturn(oneFinding());
        when(findingTextRenderer.renderInline(any())).thenReturn("x");

        appender.append("最近30天营收 ¥7,812 万。", FACTORY_ID, false);

        verify(findingService).detectInline(eq(FACTORY_ID), anyCollection(),
                eq(com.cretas.aims.service.finding.FindingOrdering.ACT_NOW));
    }
}
