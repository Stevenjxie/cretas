package com.cretas.aims.ai.tool.gateway;

/** A strongly typed origin for policy checks; never inferred from tool parameters. */
public enum ToolExecutionSource {
    AI_CHAT,
    AI_INTENT_DISPATCH,
    WORKFLOW,
    SCHEDULER,
    TRIGGER,
    SOP,
    MCP,
    HTTP_CONTROLLER,
    INTERNAL_SERVICE
}
