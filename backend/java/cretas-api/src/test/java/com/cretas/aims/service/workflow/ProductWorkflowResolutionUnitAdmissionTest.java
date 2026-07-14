package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.workflow.impl.ProductWorkflowResolutionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductWorkflowResolutionUnitAdmissionTest {

    @Test
    void activeWorkflowRequiringUnitReviewCannotCoverNewPlanOutputs() {
        ProductProcessWorkflowActivationRepository activations =
                mock(ProductProcessWorkflowActivationRepository.class);
        ProductProcessWorkflowRepository workflows = mock(ProductProcessWorkflowRepository.class);
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        ProductWorkflowResolutionService service = new ProductWorkflowResolutionServiceImpl(
                activations, workflows, products, new ObjectMapper(),
                mock(com.cretas.aims.service.unit.UnitContractService.class));

        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F006");
        activation.setProductTypeId("RAW-1");
        activation.setActiveWorkflowId(41L);
        activation.setActiveDefinitionVersion(3);
        activation.setEnabled(true);
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(41L);
        workflow.setFactoryId("F006");
        workflow.setProductTypeId("RAW-1");
        workflow.setDefinitionVersion(3);
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setUnitReviewRequired(true);
        workflow.setNodesJson("[]");

        when(activations.findByFactoryIdAndProductTypeId("F006", "RAW-1"))
                .thenReturn(Optional.of(activation));
        when(workflows.findByIdAndFactoryId(41L, "F006")).thenReturn(Optional.of(workflow));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.assertActiveWorkflowCoversOutputs(
                        "F006", "RAW-1", List.of("FG-1")));

        assertEquals("WORKFLOW_RESOLUTION_NOT_COVERED", error.getErrorCode());
    }
}
