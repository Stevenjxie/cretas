package com.cretas.aims.service.startup;

import com.cretas.aims.service.SemanticIntentMatcher;
import com.cretas.aims.service.SemanticRouterService;
import com.cretas.aims.service.impl.IntentEmbeddingCacheServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

@Slf4j
@Component
public class StartupWarmupCoordinator {

    private final Executor executor;
    private final IntentEmbeddingCacheServiceImpl intentCache;
    private final SemanticIntentMatcher semanticMatcher;
    private final SemanticRouterService semanticRouter;
    private final AiWarmupStatusRegistry statusRegistry;

    public StartupWarmupCoordinator(
            @Qualifier("startupWarmupExecutor") Executor executor,
            IntentEmbeddingCacheServiceImpl intentCache,
            SemanticIntentMatcher semanticMatcher,
            SemanticRouterService semanticRouter,
            AiWarmupStatusRegistry statusRegistry) {
        this.executor = executor;
        this.intentCache = intentCache;
        this.semanticMatcher = semanticMatcher;
        this.semanticRouter = semanticRouter;
        this.statusRegistry = statusRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void afterApplicationReady() {
        executor.execute(this::runSerialWarmups);
    }

    void runSerialWarmups() {
        boolean intentCacheReady = warm(
                AiWarmupStatusRegistry.INTENT_CACHE, intentCache::initializeCache);
        warm(AiWarmupStatusRegistry.SEMANTIC_MATCHER, semanticMatcher::initializePhraseVectors);
        if (!intentCacheReady) {
            log.warn("Retrying startup warmup {} once after semantic matcher warmup",
                    AiWarmupStatusRegistry.INTENT_CACHE);
            intentCacheReady = warm(
                    AiWarmupStatusRegistry.INTENT_CACHE, intentCache::initializeCache);
        }
        if (intentCacheReady) {
            warm(AiWarmupStatusRegistry.SEMANTIC_ROUTER, semanticRouter::refreshAllCache);
        } else {
            statusRegistry.failed(AiWarmupStatusRegistry.SEMANTIC_ROUTER,
                    new IllegalStateException("intent embedding cache unavailable"));
            log.warn("Skipping startup warmup {} because {} did not become ready",
                    AiWarmupStatusRegistry.SEMANTIC_ROUTER,
                    AiWarmupStatusRegistry.INTENT_CACHE);
        }
    }

    private boolean warm(String component, Runnable action) {
        statusRegistry.warming(component);
        long started = System.currentTimeMillis();
        try {
            action.run();
            statusRegistry.ready(component);
            log.info("Startup warmup {} READY in {}ms", component, System.currentTimeMillis() - started);
            return true;
        } catch (Exception error) {
            statusRegistry.failed(component, error);
            log.error("Startup warmup {} FAILED after {}ms: {}", component,
                    System.currentTimeMillis() - started, error.getMessage(), error);
            return false;
        }
    }
}
