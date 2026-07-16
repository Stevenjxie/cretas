package com.cretas.aims.service.impl;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.SemanticCacheConfigRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.learning.LearnedExpressionRepository;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.RequestScopedEmbeddingCache;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentEmbeddingCacheServiceImplWarmupTest {

    @Test
    void batchesAllIntentEmbeddingsAndPublishesCompletedMap() {
        AIIntentConfigRepository intents = mock(AIIntentConfigRepository.class);
        LearnedExpressionRepository expressions = mock(LearnedExpressionRepository.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        AIIntentConfig first = AIIntentConfig.builder().intentCode("A").description("alpha").build();
        AIIntentConfig second = AIIntentConfig.builder().factoryId("F006")
                .intentCode("B").description("beta").build();
        when(embedding.isAvailable()).thenReturn(true);
        when(intents.findAllEnabled()).thenReturn(List.of(first, second));
        when(expressions.findByIsActiveTrue()).thenReturn(List.of());
        when(embedding.encodeBatch(anyList())).thenReturn(List.of(new float[]{1}, new float[]{2}));
        IntentEmbeddingCacheServiceImpl service = new IntentEmbeddingCacheServiceImpl(
                intents, mock(SemanticCacheConfigRepository.class), embedding, expressions,
                mock(RequestScopedEmbeddingCache.class));

        service.initializeCache();

        verify(embedding).encodeBatch(List.of("alpha", "beta"));
        verify(embedding, never()).encode(org.mockito.ArgumentMatchers.anyString());
        assertArrayEquals(new float[]{1}, service.getIntentEmbedding("*", "A").orElseThrow());
        assertArrayEquals(new float[]{2}, service.getIntentEmbedding("F006", "B").orElseThrow());
    }

    @Test
    void failedBatchDoesNotPublishPartialCache() {
        AIIntentConfigRepository intents = mock(AIIntentConfigRepository.class);
        LearnedExpressionRepository expressions = mock(LearnedExpressionRepository.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isAvailable()).thenReturn(true);
        when(intents.findAllEnabled()).thenReturn(List.of(
                AIIntentConfig.builder().intentCode("A").description("alpha").build()));
        when(embedding.encodeBatch(anyList())).thenReturn(List.of());
        IntentEmbeddingCacheServiceImpl service = new IntentEmbeddingCacheServiceImpl(
                intents, mock(SemanticCacheConfigRepository.class), embedding, expressions,
                mock(RequestScopedEmbeddingCache.class));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, service::initializeCache);
        assertTrue(service.getIntentEmbedding("*", "A").isEmpty());
    }
}
