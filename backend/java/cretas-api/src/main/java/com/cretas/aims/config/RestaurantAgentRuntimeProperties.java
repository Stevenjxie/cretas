package com.cretas.aims.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Rollout gate for the bounded restaurant agent runtime.
 *
 * <p>There is deliberately no SHADOW mode: the only admitted production
 * behavior is an explicit ACTIVE proxy. OFF is the fail-closed default and
 * performs no Python runtime call.</p>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "cretas.restaurant-agent-runtime")
public class RestaurantAgentRuntimeProperties {

    public enum Mode {
        OFF,
        ACTIVE
    }

    private Mode mode = Mode.OFF;

    /** Browser-facing SSE lifetime. The bounded Python run itself is shorter. */
    private long emitterTimeoutMs = 120_000L;

    public boolean isActive() {
        return mode == Mode.ACTIVE;
    }
}
