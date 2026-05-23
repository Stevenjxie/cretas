package com.cretas.aims.dev.faultinjection;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * F5 fault-injection hook — simulates 1-of-N Tool execution failure
 * (e.g. {@code monthly_revenue_query} throws inside a multi-Tool composite
 * Skill).
 *
 * <p>Active ONLY when Spring profile {@code dev-fault-injection} is enabled.
 * Production-safe: never instantiated under {@code prod} / {@code pg-prod} /
 * {@code pg} profiles.
 *
 * <h3>Activation</h3>
 * <pre>{@code
 * # comma-separated tool names that should throw
 * SPRING_PROFILES_ACTIVE=pg-prod,dev-fault-injection \
 *   MOCK_TOOL_THROW=monthly_revenue_query \
 *   java -jar aims-0.0.1-SNAPSHOT.jar
 *
 * # Multiple tools
 * MOCK_TOOL_THROW=monthly_revenue_query,daily_cost_summary
 * }</pre>
 *
 * <h3>Test scenario (per runner-fault.sh F5)</h3>
 * sales-owner / finance-manager composite Skill uses 5+ Tools each.
 * Expected: SkillExecutor catches the 1 fail, other 4-7 Tools succeed,
 * {@code formattedText} degrades gracefully (no full skill abort).
 *
 * <p>Hook is installed in {@link com.cretas.aims.service.execution.ToolDispatchService}
 * right before {@code tool.execute(toolCall, context)} — that path is shared by
 * both direct Tool dispatch and Skill orchestration (Skill steps loop back
 * through ToolDispatchService for execution).
 *
 * @author AI Factory chat (Sprint 12 close)
 * @since 2026-05-23
 * @see com.cretas.aims.service.execution.ToolDispatchService
 */
@Slf4j
@Component
@Profile("dev-fault-injection")
public class ToolExecutionFaultInjector {

    @Value("${MOCK_TOOL_THROW:}")
    private String toolThrowList;

    private Set<String> toolsToThrow = Collections.emptySet();

    @PostConstruct
    void init() {
        if (toolThrowList != null && !toolThrowList.isBlank()) {
            toolsToThrow = new HashSet<>(Arrays.asList(toolThrowList.split("\\s*,\\s*")));
        }
        log.warn("[FAULT-INJECTION] ToolExecutionFaultInjector ACTIVE — toolsToThrow={} (this must NEVER run in production)", toolsToThrow);
    }

    /**
     * Throw {@link RuntimeException} when {@code toolName} is in the
     * {@code MOCK_TOOL_THROW} list. No-op otherwise.
     *
     * @param toolName the Tool about to execute (from {@code tool.getToolName()})
     * @throws RuntimeException when toolName matches — Exception type mirrors a
     *                          natural Tool failure so caller's existing retry/
     *                          CRITIC correction loop handles it identically
     */
    public void maybeThrow(String toolName) {
        if (toolName != null && toolsToThrow.contains(toolName)) {
            log.warn("[FAULT-INJECTION] Tool throw simulated for tool={}", toolName);
            throw new RuntimeException("[FAULT-INJECTION] Tool [" + toolName + "] simulated failure (MOCK_TOOL_THROW)");
        }
    }
}
