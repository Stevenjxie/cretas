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
    void singleSelectionPrefersExactSingleOutputWorkflowOverMultiOutputSuperset() {
        activate(11L, "ANCHOR-M", List.of("RAW"), List.of("P1", "P2"), LocalDateTime.now());
        activate(12L, "ANCHOR-S", List.of("RAW-A", "RAW-B"), List.of("P1"), LocalDateTime.now().minusDays(1));

        WorkflowOutputResolutionDTO result = service.resolveForOutputs("F1", List.of("P1"));

        assertEquals("SINGLE_OUTPUT", result.getResolutionMode());
        assertEquals(12L, result.getCandidates().getFirst().getWorkflowId());
        assertEquals("SINGLE_OUTPUT_PRODUCT", result.getCandidates().getFirst().getWorkflowType());
    }

    @Test
    void singleSelectionFallsBackToTheSmallestMultiOutputSuperset() {
        activate(11L, "ANCHOR-SMALL", List.of("RAW"), List.of("P1", "P2"), LocalDateTime.now());
        activate(12L, "ANCHOR-LARGE", List.of("RAW-B"), List.of("P1", "P2", "P3"),
                LocalDateTime.now().minusHours(1));

        WorkflowOutputResolutionDTO result = service.resolveForOutputs("F1", List.of("P1"));

        assertEquals("SINGLE_OUTPUT", result.getResolutionMode());
        assertEquals(List.of(11L), result.getCandidates().stream()
                .map(WorkflowOutputResolutionDTO.Candidate::getWorkflowId).toList());
        assertEquals(false, result.getCandidates().getFirst().isExactMatch());
        assertEquals("匹配到包含额外联产成品的 Workflow，请确认完整产出集合", result.getMessage());
    }

    @Test
    void singleSelectionReturnsAllCandidatesInTheSmallestSupersetLayer() {
        activate(13L, "ANCHOR-A", List.of("RAW-A"), List.of("P1", "P2"), LocalDateTime.now());
        activate(14L, "ANCHOR-LARGE", List.of("RAW-B"), List.of("P1", "P2", "P3"),
                LocalDateTime.now().minusHours(1));
        activate(15L, "ANCHOR-B", List.of("RAW-C"), List.of("P1", "P3"),
                LocalDateTime.now().minusHours(2));

        WorkflowOutputResolutionDTO result = service.resolveForOutputs("F1", List.of("P1"));

        assertEquals(List.of(13L, 15L), result.getCandidates().stream()
                .map(WorkflowOutputResolutionDTO.Candidate::getWorkflowId).toList());
        assertEquals("匹配到多条同优先级 Workflow，请根据工序链选择本计划使用的版本", result.getMessage());
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
    void duplicateActiveSingleOutputWorkflowsAreReturnedForExplicitSelection() {
        activate(41L, "ANCHOR-A", List.of("RAW-A"), List.of("P1"), LocalDateTime.now());
        activate(42L, "ANCHOR-B", List.of("RAW-B"), List.of("P1"), LocalDateTime.now().minusDays(1));

        WorkflowOutputResolutionDTO result = service.resolveForOutputs("F1", List.of("P1"));

        assertEquals(List.of(41L, 42L), result.getCandidates().stream()
                .map(WorkflowOutputResolutionDTO.Candidate::getWorkflowId).toList());
        assertEquals("匹配到多条同优先级 Workflow，请根据工序链选择本计划使用的版本", result.getMessage());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.resolveProcessPath("F1", "P1"));
        assertEquals("WORKFLOW_RESOLUTION_AMBIGUOUS", error.getErrorCode());
    }

    @Test
    void multiSelectionReturnsOnlyTheSmallestSupersetLayer() {
        activate(43L, "ANCHOR-SMALL-A", List.of("RAW-A"), List.of("P1", "P2", "P3"),
                LocalDateTime.now());
        activate(44L, "ANCHOR-LARGE", List.of("RAW-B"), List.of("P1", "P2", "P3", "P4"),
                LocalDateTime.now().minusHours(1));
        activate(45L, "ANCHOR-SMALL-B", List.of("RAW-C"), List.of("P1", "P2", "P3"),
                LocalDateTime.now().minusHours(2));
        when(products.findByIdAndFactoryId("P4", "F1")).thenReturn(Optional.of(product("P4")));

        WorkflowOutputResolutionDTO result = service.resolveForOutputs("F1", List.of("P1", "P2"));

        assertEquals(List.of(43L, 45L), result.getCandidates().stream()
                .map(WorkflowOutputResolutionDTO.Candidate::getWorkflowId).toList());
        assertEquals(false, result.getCandidates().getFirst().isExactMatch());
    }

    @Test
    void candidateCarriesSanitizedProcessAndCellPreview() {
        activate(46L, "ANCHOR-PREVIEW", List.of("RAW-A"), List.of("P1"), LocalDateTime.now());

        WorkflowOutputResolutionDTO.Candidate candidate = service.resolveForOutputs("F1", List.of("P1"))
                .getCandidates().getFirst();

        assertEquals(List.of("原料处理"), candidate.getProcessSteps());
        assertEquals(3, candidate.getPreviewNodes().size());
        assertEquals("原料处理", candidate.getPreviewNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind())).findFirst().orElseThrow().getLabel());
    }

    @Test
    void planContractRevalidatesTheSelectedAnchorInsteadOfChoosingAnotherMatch() {
        activate(51L, "ANCHOR-NEW", List.of("RAW-A"), List.of("P1", "P2"),
                LocalDateTime.now());
        activate(52L, "ANCHOR-SELECTED", List.of("RAW-B"), List.of("P1", "P2"),
                LocalDateTime.now().minusDays(1));

        WorkflowPlanOutputContract result = service.resolveActivePlanOutputContract(
                "F1", "ANCHOR-SELECTED", List.of("P1", "P2")).orElseThrow();

        assertEquals(52L, result.workflowId());
    }

    @Test
    void pinnedPlanContractRejectsAWorkflowVersionThatIsNoLongerTheActiveSelection() {
        activate(53L, "ANCHOR-PINNED", List.of("RAW-A"), List.of("P1"), LocalDateTime.now());

        WorkflowPlanOutputContract contract = service.resolvePinnedPlanOutputContract(
                "F1", "ANCHOR-PINNED", 53L, 1, List.of("P1"));
        assertEquals(53L, contract.workflowId());

        BusinessException stale = assertThrows(BusinessException.class,
                () -> service.resolvePinnedPlanOutputContract(
                        "F1", "ANCHOR-PINNED", 53L, 2, List.of("P1")));
        assertEquals("WORKFLOW_SELECTED_VERSION_CHANGED", stale.getErrorCode());
    }

    @Test
    void pinnedPlanContractAcceptsOneSelectedProductFromJointOutputs() {
        activate(54L, "ANCHOR-JOINT", List.of("RAW-A", "RAW-B"), List.of("P1", "P2"),
                LocalDateTime.now());

        WorkflowPlanOutputContract contract = service.resolvePinnedPlanOutputContract(
                "F1", "ANCHOR-JOINT", 54L, 1, List.of("P1"));

        assertEquals(54L, contract.workflowId());
        assertEquals(List.of("P1"), contract.outputUnitBySku().keySet().stream().toList());
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
        workflow.setEdgesJson(edgesJson(roots.size(), terminals.size()));
        when(workflows.findByIdAndFactoryId(id, "F1")).thenReturn(Optional.of(workflow));

        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F1");
        activation.setProductTypeId(anchor);
        activation.setActiveWorkflowId(id);
        activation.setActiveDefinitionVersion(1);
        activation.setEnabled(true);
        activation.setActivatedAt(activatedAt);
        enabled.add(activation);
        when(activations.findByFactoryIdAndProductTypeId("F1", anchor))
                .thenReturn(Optional.of(activation));
    }

    private String nodesJson(List<String> roots, List<String> terminals) {
        List<String> nodes = new ArrayList<>();
        int index = 0;
        for (String root : roots) {
            nodes.add("{\"id\":\"raw-" + index++ + "\",\"kind\":\"RAW_MATERIAL\",\"position\":{\"x\":0,\"y\":0},\"data\":{\"skuId\":\"" + root + "\",\"name\":\"" + root + "\",\"baseUnit\":\"kg\"}}");
        }
        List<String> ports = new ArrayList<>();
        index = 0;
        for (String terminal : terminals) {
            String nodeId = "fg-" + index++;
            nodes.add("{\"id\":\"" + nodeId + "\",\"kind\":\"FINISHED_GOOD\",\"position\":{\"x\":400,\"y\":0},\"data\":{\"skuId\":\"" + terminal + "\",\"name\":\"" + terminal + "\",\"baseUnit\":\"kg\"}}");
            ports.add("{\"direction\":\"OUTPUT\",\"materialNodeId\":\"" + nodeId + "\",\"unit\":\"kg\"}");
        }
        nodes.add("{\"id\":\"process\",\"kind\":\"PROCESS\",\"position\":{\"x\":200,\"y\":0},\"data\":{\"processName\":\"原料处理\",\"inputUnit\":\"kg\",\"outputUnit\":\"kg\",\"ports\":[" + String.join(",", ports) + "]}}");
        return "[" + String.join(",", nodes) + "]";
    }

    private String edgesJson(int rootCount, int terminalCount) {
        List<String> edges = new ArrayList<>();
        for (int index = 0; index < rootCount; index++) {
            edges.add("{\"id\":\"edge-in-" + index + "\",\"source\":\"raw-" + index
                    + "\",\"target\":\"process\"}");
        }
        for (int index = 0; index < terminalCount; index++) {
            edges.add("{\"id\":\"edge-out-" + index + "\",\"source\":\"process\",\"target\":\"fg-"
                    + index + "\"}");
        }
        return "[" + String.join(",", edges) + "]";
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
