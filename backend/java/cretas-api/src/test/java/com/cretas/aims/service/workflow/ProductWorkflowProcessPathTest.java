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
import static org.junit.jupiter.api.Assertions.assertNull;
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
                new ObjectMapper(), units,
                mock(com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator.class));

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

    @Test
    void activeTwoByTwoWorkflowPreservesBothRootsAndTargetSlice() {
        ProductProcessWorkflowActivationRepository activations =
                mock(ProductProcessWorkflowActivationRepository.class);
        ProductProcessWorkflowRepository workflows = mock(ProductProcessWorkflowRepository.class);
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        UnitContractService units = mock(UnitContractService.class);
        ProductWorkflowResolutionService service = new ProductWorkflowResolutionServiceImpl(
                activations, workflows, products, mock(RawMaterialTypeRepository.class),
                new ObjectMapper(), units,
                mock(com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator.class));

        ProductType target = product("FG-A", "成品 A");
        ProductType byproduct = product("FG-B", "副产品 B");
        when(products.findByIdAndFactoryId("FG-A", "F006")).thenReturn(Optional.of(target));
        when(products.findByIdAndFactoryId("FG-B", "F006")).thenReturn(Optional.of(byproduct));
        when(products.findByIdIn(anyList())).thenReturn(List.of(target));
        CanonicalUnit kg = new CanonicalUnit(
                "kg", UnitDimension.MASS, "g", new BigDecimal("1000"), "千克", 3);
        when(units.normalize("F006", "kg"))
                .thenReturn(new UnitNormalizationResult("kg", "kg", kg));

        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F006");
        activation.setProductTypeId("FG-A");
        activation.setActiveWorkflowId(42L);
        activation.setActiveDefinitionVersion(4);
        activation.setEnabled(true);
        when(activations.findByFactoryIdAndProductTypeId("F006", "FG-A"))
                .thenReturn(Optional.of(activation));
        when(activations.findByFactoryIdAndEnabledTrue("F006"))
                .thenReturn(List.of(activation));

        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(42L);
        workflow.setFactoryId("F006");
        workflow.setProductTypeId("FG-A");
        workflow.setDefinitionVersion(4);
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setUnitReviewRequired(false);
        workflow.setNodesJson("""
                [
                  {"id":"raw-a","kind":"RAW_MATERIAL","data":{"skuId":"RAW-A"}},
                  {"id":"raw-b","kind":"RAW_MATERIAL","data":{"skuId":"RAW-B"}},
                  {"id":"blend","kind":"PROCESS","data":{"workProcessId":"WP-BLEND","ports":[
                    {"id":"in-a","direction":"INPUT","materialNodeId":"raw-a","unit":"kg"},
                    {"id":"in-b","direction":"INPUT","materialNodeId":"raw-b","unit":"kg"},
                    {"id":"out-semi","direction":"OUTPUT","materialNodeId":"semi","unit":"kg"}]}},
                  {"id":"semi","kind":"SEMI_FINISHED","data":{"skuId":"SEMI-1"}},
                  {"id":"split","kind":"PROCESS","data":{"workProcessId":"WP-SPLIT","ports":[
                    {"id":"in-semi","direction":"INPUT","materialNodeId":"semi","unit":"kg"},
                    {"id":"out-main","direction":"OUTPUT","materialNodeId":"fg-a","unit":"kg","outputRole":"MAIN","costAllocationRatio":75},
                    {"id":"out-by","direction":"OUTPUT","materialNodeId":"fg-b","unit":"kg","outputRole":"CO_PRODUCT","costAllocationRatio":25}]}},
                  {"id":"fg-a","kind":"FINISHED_GOOD","data":{"skuId":"FG-A"}},
                  {"id":"fg-b","kind":"FINISHED_GOOD","data":{"skuId":"FG-B"}}
                ]
                """);
        workflow.setEdgesJson("""
                [
                  {"id":"e1","source":"raw-a","sourceHandle":"output","target":"blend","targetHandle":"in-a"},
                  {"id":"e2","source":"raw-b","sourceHandle":"output","target":"blend","targetHandle":"in-b"},
                  {"id":"e3","source":"blend","sourceHandle":"out-semi","target":"semi","targetHandle":"input"},
                  {"id":"e4","source":"semi","sourceHandle":"output","target":"split","targetHandle":"in-semi"},
                  {"id":"e5","source":"split","sourceHandle":"out-main","target":"fg-a","targetHandle":"input"},
                  {"id":"e6","source":"split","sourceHandle":"out-by","target":"fg-b","targetHandle":"input"}
                ]
                """);
        when(workflows.findByIdAndFactoryId(42L, "F006")).thenReturn(Optional.of(workflow));

        WorkflowProcessPath path = service.resolveProcessPath("F006", "FG-A").orElseThrow();

        assertNull(path.rawRootMaterialTypeId());
        assertEquals(List.of("RAW-A", "RAW-B"), path.rawRootMaterialTypeIds());
        assertEquals(List.of("WP-BLEND", "WP-SPLIT"), path.processes().stream()
                .map(WorkflowProcessPath.ProcessStep::workProcessId).toList());
    }

    private ProductType product(String id, String name) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId("F006");
        product.setName(name);
        product.setProductCategory(ProductCategory.FINISHED_PRODUCT);
        return product;
    }
}
