package com.cretas.aims.service.intent.impl;

import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.service.QueryPreprocessorService.NegationKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W1b T3 (finding 7): early VETO gate.
 *
 * <p>These tests exercise the two extracted pure helpers that carry the gate's decision so the
 * safety-critical logic is unit-testable WITHOUT spinning up the full Spring pipeline (that is T5):
 * <ul>
 *   <li>{@link IntentRecognitionPipelineServiceImpl#buildNegationClarificationResult(String)} —
 *       builds the VETO_READ clarification result (no write, bestMatch null, clarification message).</li>
 *   <li>{@link IntentRecognitionPipelineServiceImpl#earlyVetoGateResultOrNull(NegationKind, String)} —
 *       VETO_READ → clarification; everything else → null (gate does not fire).</li>
 * </ul>
 *
 * <p>Both helpers are static / pure (no instance state, no Spring deps) so a plain {@code new}-free
 * static invocation is sufficient.
 */
class IntentPipelineEarlyVetoGateTest {

    private static final String CLARIFY_MSG = "您是要取消这次操作吗?需要我帮您查询或处理什么?";

    // ---- buildNegationClarificationResult ----

    @Test
    void buildClarification_isNonNull_noWrite_carriesMessage() {
        IntentMatchResult r = IntentRecognitionPipelineServiceImpl
                .buildNegationClarificationResult("不用查库存了");
        assertThat(r).isNotNull();
        assertThat(r.getBestMatch()).isNull();                       // 不产出任何写意图
        assertThat(r.getClarificationQuestion()).isEqualTo(CLARIFY_MSG);
        assertThat(r.getUserInput()).isEqualTo("不用查库存了");
    }

    @Test
    void buildClarification_doesNotRequireWriteConfirmation() {
        IntentMatchResult r = IntentRecognitionPipelineServiceImpl
                .buildNegationClarificationResult("别给我看订单");
        // 这是"否决"澄清,不是写确认 —— requiresConfirmation 必须为 false
        assertThat(r.getRequiresConfirmation()).isFalse();
    }

    @Test
    void buildClarification_confidenceNonNull_forRecordPersistence() {
        // saveIntentMatchRecord 调 BigDecimal.valueOf(result.getConfidence()) → confidence 不可为 null
        IntentMatchResult r = IntentRecognitionPipelineServiceImpl
                .buildNegationClarificationResult("不用查库存了");
        assertThat(r.getConfidence()).isNotNull();
        assertThat(r.getConfidence()).isEqualTo(0.0);
    }

    @Test
    void buildClarification_isRejectedMethod() {
        IntentMatchResult r = IntentRecognitionPipelineServiceImpl
                .buildNegationClarificationResult("不用查库存了");
        // 镜像既有 clarification 路径(IRP:587 setMatchMethod(REJECTED))
        assertThat(r.getMatchMethod()).isEqualTo(IntentMatchResult.MatchMethod.REJECTED);
    }

    @Test
    void buildClarification_nullInput_stillBuildsClarification() {
        IntentMatchResult r = IntentRecognitionPipelineServiceImpl
                .buildNegationClarificationResult(null);
        assertThat(r).isNotNull();
        assertThat(r.getBestMatch()).isNull();
        assertThat(r.getClarificationQuestion()).isEqualTo(CLARIFY_MSG);
    }

    // ---- earlyVetoGateResultOrNull ----

    @Test
    void gate_vetoRead_returnsClarification() {
        IntentMatchResult r = IntentRecognitionPipelineServiceImpl
                .earlyVetoGateResultOrNull(NegationKind.VETO_READ, "不用查库存了");
        assertThat(r).isNotNull();
        assertThat(r.getBestMatch()).isNull();
        assertThat(r.getClarificationQuestion()).isEqualTo(CLARIFY_MSG);
    }

    @Test
    void gate_vetoWrite_returnsNull_handledByFlagGuard() {
        // VETO_WRITE 不在早期门返回澄清 —— 由 negationVetoWrite 标志守卫三个短路,落到下游 policy
        assertThat(IntentRecognitionPipelineServiceImpl
                .earlyVetoGateResultOrNull(NegationKind.VETO_WRITE, "别开始生产了")).isNull();
    }

    @Test
    void gate_none_returnsNull() {
        assertThat(IntentRecognitionPipelineServiceImpl
                .earlyVetoGateResultOrNull(NegationKind.NONE, "查库存")).isNull();
    }

    @Test
    void gate_excludeContent_returnsNull() {
        // EXCLUDE_CONTENT(查订单除了已完成的)走既有 exclusion 逻辑,早期门不介入
        assertThat(IntentRecognitionPipelineServiceImpl
                .earlyVetoGateResultOrNull(NegationKind.EXCLUDE_CONTENT, "查订单除了已完成的")).isNull();
    }

    @Test
    void gate_nullKind_returnsNull() {
        assertThat(IntentRecognitionPipelineServiceImpl
                .earlyVetoGateResultOrNull(null, "查库存")).isNull();
    }
}
