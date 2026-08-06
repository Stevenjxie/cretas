package com.cretas.aims.service.execution;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        when(findingService.detectInline(FACTORY_ID, "restaurant")).thenReturn(oneFinding());
        when(findingTextRenderer.renderInline(any()))
                .thenReturn("⚠️ 顺带 1 件事：\n · 变质损耗近7天 ¥265047.0，占全店损耗 37.1%");

        String out = appender.append(ANSWER, FACTORY_ID, false);

        assertTrue(out.startsWith(ANSWER), "原回答必须逐字保留在最前: " + out);
        assertTrue(out.contains("变质"), out);
        assertTrue(out.contains("37.1"), out);
    }

    @Test
    @DisplayName("UT-RFH-02: 用 restaurant 这个 domain 查发现层")
    void usesRestaurantDomain() {
        when(findingService.detectInline(anyString(), anyString())).thenReturn(oneFinding());
        when(findingTextRenderer.renderInline(any())).thenReturn("x");

        appender.append(ANSWER, FACTORY_ID, false);

        verify(findingService).detectInline(FACTORY_ID, "restaurant");
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
        when(findingService.detectInline(anyString(), anyString())).thenReturn(
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
        when(findingService.detectInline(anyString(), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        assertEquals(ANSWER, appender.append(ANSWER, FACTORY_ID, false),
                "顺带提示是附加物, 它坏了不该让店长连主回答都拿不到");
    }

    @Test
    @DisplayName("UT-RFH-07: 规则失败的话术由 renderer 负责，appender 不吞它")
    void ruleFailureTextIsForwarded() {
        when(findingService.detectInline(anyString(), anyString())).thenReturn(
                new FindingService.Result(List.of(), List.of(), 0, Map.of(),
                        List.of("损耗类型集中度"), List.of()));
        when(findingTextRenderer.renderInline(any()))
                .thenReturn("⚠️ 另有 损耗类型集中度 检查失败，暂无法判断。");

        String out = appender.append(ANSWER, FACTORY_ID, false);

        assertTrue(out.contains("检查失败"),
                "规则失败必须说出来, 静默吞掉就是把失败渲染成正常: " + out);
    }
}
