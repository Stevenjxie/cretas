package com.cretas.aims.service.startup;

import com.cretas.aims.service.SemanticIntentMatcher;
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
    private final AiWarmupStatusRegistry statusRegistry;

    public StartupWarmupCoordinator(
            @Qualifier("startupWarmupExecutor") Executor executor,
            IntentEmbeddingCacheServiceImpl intentCache,
            SemanticIntentMatcher semanticMatcher,
            AiWarmupStatusRegistry statusRegistry) {
        this.executor = executor;
        this.intentCache = intentCache;
        this.semanticMatcher = semanticMatcher;
        this.statusRegistry = statusRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void afterApplicationReady() {
        executor.execute(this::runSerialWarmups);
    }

    void runSerialWarmups() {
        warm(AiWarmupStatusRegistry.INTENT_CACHE, intentCache::initializeCache);
        warm(AiWarmupStatusRegistry.SEMANTIC_MATCHER, semanticMatcher::initializePhraseVectors);
    }

    private void warm(String component, Runnable action) {
        statusRegistry.warming(component);
        long started = System.currentTimeMillis();
        try {
            action.run();
            statusRegistry.ready(component);
            log.info("Startup warmup {} READY in {}ms", component, System.currentTimeMillis() - started);
        } catch (Exception error) {
            statusRegistry.failed(component, error);
            log.error("Startup warmup {} FAILED after {}ms: {}", component,
                    System.currentTimeMillis() - started, error.getMessage(), error);
        }
    }
}
