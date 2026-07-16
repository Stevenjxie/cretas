package com.cretas.aims.service.startup;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component("aiWarmupHealthIndicator")
public class AiWarmupHealthIndicator implements HealthIndicator {

    private final AiWarmupStatusRegistry registry;

    public AiWarmupHealthIndicator(AiWarmupStatusRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Map<String, AiWarmupStatusRegistry.Snapshot> statuses = registry.snapshot();
        boolean failed = statuses.values().stream().anyMatch(s -> s.state() == WarmupState.FAILED);
        boolean ready = statuses.values().stream().allMatch(s -> s.state() == WarmupState.READY);
        Health.Builder builder = failed ? Health.down() : ready ? Health.up() : Health.unknown();
        statuses.forEach((name, status) -> builder.withDetail(name, status));
        return builder.build();
    }
}
