package com.cretas.aims.service.workflow;

/** Raised when an exact-bound workflow no longer matches its validated snapshot. */
public class WorkflowDefinitionChangedException extends RuntimeException {

    public WorkflowDefinitionChangedException(String message) {
        super(message);
    }
}
