package com.cretas.aims.service.intent.impl;

import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.EntitySlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T108/D2 regression tests for dish coref wiring.
 *
 * <p>Root cause: ensureDishReferenceResolved was absent — STORE had a fallback but DISH did not.
 * When "那道菜" was resolved by performCoreferenceResolution to the actual dish name in
 * processedInput, preprocess() received the already-substituted text and found no pattern match →
 * resolvedReferences was empty → ToolDispatch never injected dish_name/dish_id → returned all Top5.
 *
 * <p>Fix: ensureDishReferenceResolved mirrors ensureStoreReferenceResolved: it checks the RAW
 * originalInput (before any coref substitution) for dish reference patterns, then injects directly.
 */
@DisplayName("IntentRecognitionPipeline DISH coref (T108/D2 fix)")
class IntentRecognitionPipelineDishReferenceTest {

    // ==================== ensureDishReferenceResolved ====================

    @Test
    @DisplayName("D2 fix: dish slot injected from originalInput when processedInput already substituted")
    void dishSlotInjectedWhenProcessedInputAlreadySubstituted() {
        // Simulate: D1 set DISH slot → D2 "那道菜的销量" → performCoreferenceResolution already
        // replaced "那道菜" → processedInput = "招牌青花椒味的销量"
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-dish-001")
                .build();
        context.setSlot(EntitySlot.SlotType.DISH, EntitySlot.dish("prod-007", "招牌青花椒味"));

        PreprocessedQuery result = IntentRecognitionPipelineServiceImpl.ensureDishReferenceResolved(
                "那道菜的销量是多少",          // originalInput — still has "那道菜"
                "招牌青花椒味的销量是多少",    // processedInput — already substituted
                context,
                PreprocessedQuery.builder()
                        .originalInput("那道菜的销量是多少")
                        .finalQuery("招牌青花椒味的销量是多少")
                        .build());

        assertThat(result.getResolvedReferences()).containsKey("那道菜");
        PreprocessedQuery.ResolvedReference ref = result.getResolvedReferences().get("那道菜");
        assertThat(ref.getEntityType()).isEqualToIgnoringCase("DISH");
        assertThat(ref.getEntityId()).isEqualTo("prod-007");
        assertThat(ref.getEntityName()).isEqualTo("招牌青花椒味");
    }

    @ParameterizedTest(name = "pattern ''{0}'' triggers DISH injection")
    @ValueSource(strings = {"那道菜", "这道菜", "该菜品", "这个菜", "那个菜", "这款菜", "那款菜"})
    @DisplayName("All DISH reference patterns inject slot")
    void allDishPatternsInjectSlot(String pattern) {
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-patterns")
                .build();
        context.setSlot(EntitySlot.SlotType.DISH, EntitySlot.dish("d1", "叮咚牛肉面"));

        PreprocessedQuery result = IntentRecognitionPipelineServiceImpl.ensureDishReferenceResolved(
                pattern + "的趋势",
                "叮咚牛肉面的趋势",
                context,
                null);

        assertThat(result).isNotNull();
        assertThat(result.getResolvedReferences()).isNotEmpty();
        PreprocessedQuery.ResolvedReference ref = result.getResolvedReferences().get(pattern);
        assertThat(ref).isNotNull();
        assertThat(ref.getEntityType()).isEqualToIgnoringCase("DISH");
        assertThat(ref.getEntityId()).isEqualTo("d1");
        assertThat(ref.getEntityName()).isEqualTo("叮咚牛肉面");
    }

    @Test
    @DisplayName("No-op when originalInput has no dish reference pattern")
    void noOpWhenNoDishPattern() {
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-noop")
                .build();
        context.setSlot(EntitySlot.SlotType.DISH, EntitySlot.dish("d1", "招牌青花椒味"));

        PreprocessedQuery existing = PreprocessedQuery.builder()
                .originalInput("畅销菜品排行")
                .finalQuery("畅销菜品排行")
                .build();

        PreprocessedQuery result = IntentRecognitionPipelineServiceImpl.ensureDishReferenceResolved(
                "畅销菜品排行", "畅销菜品排行", context, existing);

        // Should return existing PQ unchanged (no dish reference in input)
        assertThat(result).isSameAs(existing);
        assertThat(result.getResolvedReferences()).isNullOrEmpty();
    }

    @Test
    @DisplayName("No-op when DISH slot is absent from context")
    void noOpWhenNoDishSlotInContext() {
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-noslot")
                .build();
        // No DISH slot set — first turn has no memory

        PreprocessedQuery result = IntentRecognitionPipelineServiceImpl.ensureDishReferenceResolved(
                "那道菜的销量", "那道菜的销量", context, null);

        // Should return null (no slot to inject from)
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("No-op when DISH already in resolvedReferences (no double injection)")
    void noDoubleInjectionWhenDishAlreadyResolved() {
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-double")
                .build();
        context.setSlot(EntitySlot.SlotType.DISH, EntitySlot.dish("d1", "招牌青花椒味"));

        PreprocessedQuery existingPq = PreprocessedQuery.builder()
                .originalInput("那道菜的销量")
                .finalQuery("招牌青花椒味的销量")
                .resolvedReferences(new HashMap<>())
                .build();
        // Pre-populate the DISH reference so the guard fires
        existingPq.addResolvedReference("那道菜", "DISH", "d1", "招牌青花椒味");

        PreprocessedQuery result = IntentRecognitionPipelineServiceImpl.ensureDishReferenceResolved(
                "那道菜的销量", "招牌青花椒味的销量", context, existingPq);

        // Should return the same object without modification
        assertThat(result).isSameAs(existingPq);
        assertThat(result.getResolvedReferences()).hasSize(1);
    }

    @Test
    @DisplayName("Creates PreprocessedQuery when null passed in (similar to ensureStoreReferenceResolved)")
    void createsPqWhenNullPassedIn() {
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-create")
                .build();
        context.setSlot(EntitySlot.SlotType.DISH, EntitySlot.dish("d42", "夫妻肺片"));

        PreprocessedQuery result = IntentRecognitionPipelineServiceImpl.ensureDishReferenceResolved(
                "那道菜的销量趋势",
                "夫妻肺片的销量趋势",
                context,
                null);

        assertThat(result).isNotNull();
        assertThat(result.getResolvedReferences()).containsKey("那道菜");
        PreprocessedQuery.ResolvedReference ref = result.getResolvedReferences().get("那道菜");
        assertThat(ref.getEntityType()).isEqualToIgnoringCase("DISH");
        assertThat(ref.getEntityId()).isEqualTo("d42");
        assertThat(ref.getEntityName()).isEqualTo("夫妻肺片");
    }

    // ==================== Regression: STORE coref still works ====================

    @Test
    @DisplayName("STORE coref still resolves (regression guard)")
    void storeCorefUnchanged() {
        ConversationContext context = ConversationContext.builder()
                .sessionId("sid-store-regression")
                .build();
        context.setSlot(EntitySlot.SlotType.STORE, EntitySlot.store("s1", "青花椒成都路店"));

        PreprocessedQuery result = IntentRecognitionPipelineServiceImpl.ensureStoreReferenceResolved(
                "那家店的客单价",
                "青花椒成都路店的客单价",
                context,
                null);

        assertThat(result).isNotNull();
        assertThat(result.getResolvedReferences()).containsKey("那家店");
        assertThat(result.getResolvedReferences().get("那家店").getEntityType()).isEqualToIgnoringCase("STORE");
    }
}
