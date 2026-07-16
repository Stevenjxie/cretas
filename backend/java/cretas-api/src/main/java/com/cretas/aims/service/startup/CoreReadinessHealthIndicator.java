package com.cretas.aims.service.startup;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component("coreReadinessHealthIndicator")
public class CoreReadinessHealthIndicator implements HealthIndicator {

    private final AtomicBoolean applicationReady = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() {
        applicationReady.set(true);
    }

    @Override
    public Health health() {
        return applicationReady.get()
                ? Health.up().withDetail("state", "READY").build()
                : Health.outOfService().withDetail("state", "NOT_STARTED").build();
    }
}
