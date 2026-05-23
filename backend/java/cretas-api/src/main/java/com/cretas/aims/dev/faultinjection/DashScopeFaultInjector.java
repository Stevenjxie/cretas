package com.cretas.aims.dev.faultinjection;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * F3 fault-injection hook — simulates DashScope (Aliyun LLM) timeout.
 *
 * <p>Active ONLY when Spring profile {@code dev-fault-injection} is enabled.
 * Production-safe: never instantiated under {@code prod} / {@code pg-prod} /
 * {@code pg} profiles, so calls to {@code maybeDelay()} from business code
 * (with {@code @Autowired(required=false)}) become no-ops in production.
 *
 * <h3>Activation</h3>
 * <pre>{@code
 * # Start backend with fault-injection profile + delay envvar
 * SPRING_PROFILES_ACTIVE=pg-prod,dev-fault-injection \
 *   MOCK_DASHSCOPE_DELAY_MS=30000 \
 *   java -jar aims-0.0.1-SNAPSHOT.jar
 * }</pre>
 *
 * <h3>Test scenario (per runner-fault.sh F3)</h3>
 * 6 LLM-routed Workdesk inputs should produce graceful timeout response
 * (status=FAILED with retry hint), NOT 500 nor silent hang.
 *
 * @author AI Factory chat (Sprint 12 close)
 * @since 2026-05-23
 * @see com.cretas.aims.ai.client.PythonLLMClient
 */
@Slf4j
@Component
@Profile("dev-fault-injection")
public class DashScopeFaultInjector {

    @Value("${MOCK_DASHSCOPE_DELAY_MS:0}")
    private int delayMs;

    @PostConstruct
    void warnAtStartup() {
        log.warn("[FAULT-INJECTION] DashScopeFaultInjector ACTIVE — delayMs={} (this must NEVER run in production)", delayMs);
    }

    /**
     * Block calling thread for {@code MOCK_DASHSCOPE_DELAY_MS} milliseconds.
     * No-op when env var is unset or zero.
     */
    public void maybeDelay() {
        if (delayMs > 0) {
            log.warn("[FAULT-INJECTION] DashScope delay {}ms (simulating Aliyun timeout)", delayMs);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
