package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowOutputResolutionDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductWorkflowUnifiedResolutionTest {

    private ProductProcessWorkflowActivationRepository activations;
    private ProductProcessWorkflowRepository workflows;
    private ProductTypeRepository products;
    private ProductWorkflowResolutionService service;
    private final List<ProductProcessWorkflowActivation> enabled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        activations = mock(ProductProcessWorkflowActivationRepository.class);
        workflows = mock(ProductProcessWorkflowRepository.class);
        products = mock(ProductTypeRepository.class);
        UnitContractService units = mock(UnitContractService.class);
        CanonicalUnit kg = new CanonicalUnit(
                "kg", UnitDimension.MASS, "g", new BigDecimal("1000"), "千克", 3);
        when(units.normalize("F1", "kg"))
                .thenReturn(new UnitNormalizationResult("kg", "kg", kg));
        when(activations.findByFactoryIdAndEnabledTrue("F1")).thenAnswer(ignored -> List.copyOf(enabled));
        when(products.findByIdIn(anyList())).thenAnswer(invocation -> invocation.<List<String>>getArgument(0)
                .stream().map(this::product).toList());
        service = new ProductWorkflowResolutionServiceImpl(
                activations, workflows, products, mock(RawMaterialTypeRepository.class),
                new ObjectMapper(), units);
        for (String sku : List.of("P1", "P2", "P3")) {
            when(products.findByIdAndFactoryId(sku, "F1")).thenReturn(Optional.of(product(sku)));
        }
    }

    @Test
    void singleSelectionUsesOnlySingleOutputWorkflow() {
        activate(11L, "ANCHOR-M", List.of("RAW"), List.of("P1", "P2"), LocalDateTime.now());
        activate(12L, "ANCHOR-S", List.of("RAW-A", "RAW-B"), List.of("P1"), LocalDateTime.now().minusDays(1));

        WorkflowOutputResolutionDTO result = service.resolveForOutputs("F1", List.of("P1"));

        assertEquals("SINGLE_OUTPUT", result.getResolutionMode());
        assertEquals(12L, result.getCandidates().getFirst().getWorkflowId());
        assertEquals("SINGLE_OUTPUT_PRODUCT", result.getCandidates().getFirst().getWorkflowType());
    }

    @Test
    void singleSelectionDoesNotFallbackToMultiOutputWorkflow() {
        activate(11L, "ANCHOR-M", List.of("RAW"), List.of("P1", "P2"), LocalDateTime.now());

        WorkflowOutputResolutionDTO result = service.resolveForOutputs("F1", List.of("P1"));

        assertEquals("NONE", result.getResolutionMode());
        assertEquals("该产品没有单产出 Workflow，请前往创建单产出 Workflow", result.getMessage());
    }

    @Test
    void multiSelectionPrefersExactTerminalSetBeforeNewerSuperset() {
        activate(21L, "ANCHOR-SUPER", List.of("RAW"), List.of("P1", "P2", "P3"), LocalDateTime.now());
        activate(22L, "ANCHOR-EXACT", List.of("RAW-A", "RAW-B"), List.of("P1", "P2"),
                LocalDateTime.now().minusDays(1));

        WorkflowOutputResolutionDTO result = service.resolveForOutputs("F1", List.of("P1", "P2"));

        assertEquals("MULTI_OUTPUT", result.getResolutionMode());
        assertEquals(22L, result.getCandidates().getFirst().getWorkflowId());
        assertEquals("JOINT_PRODUCTION", result.getCandidates().getFirst().getWorkflowType());
    }

    @Test
    void multiSelectionWithoutSharedWorkflowReturnsGuidance() {
        activate(31L, "ANCHOR-A", List.of("RAW"), List.of("P1", "P3"), LocalDateTime.now());

        WorkflowOutputResolutionDTO result = service.resolveForOutputs("F1", List.of("P1", "P2"));

        assertEquals("NONE", result.getResolutionMode());
        assertEquals("未找到共享的工序 Workflow，请分开创建生产计划", result.getMessage());
    }

    @Test
    void duplicateActiveSingleOutputWorkflowsFailLoudly() {
        activate(41L, "ANCHOR-A", List.of("RAW-A"), List.of("P1"), LocalDateTime.now());
        activate(42L, "ANCHOR-B", List.of("RAW-B"), List.of("P1"), LocalDateTime.now().minusDays(1));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.resolveForOutputs("F1", List.of("P1")));

        assertEquals("WORKFLOW_SINGLE_OUTPUT_AMBIGUOUS", error.getErrorCode());
    }

    private void activate(
            Long id, String anchor, List<String> roots, List<String> terminals,
            LocalDateTime activatedAt) {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(id);
        workflow.setFactoryId("F1");
        workflow.setProductTypeId(anchor);
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setDefinitionVersion(1);
        workflow.setUnitReviewRequired(false);
        workflow.setNodesJson(nodesJson(roots, terminals));
        workflow.setEdgesJson("[]");
        when(workflows.findByIdAndFactoryId(id, "F1")).thenReturn(Optional.of(workflow));

        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F1");
        activation.setProductTypeId(anchor);
        activation.setActiveWorkflowId(id);
        activation.setActiveDefinitionVersion(1);
        activation.setEnabled(true);
        activation.setActivatedAt(activatedAt);
        enabled.add(activation);
    }

    private String nodesJson(List<String> roots, List<String> terminals) {
        List<String> nodes = new ArrayList<>();
        int index = 0;
        for (String root : roots) {
            nodes.add("{\"id\":\"raw-" + index++ + "\",\"kind\":\"RAW_MATERIAL\",\"data\":{\"skuId\":\"" + root + "\"}}");
        }
        List<String> ports = new ArrayList<>();
        index = 0;
        for (String terminal : terminals) {
            String nodeId = "fg-" + index++;
            nodes.add("{\"id\":\"" + nodeId + "\",\"kind\":\"FINISHED_GOOD\",\"data\":{\"skuId\":\"" + terminal + "\"}}");
            ports.add("{\"direction\":\"OUTPUT\",\"materialNodeId\":\"" + nodeId + "\",\"unit\":\"kg\"}");
        }
        nodes.add("{\"id\":\"process\",\"kind\":\"PROCESS\",\"data\":{\"ports\":[" + String.join(",", ports) + "]}}");
        return "[" + String.join(",", nodes) + "]";
    }

    private ProductType product(String id) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId("F1");
        product.setName(id);
        product.setUnit("kg");
        product.setProductCategory(ProductCategory.FINISHED_PRODUCT);
        return product;
    }
}
