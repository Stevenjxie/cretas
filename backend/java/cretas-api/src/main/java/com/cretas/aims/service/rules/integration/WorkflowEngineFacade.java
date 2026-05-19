package com.cretas.aims.service.rules.integration;

import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * Facade for invoking Phase 1 Canvas-Workflow engine from rule TRIGGER_WORKFLOW actions.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §7
 *
 * <p>Phase 4a impl (2026-05-18): wired to Phase 1 {@link WorkflowEngineService} (merged
 * to main). {@code required=false} so absence of the bean (e.g. unit tests, factories
 * without workflow config) does not break Rule engine bootstrap — TRIGGER_WORKFLOW
 * rules just throw at execution time, which surfaces in {@code rule_execution_logs}.
 *
 * @author Cretas Team
 * @version 1.1.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class WorkflowEngineFacade {

    /**
     * Phase 1 workflow engine. {@code required=false} — absence handled gracefully.
     */
    @Autowired(required = false)
    private WorkflowEngineService workflowEngineService;

    /**
     * Start a Canvas-Workflow process by code.
     *
     * <p>Maps Canvas-Rules action config into Phase 1's startWorkflow signature.
     * The {@code workflowCode} parameter is bound to {@code moduleCode} in Phase 1
     * terminology (e.g. {@code "PURCHASE_ORDER"}, {@code "TRANSFER_REQUEST"}).
     *
     * @param factoryId        tenant
     * @param workflowCode     Phase 1 moduleCode (e.g. "TRANSFER_REQUEST")
     * @param businessEntityId business entity id (PO id / SO id / etc.)
     * @param ctx              context map passed as workflow variables (bound to #context.* in SpEL)
     * @param initiatorUserId  user who triggered (may be null for system-initiated)
     * @return started instance, or null when engine unavailable (logged warn)
     */
    public ApprovalWorkflowInstance startWorkflow(String factoryId,
                                                  String workflowCode,
                                                  String businessEntityId,
                                                  Map<String, Object> ctx,
                                                  Long initiatorUserId) {
        Objects.requireNonNull(factoryId, "factoryId must not be null");
        Objects.requireNonNull(workflowCode, "workflowCode must not be null");

        if (workflowEngineService == null) {
            log.warn("WorkflowEngineService unavailable - TRIGGER_WORKFLOW rule for "
                    + "workflowCode={} factory={} skipped. Verify Phase 1 deployment.",
                    workflowCode, factoryId);
            return null;
        }
        // Pre-check to avoid Phase 1 illegalArgumentException flooding (per its hasActiveWorkflow Javadoc).
        if (!workflowEngineService.hasActiveWorkflow(factoryId, workflowCode)) {
            log.warn("No active workflow for factoryId={}, moduleCode={} - "
                    + "TRIGGER_WORKFLOW rule skipped. Configure workflow in Canvas first.",
                    factoryId, workflowCode);
            return null;
        }
        log.info("Canvas-Rules TRIGGER_WORKFLOW - factoryId={}, workflowCode={}, businessEntityId={}",
                factoryId, workflowCode, businessEntityId);
        return workflowEngineService.startWorkflow(factoryId, workflowCode,
                businessEntityId, ctx, initiatorUserId);
    }
}
