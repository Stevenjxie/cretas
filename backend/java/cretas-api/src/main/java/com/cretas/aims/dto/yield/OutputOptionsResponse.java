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
 * <p>Items are assembled from <b>two</b> independent sources in
 * {@code WipInventoryServiceImpl.getOutputOptions} (union, not either/or):
 * <ul>
 *   <li><b>Legacy (工序管理) tasks</b>: one item per {@code WorkProcessTask} whose parent
 *       {@code WorkProcess.semiFinishedOutputCode} is configured (non-blank).</li>
 *   <li><b>Workflow (画布) tasks</b>: {@code WorkProcess.semiFinishedOutputCode} is never
 *       populated for these — the canvas expresses output structure via
 *       {@code workflow_task_ports} instead. One item per port with
 *       {@code direction=OUTPUT, materialKind=SEMI_FINISHED} that is not flagged
 *       {@code data.isByproduct} on the canvas (see {@code WorkflowByproductNodes}); a single
 *       task can legitimately contribute more than one item (2B.2 multi-output). {@code semiCode}
 *       is the resolved {@code ProductType.code} (e.g. {@code PTSEMI-F006-2001}), not the raw
 *       {@code skuId} UUID.</li>
 * </ul>
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

        /** Process/step name, shown to the operator as the label. */
        private String processName;

        /**
         * The semi-finished batch code that will be used as the ledger key
         * ({@code SemiFinishedInventory.intermediateBatchNo}). Legacy tasks: this is
         * {@code WorkProcess.semiFinishedOutputCode}. Workflow tasks: this is the semi-finished
         * product's {@code ProductType.code} (human-readable, unique per factory).
         */
        private String semiCode;

        /** Process order (1-based), for display ordering in the dropdown. */
        private Integer processOrder;
    }
}
