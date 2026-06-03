package com.cretas.aims.ai.tool;

import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.intent.IntentMatchResult.CandidateIntent;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.QueryPreprocessorService.NegationInfo;
import com.cretas.aims.service.QueryPreprocessorService.NegationKind;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.assertThat;

class NegationTwinPolicyTest {

    private final NegationTwinPolicy policy = new NegationTwinPolicy(new WriteGuardService());

    private CandidateIntent c(String code, double conf) {
        return CandidateIntent.builder().intentCode(code).confidence(conf).build();
    }
    private AIIntentConfig cfg(String code, String sens) {
        AIIntentConfig a = new AIIntentConfig();
        a.setIntentCode(code); a.setSensitivityLevel(sens);
        return a;
    }
    /** resolver: known codes → config w/ sensitivity; unknown → null */
    private Function<String, AIIntentConfig> resolver(Map<String,String> sens) {
        return code -> sens.containsKey(code) ? cfg(code, sens.get(code)) : null;
    }

    @Test
    void vetoRead_removesAllCandidates_includingWrite() {
        var in = List.of(c("INVENTORY_CLEAR", 0.75), c("MATERIAL_BATCH_QUERY", 0.72));
        var neg = NegationInfo.builder().kind(NegationKind.VETO_READ).hasNegation(true).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, IntentKnowledgeBase.ActionType.QUERY,
                resolver(Map.of("INVENTORY_CLEAR", "CRITICAL")));
        assertThat(out).isEmpty();                       // 不用查库存了 → 抑制
        assertThat(out).noneMatch(x -> x.getIntentCode().equals("INVENTORY_CLEAR"));
    }

    @Test
    void vetoWrite_convertsToReadTwin() {
        var in = List.of(c("PROCESSING_BATCH_START", 0.85));
        var neg = NegationInfo.builder().kind(NegationKind.VETO_WRITE).hasNegation(true).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, IntentKnowledgeBase.ActionType.UPDATE,
                resolver(Map.of()));
        assertThat(out).extracting(CandidateIntent::getIntentCode)
                .containsExactly("PROCESSING_BATCH_LIST");   // 别开始生产 → 读孪生
    }

    @Test
    void vetoWrite_dropsWriteWithoutTwin_safetyInvariant() {
        var in = List.of(c("SOME_WRITE_DELETE", 0.9));   // _DELETE suffix → write, no twin
        var neg = NegationInfo.builder().kind(NegationKind.VETO_WRITE).hasNegation(true).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, IntentKnowledgeBase.ActionType.DELETE, resolver(Map.of()));
        assertThat(out).isEmpty();                        // 无孪生 → 剔除,绝不留写
    }

    @Test
    void component2_promotesReadOverWriteTwin_withinMargin() {
        var in = List.of(c("PROCESSING_BATCH_COMPLETE", 0.76), c("REPORT_PRODUCTION", 0.74));
        var neg = NegationInfo.builder().kind(NegationKind.NONE).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, IntentKnowledgeBase.ActionType.QUERY, resolver(Map.of()));
        assertThat(out.get(0).getIntentCode()).isEqualTo("REPORT_PRODUCTION");  // 生产进度怎么样 偏读
    }

    @Test
    void component2_doesNotFire_whenWriteVerb() {
        var in = List.of(c("PROCESSING_BATCH_COMPLETE", 0.9), c("REPORT_PRODUCTION", 0.85));
        var neg = NegationInfo.builder().kind(NegationKind.NONE).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, IntentKnowledgeBase.ActionType.UPDATE, resolver(Map.of()));
        assertThat(out.get(0).getIntentCode()).isEqualTo("PROCESSING_BATCH_COMPLETE"); // 完成生产 保持写
    }

    @Test
    void kindNone_readPhrased_noWriteTop_unchanged() {
        var in = List.of(c("MATERIAL_BATCH_QUERY", 0.9), c("MATERIAL_BATCH_CREATE", 0.5));
        var neg = NegationInfo.builder().kind(NegationKind.NONE).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, IntentKnowledgeBase.ActionType.QUERY, resolver(Map.of()));
        assertThat(out.get(0).getIntentCode()).isEqualTo("MATERIAL_BATCH_QUERY"); // top 已是读 → 不动
    }

    @Test
    void readTwinOf_knownAndUnknown() {
        assertThat(policy.readTwinOf("INVENTORY_CLEAR")).isEqualTo("INVENTORY_QUERY");
        assertThat(policy.readTwinOf("SHIPMENT_CREATE")).isEqualTo("SHIPMENT_QUERY");
        assertThat(policy.readTwinOf("NOT_A_WRITE")).isNull();
    }

    @Test
    void isVetoToClarification_trueOnlyWhenVetoEmptiedNonEmpty() {
        var negRead = NegationInfo.builder().kind(NegationKind.VETO_READ).build();
        assertThat(policy.isVetoToClarification(List.of(c("X",0.5)), List.of(), negRead)).isTrue();
        var negNone = NegationInfo.builder().kind(NegationKind.NONE).build();
        assertThat(policy.isVetoToClarification(List.of(c("X",0.5)), List.of(), negNone)).isFalse();
    }

    @Test
    void nullOrEmptyCandidates_returnedAsIs() {
        assertThat(policy.applyNegationVetoAndTwinRerank(List.of(), null, IntentKnowledgeBase.ActionType.QUERY, c -> null)).isEmpty();
    }

    @Test
    void vetoWrite_treatsHighSensitivityConfigAsWrite_evenWithoutSuffix() {
        var in = List.of(c("DASHBOARD_OVERVIEW", 0.9));          // no write suffix
        var neg = NegationInfo.builder().kind(NegationKind.VETO_WRITE).hasNegation(true).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, IntentKnowledgeBase.ActionType.QUERY,
                resolver(Map.of("DASHBOARD_OVERVIEW", "HIGH")));  // HIGH sensitivity → isWriteIntent==true
        assertThat(out).isEmpty();   // treated as write, no twin → dropped by safety invariant
    }

    @Test
    void nullCandidates_returnedAsIs() {
        assertThat(policy.applyNegationVetoAndTwinRerank(null, null, IntentKnowledgeBase.ActionType.QUERY, code -> null)).isNull();
    }

    @Test
    void isVetoToClarification_trueForVetoWriteAllDropped() {
        var neg = NegationInfo.builder().kind(NegationKind.VETO_WRITE).build();
        assertThat(policy.isVetoToClarification(List.of(c("SOME_DELETE", 0.9)), List.of(), neg)).isTrue();
    }
}
