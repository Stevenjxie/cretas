package com.cretas.aims.ai.tool.gateway;

/**
 * Contract boundary for governed tool execution.
 *
 * <p>This phase intentionally defines no implementation and makes no claim that policy,
 * idempotency, audit, or trace enforcement is already wired into production callers.</p>
 */
@FunctionalInterface
public interface ToolExecutionGateway {

    ToolExecutionResult execute(ToolExecutionCommand command);
}
