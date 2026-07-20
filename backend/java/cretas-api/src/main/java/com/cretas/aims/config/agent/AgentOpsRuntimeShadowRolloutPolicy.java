package com.cretas.aims.config.agent;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Fail-closed rollout policy for AgentOps Runtime Shadow.
 *
 * <p>The master flag alone never admits traffic. A trusted request must also match the configured
 * factory and role allowlists and its stable actor bucket must be below {@code sampleBps}. Python
 * repeats the same policy at the internal boundary using the exact canonical hash contract.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "agent-ops.runtime-shadow")
public class AgentOpsRuntimeShadowRolloutPolicy {

    static final String FEATURE_KEY = "agent_ops_runtime_shadow";
    static final int BUCKET_COUNT = 10_000;

    private boolean enabled = false;
    private String factoryAllowlist = "";
    private String roleAllowlist = "";

    private int sampleBps = 0;

    private String rolloutSalt = "runtime-shadow-v1";

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public void setRolloutSalt(String rolloutSalt) {
        this.rolloutSalt = rolloutSalt == null ? null : rolloutSalt.trim();
    }

    public Decision evaluate(String factoryId, String userId, String role) {
        if (!enabled) {
            return record(Decision.MASTER_DISABLED);
        }
        if (isBlank(factoryId) || isBlank(userId) || isBlank(role) || isBlank(rolloutSalt)
                || sampleBps <= 0 || sampleBps > BUCKET_COUNT) {
            return record(Decision.CANARY_DENIED);
        }
        if (!matches(factoryAllowlist, factoryId, false)
                || !matches(roleAllowlist, role, true)) {
            return record(Decision.CANARY_DENIED);
        }
        return record(stableBucket(factoryId, userId, role, rolloutSalt) < sampleBps
                ? Decision.ELIGIBLE
                : Decision.CANARY_DENIED);
    }

    public static int stableBucket(String factoryId, String userId, String role, String salt) {
        String canonical = FEATURE_KEY + "|" + factoryId + "|" + userId + "|"
                + role.toLowerCase(Locale.ROOT) + "|" + salt.trim();
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        long first32 = ((long) (digest[0] & 0xff) << 24)
                | ((long) (digest[1] & 0xff) << 16)
                | ((long) (digest[2] & 0xff) << 8)
                | (digest[3] & 0xffL);
        return (int) (first32 % BUCKET_COUNT);
    }

    private static boolean matches(String csv, String candidate, boolean lowercase) {
        if (isBlank(csv)) {
            return false;
        }
        String normalizedCandidate = lowercase ? candidate.toLowerCase(Locale.ROOT) : candidate;
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> lowercase ? value.toLowerCase(Locale.ROOT) : value)
                .anyMatch(value -> value.equals("*") || value.equals(normalizedCandidate));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Decision record(Decision decision) {
        if (meterRegistry != null) {
            meterRegistry.counter(
                    "agent.ops.runtime.shadow.rollout", "decision", decision.name().toLowerCase(Locale.ROOT))
                    .increment();
        }
        return decision;
    }

    public enum Decision {
        MASTER_DISABLED,
        CANARY_DENIED,
        ELIGIBLE
    }
}
