package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;

/**
 * Invalidates published Workflow unit authority after a factory-scoped unit contract changes.
 * Existing runtime snapshots are intentionally not touched.
 */
@Service
@RequiredArgsConstructor
public class WorkflowUnitReviewService {

    private final ProductProcessWorkflowRepository workflowRepository;

    @Transactional
    public int markPublishedWorkflowsForReview(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return 0;
        }
        var workflows = workflowRepository.lockByFactoryId(factoryId);
        var changedWorkflows = new ArrayList<ProductProcessWorkflow>();
        for (ProductProcessWorkflow workflow : workflows) {
            if (workflow.getStatus() == ProductProcessWorkflow.Status.PUBLISHED
                    && !Boolean.TRUE.equals(workflow.getUnitReviewRequired())) {
                workflow.setUnitReviewRequired(true);
                changedWorkflows.add(workflow);
            }
        }
        if (!changedWorkflows.isEmpty()) {
            workflowRepository.saveAll(changedWorkflows);
        }
        return changedWorkflows.size();
    }
}
