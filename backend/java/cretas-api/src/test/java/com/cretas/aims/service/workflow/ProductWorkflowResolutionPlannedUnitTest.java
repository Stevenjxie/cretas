package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.workflow.impl.ProductWorkflowResolutionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductWorkflowResolutionPlannedUnitTest {

    private ProductProcessWorkflowActivationRepository activations;
    private ProductProcessWorkflowRepository workflows;
    private UnitContractService units;
    private ProductWorkflowResolutionService service;

    @BeforeEach
    void setUp() {
        activations = mock(ProductProcessWorkflowActivationRepository.class);
        workflows = mock(ProductProcessWorkflowRepository.class);
        units = mock(UnitContractService.class);
        service = new ProductWorkflowResolutionServiceImpl(
                activations, workflows, mock(ProductTypeRepository.class),
                mock(RawMaterialTypeRepository.class),
                new ObjectMapper(), units,
                mock(com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator.class));
        CanonicalUnit gram = new CanonicalUnit(
                "g", UnitDimension.MASS, "g", BigDecimal.ONE, "克", 2);
        when(units.normalize("F1", "g"))
                .thenReturn(new UnitNormalizationResult("g", "g", gram));
    }

    @Test
    void activeWorkflowUsesTerminalOutputPortUnit() {
        activate("P1", """
                [
                  {"id":"fg","kind":"FINISHED_GOOD","data":{"skuId":"P1"}},
                  {"id":"proc","kind":"PROCESS","data":{"ports":[
                    {"direction":"OUTPUT","materialNodeId":"fg","unit":"g"}
                  ]}}
                ]
                """);

        WorkflowPlanOutputContract result = service
                .resolveActivePlanOutputContract("F1", "P1", null).orElseThrow();

        assertEquals("g", result.plannedUnit());
        assertEquals(41L, result.workflowId());
        assertEquals("g", result.outputUnitBySku().get("P1"));
    }

    @Test
    void multiSkuWithDifferentOutputUnitsFailsClosed() {
        CanonicalUnit box = new CanonicalUnit(
                "box", UnitDimension.PACKAGE, "box", null, "盒", 0);
        when(units.normalize("F1", "box"))
                .thenReturn(new UnitNormalizationResult("box", "box", box));
        activate("P1", """
                [
                  {"id":"raw","kind":"RAW_MATERIAL","data":{"skuId":"RAW"}},
                  {"id":"fg1","kind":"FINISHED_GOOD","data":{"skuId":"P1"}},
                  {"id":"fg2","kind":"FINISHED_GOOD","data":{"skuId":"P2"}},
                  {"id":"proc","kind":"PROCESS","data":{"ports":[
                    {"direction":"OUTPUT","materialNodeId":"fg1","unit":"g"},
                    {"direction":"OUTPUT","materialNodeId":"fg2","unit":"box"}
                  ]}}
                ]
                """);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.resolveActivePlanOutputContract(
                        "F1", "P1", List.of("P1", "P2")));

        assertEquals("WORKFLOW_PLAN_OUTPUT_UNIT_AMBIGUOUS", error.getErrorCode());
    }

    @Test
    void noSingleOutputActivationFailsWithoutMultiOutputFallback() {
        when(activations.findByFactoryIdAndEnabledTrue("F1")).thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.resolveActivePlanOutputContract("F1", "P1", null));

        assertEquals("WORKFLOW_SINGLE_OUTPUT_NOT_FOUND", error.getErrorCode());
    }

    @Test
    void rawCentricPlanUsesOwnerInputPortUnitNotFinishedOutputUnit() {
        CanonicalUnit kilogram = new CanonicalUnit(
                "kg", UnitDimension.MASS, "g", new BigDecimal("1000"), "千克", 3);
        when(units.normalize("F1", "kg"))
                .thenReturn(new UnitNormalizationResult("kg", "kg", kilogram));
        activate("RAW", """
                [
                  {"id":"raw","kind":"RAW_MATERIAL","data":{"skuId":"RAW"}},
                  {"id":"fg","kind":"FINISHED_GOOD","data":{"skuId":"P1"}},
                  {"id":"proc","kind":"PROCESS","data":{"ports":[
                    {"direction":"INPUT","materialNodeId":"raw","unit":"kg"},
                    {"direction":"OUTPUT","materialNodeId":"fg","unit":"g"}
                  ]}}
                ]
                """);

        WorkflowPlanOutputContract result = service
                .resolveActivePlanOutputContract("F1", "RAW", List.of("P1")).orElseThrow();

        assertEquals("kg", result.plannedUnit());
        assertEquals("g", result.outputUnitBySku().get("P1"));
    }

    private void activate(String ownerId, String nodesJson) {
        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F1");
        activation.setProductTypeId(ownerId);
        activation.setActiveWorkflowId(41L);
        activation.setActiveDefinitionVersion(3);
        activation.setEnabled(true);
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(41L);
        workflow.setFactoryId("F1");
        workflow.setProductTypeId(ownerId);
        workflow.setDefinitionVersion(3);
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setUnitReviewRequired(false);
        workflow.setNodesJson(nodesJson);
        workflow.setEdgesJson("[]");
        when(activations.findByFactoryIdAndProductTypeId("F1", ownerId))
                .thenReturn(Optional.of(activation));
        when(activations.findByFactoryIdAndEnabledTrue("F1"))
                .thenReturn(List.of(activation));
        when(workflows.findByIdAndFactoryId(41L, "F1")).thenReturn(Optional.of(workflow));
    }
}
