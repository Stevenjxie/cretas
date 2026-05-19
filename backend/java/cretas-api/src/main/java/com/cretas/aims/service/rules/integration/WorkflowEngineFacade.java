package com.cretas.aims.service.rules.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Facade for invoking Phase 1 Canvas-Workflow engine from rule TRIGGER_WORKFLOW actions.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §7
 *
 * ⚠️ SKELETON / STUB: throws UnsupportedOperationException.
 *
 * Phase 4a follow-up after Phase 1 (Canvas-Workflow) merge:
 *   1. Add `@Autowired private WorkflowEngineService workflowEngineService;`
 *   2. Replace startWorkflow body with `workflowEngineService.start(workflowCode, ctx);`
 *
 * The facade indirection lets Phase 4a ship a compiling skeleton without depending on
 * Phase 1's (concurrent) skeleton PR for the same import path. Once Phase 1 is merged,
 * sister chat wires the real injection in a follow-up commit.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class WorkflowEngineFacade {

    /**
     * Start a Canvas-Workflow process by code.
     *
     * @param workflowCode Phase 1 workflow definition code (e.g. "TRANSFER_REQUEST")
     * @param ctx          context map passed as workflow variables
     */
    public void startWorkflow(String workflowCode, Map<String, Object> ctx) {
        // Phase 4a follow-up: replace with workflowEngineService.start(workflowCode, ctx);
        throw new UnsupportedOperationException(
                "WorkflowEngineFacade.startWorkflow not yet wired (Phase 4a follow-up after Phase 1 merge). "
                + "workflowCode=" + workflowCode);
    }
}
