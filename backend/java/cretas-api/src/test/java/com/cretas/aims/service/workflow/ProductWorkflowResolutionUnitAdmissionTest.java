package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowUnitIssueDTO;
import com.cretas.aims.dto.workflow.WorkflowUnitValidationResult;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator;
import com.cretas.aims.service.workflow.impl.ProductWorkflowResolutionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductWorkflowResolutionUnitAdmissionTest {

    @Test
    void staleReviewMarkerDoesNotHideEnabledWorkflowWhenLiveUnitContractIsValid() {
        ProductProcessWorkflowActivationRepository activations =
                mock(ProductProcessWorkflowActivationRepository.class);
        ProductProcessWorkflowRepository workflows = mock(ProductProcessWorkflowRepository.class);
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        ProductProcessWorkflowUnitValidator unitValidator = mock(ProductProcessWorkflowUnitValidator.class);
        ProductWorkflowResolutionService service = new ProductWorkflowResolutionServiceImpl(
                activations, workflows, products, mock(RawMaterialTypeRepository.class), new ObjectMapper(),
                mock(com.cretas.aims.service.unit.UnitContractService.class), unitValidator);

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
        workflow.setNodesJson("""
                [
                  {"id":"raw","kind":"RAW_MATERIAL","data":{"skuId":"RAW-1"}},
                  {"id":"process","kind":"PROCESS","data":{"workProcessId":"WP-1","ports":[]}},
                  {"id":"fg","kind":"FINISHED_GOOD","data":{"skuId":"FG-1"}}
                ]
                """);
        workflow.setEdgesJson("""
                [
                  {"id":"e1","source":"raw","target":"process"},
                  {"id":"e2","source":"process","target":"fg"}
                ]
                """);

        when(activations.findByFactoryIdAndProductTypeId("F006", "RAW-1"))
                .thenReturn(Optional.of(activation));
        when(workflows.findByIdAndFactoryId(41L, "F006")).thenReturn(Optional.of(workflow));
        when(unitValidator.validate(eq("F006"), any()))
                .thenReturn(new WorkflowUnitValidationResult(List.of(), List.of()));

        assertDoesNotThrow(() -> service.assertActiveWorkflowCoversOutputs(
                "F006", "RAW-1", List.of("FG-1")));
    }

    @Test
    void markedWorkflowRemainsExcludedWhenLiveUnitContractIsInvalid() {
        ProductProcessWorkflowActivationRepository activations =
                mock(ProductProcessWorkflowActivationRepository.class);
        ProductProcessWorkflowRepository workflows = mock(ProductProcessWorkflowRepository.class);
        ProductProcessWorkflowUnitValidator unitValidator = mock(ProductProcessWorkflowUnitValidator.class);
        ProductWorkflowResolutionService service = new ProductWorkflowResolutionServiceImpl(
                activations, workflows, mock(ProductTypeRepository.class),
                mock(RawMaterialTypeRepository.class), new ObjectMapper(),
                mock(com.cretas.aims.service.unit.UnitContractService.class), unitValidator);

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
        workflow.setEdgesJson("[]");

        when(activations.findByFactoryIdAndProductTypeId("F006", "RAW-1"))
                .thenReturn(Optional.of(activation));
        when(workflows.findByIdAndFactoryId(41L, "F006")).thenReturn(Optional.of(workflow));
        when(unitValidator.validate(eq("F006"), any())).thenReturn(new WorkflowUnitValidationResult(
                List.of(new WorkflowUnitIssueDTO(
                        "WORKFLOW_PORT_UNIT_STALE", "unit mismatch", "fg", "out", "kg", "box")),
                List.of()));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.assertActiveWorkflowCoversOutputs(
                        "F006", "RAW-1", List.of("FG-1")));

        assertEquals("WORKFLOW_SINGLE_OUTPUT_NOT_FOUND", error.getErrorCode());
    }
}
