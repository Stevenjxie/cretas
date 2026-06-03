package com.cretas.aims.service.impl;

import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.service.QueryPreprocessorService.NegationKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W1b T2: 否定否决细分检测单测。
 *
 * <p>核对真实代码后采用<b>参数形式</b> {@code detectNegationVeto(String, IntentKnowledgeBase)}:
 * {@code QueryPreprocessorServiceImpl} 未注入 {@code IntentKnowledgeBase}(无该字段, ctor 7 参不含它),
 * 故 {@code kb} 由调用方(IRP, T3/T4)持有并传入。本测试直接传 mock kb,
 * 避免 plan {@code forNegationTest} 工厂对 ctor arity 的错误猜测。</p>
 *
 * <p>覆盖 spec §9 否定检测边界: VETO_READ / VETO_WRITE / 双重否定守卫(finding 6) /
 * 合法写动词无前置否定副词 / 普通读。</p>
 */
class QueryPreprocessorNegationVetoTest {

    private QueryPreprocessorServiceImpl svc;
    private IntentKnowledgeBase kb;

    @BeforeEach
    void setup() {
        // detectNegationVeto 仅需 knowledge base 判定否定副词后动词的 ActionType。
        kb = mock(IntentKnowledgeBase.class);
        // 默认 stub: 未匹配的输入返 UNKNOWN(确定性,且 lenient 避免 strict-stubs 抱怨未用)
        lenient().when(kb.detectActionType(any())).thenReturn(IntentKnowledgeBase.ActionType.UNKNOWN);
        // remainder verb → ActionType (mirror real detectActionType behavior for the test inputs)
        when(kb.detectActionType(contains("查"))).thenReturn(IntentKnowledgeBase.ActionType.QUERY);
        when(kb.detectActionType(contains("看"))).thenReturn(IntentKnowledgeBase.ActionType.QUERY);
        when(kb.detectActionType(contains("开始"))).thenReturn(IntentKnowledgeBase.ActionType.UPDATE);
        when(kb.detectActionType(contains("创建"))).thenReturn(IntentKnowledgeBase.ActionType.CREATE);
        // QPSImpl 不注入 kb,直接构造仅供调用纯函数 detectNegationVeto(String, IntentKnowledgeBase)。
        svc = new QueryPreprocessorServiceImpl(
                null, null, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper(), null, null);
    }

    @Test
    void vetoRead_buchaKucun() {
        assertThat(svc.detectNegationVeto("不用查库存了", kb)).isEqualTo(NegationKind.VETO_READ);
    }

    @Test
    void vetoRead_bieGeiWoKan() {
        assertThat(svc.detectNegationVeto("别给我看订单", kb)).isEqualTo(NegationKind.VETO_READ);
    }

    @Test
    void vetoWrite_bieKaishiShengchan() {
        assertThat(svc.detectNegationVeto("别开始生产了", kb)).isEqualTo(NegationKind.VETO_WRITE);
    }

    @Test
    void vetoWrite_buyongChuangjian() {
        assertThat(svc.detectNegationVeto("不用创建订单", kb)).isEqualTo(NegationKind.VETO_WRITE);
    }

    @Test
    void doubleNegative_guard_returnsNone() {
        // finding 6: 双重否定守卫优先,即便 "不想" 在否定副词表里
        assertThat(svc.detectNegationVeto("不是不想查库存", kb)).isEqualTo(NegationKind.NONE);
        assertThat(svc.detectNegationVeto("不能不查质检", kb)).isEqualTo(NegationKind.NONE);
    }

    @Test
    void legitWrite_noLeadingNegation_none() {
        // 取消 / 作废 是动作动词,不是否定副词 → 不得被 veto
        assertThat(svc.detectNegationVeto("取消订单", kb)).isEqualTo(NegationKind.NONE);
        assertThat(svc.detectNegationVeto("作废这张单", kb)).isEqualTo(NegationKind.NONE);
    }

    @Test
    void plainQuery_none() {
        assertThat(svc.detectNegationVeto("查库存", kb)).isEqualTo(NegationKind.NONE);
    }

    @Test
    void nullInput_returnsNone() {
        assertThat(svc.detectNegationVeto(null, kb)).isEqualTo(NegationKind.NONE);
    }

    @Test
    void emptyInput_returnsNone() {
        assertThat(svc.detectNegationVeto("  ", kb)).isEqualTo(NegationKind.NONE);
    }

    @Test
    void midSentenceUnusedContent_stillVetoRead() {
        // "别给我查不用的那批货" — 别 leads, 不用的 is mid-sentence content, must NOT false-friend out
        when(kb.detectActionType(contains("查"))).thenReturn(IntentKnowledgeBase.ActionType.QUERY);
        assertThat(svc.detectNegationVeto("别给我查不用的那批货", kb)).isEqualTo(NegationKind.VETO_READ);
    }

    @Test
    void sentenceStartUnused_isNotVeto() {
        // "不用的工具有哪些" — attributive 不用的 at start → NONE (a query about unused tools, not a veto)
        assertThat(svc.detectNegationVeto("不用的工具有哪些", kb)).isEqualTo(NegationKind.NONE);
    }

    @Test
    void exclusionStillProducesExcludeContent() {
        // 2-arg detectNegationSemantics with an exclusion word but no veto adverb → EXCLUDE_CONTENT preserved
        var ni = svc.detectNegationSemantics("查订单除了已完成的", kb);
        assertThat(ni.getKind()).isEqualTo(NegationKind.EXCLUDE_CONTENT);
    }
}
