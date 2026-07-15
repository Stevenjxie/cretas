package com.cretas.aims.event;

import java.math.BigDecimal;

/** Exact Workflow task progress projection requested by a committed process-sheet row. */
public record WorkflowTaskProgressRequestedEvent(
        String factoryId,
        String planId,
        Long workflowInstanceId,
        String workflowNodeId,
        Integer processOrder,
        BigDecimal actualQuantity) {
}
