package com.cretas.aims.service.startup;

import com.cretas.aims.service.SemanticIntentMatcher;
import com.cretas.aims.service.impl.IntentEmbeddingCacheServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class StartupWarmupCoordinatorTest {

    @Test
    void schedulesBothEmbeddingWarmupsSeriallyAfterReady() {
        IntentEmbeddingCacheServiceImpl intentCache = mock(IntentEmbeddingCacheServiceImpl.class);
        SemanticIntentMatcher semanticMatcher = mock(SemanticIntentMatcher.class);
        AiWarmupStatusRegistry registry = new AiWarmupStatusRegistry();
        Executor directExecutor = Runnable::run;
        StartupWarmupCoordinator coordinator = new StartupWarmupCoordinator(
                directExecutor, intentCache, semanticMatcher, registry);

        coordinator.afterApplicationReady();

        InOrder order = inOrder(intentCache, semanticMatcher);
        order.verify(intentCache).initializeCache();
        order.verify(semanticMatcher).initializePhraseVectors();
        verify(intentCache, times(1)).initializeCache();
        assertEquals(WarmupState.READY,
                registry.snapshot().get(AiWarmupStatusRegistry.INTENT_CACHE).state());
        assertEquals(WarmupState.READY,
                registry.snapshot().get(AiWarmupStatusRegistry.SEMANTIC_MATCHER).state());
    }

    @Test
    void recordsFailureWithoutPretendingReadyAndContinuesSecondWarmup() {
        IntentEmbeddingCacheServiceImpl intentCache = mock(IntentEmbeddingCacheServiceImpl.class);
        SemanticIntentMatcher semanticMatcher = mock(SemanticIntentMatcher.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("embedding offline"))
                .when(intentCache).initializeCache();
        AiWarmupStatusRegistry registry = new AiWarmupStatusRegistry();
        StartupWarmupCoordinator coordinator = new StartupWarmupCoordinator(
                Runnable::run, intentCache, semanticMatcher, registry);

        coordinator.afterApplicationReady();

        assertEquals(WarmupState.FAILED,
                registry.snapshot().get(AiWarmupStatusRegistry.INTENT_CACHE).state());
        assertEquals("embedding offline",
                registry.snapshot().get(AiWarmupStatusRegistry.INTENT_CACHE).error());
        assertEquals(WarmupState.READY,
                registry.snapshot().get(AiWarmupStatusRegistry.SEMANTIC_MATCHER).state());
        verify(semanticMatcher).initializePhraseVectors();
        verify(intentCache, times(2)).initializeCache();
    }

    @Test
    void retriesIntentCacheOnceAfterSemanticWarmupAndRecoversHealth() {
        IntentEmbeddingCacheServiceImpl intentCache = mock(IntentEmbeddingCacheServiceImpl.class);
        SemanticIntentMatcher semanticMatcher = mock(SemanticIntentMatcher.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("cold batch timeout"))
                .doNothing()
                .when(intentCache).initializeCache();
        AiWarmupStatusRegistry registry = new AiWarmupStatusRegistry();
        StartupWarmupCoordinator coordinator = new StartupWarmupCoordinator(
                Runnable::run, intentCache, semanticMatcher, registry);

        coordinator.afterApplicationReady();

        InOrder order = inOrder(intentCache, semanticMatcher);
        order.verify(intentCache).initializeCache();
        order.verify(semanticMatcher).initializePhraseVectors();
        order.verify(intentCache).initializeCache();
        verify(intentCache, times(2)).initializeCache();
        assertEquals(WarmupState.READY,
                registry.snapshot().get(AiWarmupStatusRegistry.INTENT_CACHE).state());
        assertEquals(WarmupState.READY,
                registry.snapshot().get(AiWarmupStatusRegistry.SEMANTIC_MATCHER).state());
    }
}
