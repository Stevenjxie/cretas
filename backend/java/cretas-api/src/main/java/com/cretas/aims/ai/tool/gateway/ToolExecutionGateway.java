package com.cretas.aims.ai.tool.gateway;

/**
 * Contract boundary for governed tool execution.
 *
 * <p>The default Spring implementation enforces policy, confirmation, persistent idempotency,
 * and audit. Production callers remain on their legacy paths until migrated explicitly.</p>
 */
@FunctionalInterface
public interface ToolExecutionGateway {

    ToolExecutionResult execute(ToolExecutionCommand command);
}
