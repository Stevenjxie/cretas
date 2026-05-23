package com.cretas.aims.dev.faultinjection;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * F4 fault-injection hook — simulates Python SmartBI service unreachable
 * (10010 → 8083 HTTP layer blocked).
 *
 * <p>Active ONLY when Spring profile {@code dev-fault-injection} is enabled.
 * Production-safe: never instantiated under {@code prod} / {@code pg-prod} /
 * {@code pg} profiles.
 *
 * <h3>Activation</h3>
 * <pre>{@code
 * SPRING_PROFILES_ACTIVE=pg-prod,dev-fault-injection \
 *   MOCK_PYTHON_LLM_UNREACHABLE=true \
 *   java -jar aims-0.0.1-SNAPSHOT.jar
 * }</pre>
 *
 * <h3>Test scenario (per runner-fault.sh F4)</h3>
 * 6 LLM-routed inputs that involve Python SmartBI analysis composite should
 * trigger Java backend degraded mode (cached / deterministic fallback),
 * NOT 500 propagation.
 *
 * <p>Hook is installed at the OkHttp interceptor layer of
 * {@link com.cretas.aims.client.PythonSmartBIClient} so it intercepts ALL
 * Java→Python HTTP traffic (Excel parse / forecast / LLM tool-call / health).
 *
 * @author AI Factory chat (Sprint 12 close)
 * @since 2026-05-23
 * @see com.cretas.aims.client.PythonSmartBIClient
 */
@Slf4j
@Component
@Profile("dev-fault-injection")
public class PythonClientFaultInjector {

    @Value("${MOCK_PYTHON_LLM_UNREACHABLE:false}")
    private boolean unreachable;

    @PostConstruct
    void warnAtStartup() {
        log.warn("[FAULT-INJECTION] PythonClientFaultInjector ACTIVE — unreachable={} (this must NEVER run in production)", unreachable);
    }

    /**
     * Throw {@link IOException} when {@code MOCK_PYTHON_LLM_UNREACHABLE=true},
     * mimicking Connection refused / DNS failure. No-op when env var unset/false.
     *
     * @param requestPath path of the outbound request (for log context only)
     * @throws IOException when fault is active — bubbles up identically to a
     *                     real Python service outage so caller's existing
     *                     fallback / try-catch handles it
     */
    public void maybeThrowUnreachable(String requestPath) throws IOException {
        if (unreachable) {
            log.warn("[FAULT-INJECTION] Python unreachable simulated for path={}", requestPath);
            throw new IOException("[FAULT-INJECTION] Python SmartBI service unreachable (MOCK_PYTHON_LLM_UNREACHABLE=true)");
        }
    }
}
