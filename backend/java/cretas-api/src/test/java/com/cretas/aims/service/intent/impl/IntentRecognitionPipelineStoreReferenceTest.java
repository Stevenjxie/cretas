package com.cretas.aims.service.intent.impl;

import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.EntitySlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IntentRecognitionPipeline STORE reference handling")
class IntentRecognitionPipelineStoreReferenceTest {

    @Test
    @DisplayName("store reference follow-up bypasses pre-preprocess phrase shortcut")
    void storeReferenceFollowUpBypassesPrePreprocessPhraseShortcut() {
        assertThat(IntentRecognitionPipelineServiceImpl
                .shouldBypassEarlyPhraseShortcutForStoreReference("那家店的客单价呢")).isTrue();
        assertThat(IntentRecognitionPipelineServiceImpl
                .shouldBypassEarlyPhraseShortcutForStoreReference("这家的客单价呢")).isTrue();
        assertThat(IntentRecognitionPipelineServiceImpl
                .shouldBypassEarlyPhraseShortcutForStoreReference("那它的毛利率也是第一吗")).isTrue();
        assertThat(IntentRecognitionPipelineServiceImpl
                .shouldBypassEarlyPhraseShortcutForStoreReference("客单价")).isFalse();
        assertThat(IntentRecognitionPipelineServiceImpl
                .shouldBypassEarlyPhraseShortcutForStoreReference("哪家店业绩最好")).isFalse();
    }

    @Test
    @DisplayName("store slot is copied into preprocessed references when QPS misses it")
    void storeSlotIsCopiedIntoPreprocessedReferences() {
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-store")
                .build();
        context.setSlot(EntitySlot.SlotType.STORE, EntitySlot.store("11", "青花椒大丸百货店"));

        PreprocessedQuery result = IntentRecognitionPipelineServiceImpl.ensureStoreReferenceResolved(
                "那家店的客单价呢",
                "门店 青花椒大丸百货店的客单价呢",
                context,
                PreprocessedQuery.builder()
                        .originalInput("那家店的客单价呢")
                        .finalQuery("门店 青花椒大丸百货店的客单价呢")
                        .build());

        assertThat(result.getResolvedReferences()).containsKey("那家店");
        PreprocessedQuery.ResolvedReference reference = result.getResolvedReferences().get("那家店");
        assertThat(reference.getEntityType()).isEqualTo("STORE");
        assertThat(reference.getEntityId()).isEqualTo("11");
        assertThat(reference.getEntityName()).isEqualTo("青花椒大丸百货店");
    }

    @Test
    @DisplayName("ambiguous pronoun is copied as STORE when there is no DISH slot")
    void ambiguousPronounUsesStoreSlotWhenDishSlotIsAbsent() {
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-store-pronoun")
                .build();
        context.setSlot(EntitySlot.SlotType.STORE, EntitySlot.store("11", "青花椒大丸百货店"));

        PreprocessedQuery result = IntentRecognitionPipelineServiceImpl.ensureStoreReferenceResolved(
                "那它的毛利率也是第一吗",
                "门店 青花椒大丸百货店的毛利率也是第一吗",
                context,
                PreprocessedQuery.builder()
                        .originalInput("那它的毛利率也是第一吗")
                        .finalQuery("门店 青花椒大丸百货店的毛利率也是第一吗")
                        .build());

        assertThat(result.getResolvedReferences()).containsKey("那它");
        assertThat(result.getResolvedReferences().get("那它").getEntityType()).isEqualTo("STORE");
        assertThat(result.getResolvedReferences().get("那它").getEntityName()).isEqualTo("青花椒大丸百货店");
    }
}
