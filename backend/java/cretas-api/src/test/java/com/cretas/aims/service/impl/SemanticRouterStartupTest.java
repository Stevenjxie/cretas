package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.NegationTwinPolicy;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.IntentEmbeddingCacheService;
import com.cretas.aims.service.RequestScopedEmbeddingCache;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SemanticRouterStartupTest {

    @Test
    void postConstructDoesNotPerformBlockingCacheWarmup() {
        IntentEmbeddingCacheService embeddingCache = mock(IntentEmbeddingCacheService.class);
        AIIntentConfigRepository intentRepository = mock(AIIntentConfigRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        RequestScopedEmbeddingCache requestCache = mock(RequestScopedEmbeddingCache.class);
        NegationTwinPolicy negationTwinPolicy = mock(NegationTwinPolicy.class);
        SemanticRouterServiceImpl router = new SemanticRouterServiceImpl(
                embeddingCache, intentRepository, embeddingClient, requestCache, negationTwinPolicy);

        router.init();

        verifyNoInteractions(embeddingCache, intentRepository, embeddingClient, requestCache,
                negationTwinPolicy);
    }
}
