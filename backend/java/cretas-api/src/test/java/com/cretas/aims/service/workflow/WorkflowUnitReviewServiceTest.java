package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowUnitReviewServiceTest {

    @Test
    void locksEveryFactoryWorkflowButMarksOnlyPublishedRows() {
        ProductProcessWorkflowRepository repository = mock(ProductProcessWorkflowRepository.class);
        ProductProcessWorkflow fresh = workflow(false);
        ProductProcessWorkflow alreadyMarked = workflow(true);
        ProductProcessWorkflow draft = workflow(false);
        draft.setStatus(ProductProcessWorkflow.Status.DRAFT);
        when(repository.lockByFactoryId("F006"))
                .thenReturn(List.of(fresh, alreadyMarked, draft));

        int changed = new WorkflowUnitReviewService(repository)
                .markPublishedWorkflowsForReview("F006");

        assertEquals(1, changed);
        assertTrue(fresh.getUnitReviewRequired());
        assertEquals(false, draft.getUnitReviewRequired());
        verify(repository).lockByFactoryId("F006");
        verify(repository).saveAll(List.of(fresh));
    }

    private ProductProcessWorkflow workflow(boolean reviewRequired) {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setFactoryId("F006");
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setUnitReviewRequired(reviewRequired);
        return workflow;
    }
}
