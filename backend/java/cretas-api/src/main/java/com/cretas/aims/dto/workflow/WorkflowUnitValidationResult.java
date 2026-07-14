package com.cretas.aims.dto.workflow;

import java.util.List;

public record WorkflowUnitValidationResult(
        List<WorkflowUnitIssueDTO> errors,
        List<WorkflowUnitIssueDTO> warnings) {

    public boolean valid() {
        return errors == null || errors.isEmpty();
    }
}
