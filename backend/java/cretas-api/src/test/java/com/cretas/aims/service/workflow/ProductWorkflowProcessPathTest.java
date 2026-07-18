package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

class ProductWorkflowProcessPathTest {

    @Test
    void reverseTraversalReturnsOnlyTheSelectedFinishedGoodsBranch() {
        ProductProcessWorkflowActivationRepository activations =
                mock(ProductProcessWorkflowActivationRepository.class);
        ProductProcessWorkflowRepository workflows = mock(ProductProcessWorkflowRepository.class);
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        UnitContractService units = mock(UnitContractService.class);
        ProductWorkflowResolutionService service = new ProductWorkflowResolutionServiceImpl(
                activations, workflows, products, mock(RawMaterialTypeRepository.class),
                new ObjectMapper(), units);

        ProductType target = new ProductType();
        target.setId("FG-A");
        target.setFactoryId("F006");
        target.setName("成品 A");
        target.setProductCategory(ProductCategory.FINISHED_PRODUCT);
        when(products.findByIdAndFactoryId("FG-A", "F006")).thenReturn(Optional.of(target));
        when(products.findByIdIn(anyList())).thenReturn(List.of(target));
        CanonicalUnit kg = new CanonicalUnit(
                "kg", UnitDimension.MASS, "g", new BigDecimal("1000"), "千克", 3);
        when(units.normalize("F006", "kg"))
                .thenReturn(new UnitNormalizationResult("kg", "kg", kg));

        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F006");
        activation.setProductTypeId("FG-A");
        activation.setActiveWorkflowId(41L);
        activation.setActiveDefinitionVersion(3);
        activation.setEnabled(true);
        when(activations.findByFactoryIdAndProductTypeId("F006", "FG-A"))
                .thenReturn(Optional.of(activation));
        when(activations.findByFactoryIdAndEnabledTrue("F006"))
                .thenReturn(List.of(activation));

        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(41L);
        workflow.setFactoryId("F006");
        workflow.setProductTypeId("FG-A");
        workflow.setDefinitionVersion(3);
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setUnitReviewRequired(false);
        workflow.setNodesJson("""
                [
                  {"id":"raw","kind":"RAW_MATERIAL","data":{"skuId":"RAW-1"}},
                  {"id":"shared","kind":"PROCESS","data":{"workProcessId":"WP-SHARED","ports":[]}},
                  {"id":"semi","kind":"SEMI_FINISHED","data":{"skuId":"SEMI-1"}},
                  {"id":"target-process","kind":"PROCESS","data":{"workProcessId":"WP-A","ports":[{"direction":"OUTPUT","materialNodeId":"fg-a","unit":"kg"}]}},
                  {"id":"fg-a","kind":"FINISHED_GOOD","data":{"skuId":"FG-A"}},
                  {"id":"sibling-process","kind":"PROCESS","data":{"workProcessId":"WP-B","ports":[{"direction":"OUTPUT","materialNodeId":"fg-b","unit":"kg"}]}},
                  {"id":"fg-b","kind":"FINISHED_GOOD","data":{"skuId":"FG-B"}}
                ]
                """);
        workflow.setEdgesJson("""
                [
                  {"id":"e1","source":"raw","sourceHandle":"output","target":"shared","targetHandle":"in"},
                  {"id":"e2","source":"shared","sourceHandle":"out","target":"semi","targetHandle":"input"},
                  {"id":"e3","source":"semi","sourceHandle":"output","target":"target-process","targetHandle":"in"},
                  {"id":"e4","source":"target-process","sourceHandle":"out","target":"fg-a","targetHandle":"input"},
                  {"id":"e5","source":"semi","sourceHandle":"output","target":"sibling-process","targetHandle":"in"},
                  {"id":"e6","source":"sibling-process","sourceHandle":"out","target":"fg-b","targetHandle":"input"}
                ]
                """);
        when(workflows.findByIdAndFactoryId(41L, "F006")).thenReturn(Optional.of(workflow));

        WorkflowProcessPath path = service.resolveProcessPath("F006", "FG-A").orElseThrow();

        assertEquals(List.of("WP-SHARED", "WP-A"),
                path.processes().stream().map(WorkflowProcessPath.ProcessStep::workProcessId).toList());
        assertEquals("RAW-1", path.rawRootMaterialTypeId());
    }
}
