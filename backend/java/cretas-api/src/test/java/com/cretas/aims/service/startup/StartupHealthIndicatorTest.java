package com.cretas.aims.service.startup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartupHealthIndicatorTest {

    @Test
    void coreReadinessDoesNotWaitForAiWarmup() {
        CoreReadinessHealthIndicator core = new CoreReadinessHealthIndicator();
        assertEquals(Status.OUT_OF_SERVICE, core.health().getStatus());
        core.applicationReady();
        assertEquals(Status.UP, core.health().getStatus());
    }

    @Test
    void aiWarmupFailureIsVisibleAsDown() {
        AiWarmupStatusRegistry registry = new AiWarmupStatusRegistry();
        AiWarmupHealthIndicator indicator = new AiWarmupHealthIndicator(registry);
        assertEquals(Status.UNKNOWN, indicator.health().getStatus());
        registry.warming(AiWarmupStatusRegistry.INTENT_CACHE);
        registry.failed(AiWarmupStatusRegistry.INTENT_CACHE, new RuntimeException("boom"));
        assertEquals(Status.DOWN, indicator.health().getStatus());
    }
}
