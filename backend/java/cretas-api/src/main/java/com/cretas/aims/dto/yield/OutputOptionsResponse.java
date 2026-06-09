package com.cretas.aims.dto.yield;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * SP1 T4 — Output options for a production batch's report screen.
 *
 * <p>The RN report screen calls
 * {@code GET /api/mobile/{factoryId}/processing/batches/{batchId}/output-options}
 * to populate the "semi output code" dropdown when {@code outputKind=SEMI|BOTH}.
 *
 * <p>Each item corresponds to a {@code WorkProcessTask} whose parent
 * {@code WorkProcess.semiFinishedOutputCode} is configured (non-blank).
 */
@Data
@Builder
public class OutputOptionsResponse {

    /** All tasks for this batch that have a configured semi output code. */
    private List<OutputOptionItem> items;

    @Data
    @Builder
    public static class OutputOptionItem {

        /** WorkProcessTask ID (used to bind the report to a specific task). */
        private Long taskId;

        /** WorkProcess name, shown to the operator as the label. */
        private String processName;

        /**
         * The semi-finished batch code that will be used as the ledger key.
         * This is {@code WorkProcess.semiFinishedOutputCode}.
         */
        private String semiCode;

        /** Process order (1-based), for display ordering in the dropdown. */
        private Integer processOrder;
    }
}
