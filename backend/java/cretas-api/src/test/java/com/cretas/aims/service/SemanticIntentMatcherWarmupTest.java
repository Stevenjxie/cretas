package com.cretas.aims.service;

import com.cretas.aims.config.IntentKnowledgeBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticIntentMatcherWarmupTest {

    @Test
    void firstAvailabilityCheckNeverPerformsSynchronousWarmup() {
        IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isAvailable()).thenReturn(true);
        SemanticIntentMatcher matcher = new SemanticIntentMatcher(knowledgeBase);
        ReflectionTestUtils.setField(matcher, "embeddingClient", embedding);
        ReflectionTestUtils.setField(matcher, "semanticEnabled", true);
        matcher.initializeLocalCache();

        assertFalse(matcher.isSemanticMatchingAvailable());
        verify(embedding, never()).encodeBatch(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void backgroundWarmupPublishesCompleteVectors() {
        IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(knowledgeBase.getPhraseToIntentMapping()).thenReturn(Map.of("hello", "HELLO"));
        when(knowledgeBase.getRestaurantPhraseMapping()).thenReturn(Map.of());
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.encodeBatch(java.util.List.of("hello")))
                .thenReturn(java.util.List.of(new float[]{1, 0}));
        SemanticIntentMatcher matcher = new SemanticIntentMatcher(knowledgeBase);
        ReflectionTestUtils.setField(matcher, "embeddingClient", embedding);
        ReflectionTestUtils.setField(matcher, "semanticEnabled", true);
        matcher.initializeLocalCache();

        matcher.initializePhraseVectors();

        assertTrue(matcher.isSemanticMatchingAvailable());
    }
}
