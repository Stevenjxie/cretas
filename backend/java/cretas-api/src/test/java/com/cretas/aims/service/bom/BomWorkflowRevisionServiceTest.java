package com.cretas.aims.service.bom;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.bom.BomWorkflowRevisionPinRequest;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.cretas.aims.service.workflow.WorkflowRevisionSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomWorkflowRevisionServiceTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "FG-001";

    @Mock BomRecipeRepository recipeRepository;
    @Mock ProductProcessWorkflowRepository workflowRepository;
    @Mock ProductProcessWorkflowRevisionRepository revisionRepository;
    @Mock ProductProcessWorkflowActivationRepository activationRepository;
    @Mock ProductProcessWorkflowCatalogValidator catalogValidator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProductProcessWorkflowValidator validator;
    private WorkflowRevisionSnapshotService snapshotService;
    private BomWorkflowRevisionService service;

    @BeforeEach
    void setUp() {
        validator = new ProductProcessWorkflowValidator();
        snapshotService = new WorkflowRevisionSnapshotService(revisionRepository, validator, objectMapper);
        service = new BomWorkflowRevisionService(recipeRepository, workflowRepository, revisionRepository,
                activationRepository, validator, catalogValidator, snapshotService, objectMapper);
    }

    @Test
    void matrixA_oneInputOneOutputKeepsOneRootAndOneProcess() throws Exception {
        PinnedWorkflowGraph graph = service.resolvePinnedGraph(FACTORY,
                recipe(snapshot(oneToOne())));

        assertEquals(List.of("RM-A"), graph.rootMaterialTypeIds());
        assertEquals(List.of("process-1"), graph.processes().stream()
                .map(PinnedWorkflowGraph.ProcessStep::processNodeId).toList());
    }

    @Test
    void matrixB_twoInputsConvergeToOneOutputWithoutFirstRootLoss() throws Exception {
        PinnedWorkflowGraph graph = service.resolvePinnedGraph(FACTORY,
                recipe(snapshot(twoToOne())));

        assertEquals(List.of("RM-A", "RM-B"), graph.rootMaterialTypeIds());
        assertEquals(1, graph.processes().size());
        assertEquals("blend", graph.processes().getFirst().processNodeId());
    }

    @Test
    void matrixC_oneInputTwoOutputsRequiresExplicitRolesAndAllocation() throws Exception {
        PinnedWorkflowGraph graph = service.resolvePinnedGraph(FACTORY,
                recipe(snapshot(oneToTwo(true))));

        assertEquals(List.of("RM-A"), graph.rootMaterialTypeIds());
        assertEquals(PRODUCT, graph.targetProductTypeId());
        assertEquals("split", graph.processes().getFirst().processNodeId());
        assertTrue(graph.nodes().stream().anyMatch(node -> "finished".equals(node.getId())));
        assertFalse(graph.nodes().stream().anyMatch(node -> "byproduct".equals(node.getId())),
                "the sibling output must not leak into the selected target-SKU slice");
    }

    @Test
    void matrixD_twoInputsTwoOutputsRoundTripsExactPinnedSnapshot() throws Exception {
        ProductProcessWorkflowDTO definition = twoToTwo();
        ProductProcessWorkflowRevision revision = revision(definition);
        BomRecipe recipe = recipe(null);
        when(recipeRepository.lockByIdAndFactoryId("BOM-1", FACTORY)).thenReturn(Optional.of(recipe));
        when(revisionRepository.findByIdAndFactoryId(71L, FACTORY)).thenReturn(Optional.of(revision));
        when(recipeRepository.saveAndFlush(recipe)).thenReturn(recipe);
        BomWorkflowRevisionPinRequest request = new BomWorkflowRevisionPinRequest();
        request.setRevisionId(71L);
        request.setRevisionHash(revision.getRevisionHash());

        BomRecipe pinned = service.pin(FACTORY, "BOM-1", request);
        PinnedWorkflowGraph graph = service.resolvePinnedGraph(FACTORY, pinned);

        assertEquals(revision.getNodesJson(), pinned.getWorkflowNodesSnapshotJson());
        assertEquals(revision.getEdgesJson(), pinned.getWorkflowEdgesSnapshotJson());
        assertEquals(List.of("RM-A", "RM-B"), graph.rootMaterialTypeIds());
        assertEquals(List.of("blend", "split"), graph.processes().stream()
                .map(PinnedWorkflowGraph.ProcessStep::processNodeId).toList());
        assertTrue(graph.nodes().stream().anyMatch(node -> "finished".equals(node.getId())));
        assertFalse(graph.nodes().stream().anyMatch(node -> "byproduct".equals(node.getId())),
                "the pinned readback must remain isolated to the BOM target SKU");
    }

    @Test
    void matrixE_convergenceSemiFinishedThenSplitKeepsCompleteTargetSlice() throws Exception {
        PinnedWorkflowGraph graph = service.resolvePinnedGraph(FACTORY,
                recipe(snapshot(twoToTwo())));

        assertEquals(2, graph.processes().size());
        assertTrue(graph.nodes().stream().anyMatch(node -> "semi".equals(node.getId())));
        assertTrue(graph.edges().stream().anyMatch(edge -> "semi".equals(edge.getSource())));
    }

    @Test
    void multiOutputProcessScopesAreDerivedFromStableNodePresenceAcrossTerminalSlices() throws Exception {
        BomRecipe recipe = recipe(snapshot(twoToTwo()));

        Map<String, String> scopes = service.resolveProcessCostScopes(FACTORY, recipe);

        assertEquals("SHARED", scopes.get("blend"));
        assertEquals("SHARED", scopes.get("split"));
    }

    @Test
    void firstBomAutoBindsTheOnlyCompatibleFactoryDraftEvenWhenWorkflowOwnerDiffers() throws Exception {
        BomRecipe draft = recipe(null);
        ProductProcessWorkflowRevision revision = revision(oneToOne());
        revision.setProductTypeId("UPSTREAM-WORKFLOW-OWNER");
        revision.setRevisionHash(snapshotService.hash(revision));
        when(revisionRepository.findCurrentFactoryDraftRevisions(FACTORY)).thenReturn(List.of(revision));
        when(recipeRepository.saveAndFlush(draft)).thenReturn(draft);

        BomWorkflowRevisionService.WorkflowBinding binding = service.autoBindUniqueDraft(FACTORY, draft);

        assertEquals(revision.getId(), draft.getWorkflowRevisionId());
        assertEquals(revision.getWorkflowId(), draft.getWorkflowId());
        assertEquals(revision.getRevisionHash(), draft.getWorkflowRevisionHash());
        assertEquals(revision.getNodesJson(), draft.getWorkflowNodesSnapshotJson());
        assertEquals(revision.getEdgesJson(), draft.getWorkflowEdgesSnapshotJson());
        assertEquals("finished", draft.getTargetTerminalNodeId());
        assertEquals(BomRecipe.OutputRole.MAIN, draft.getOutputRole());
        assertEquals(new BigDecimal("100"), draft.getCostAllocationRatio());
        assertEquals(PRODUCT, binding.target().productTypeId());
        verify(recipeRepository).saveAndFlush(draft);
    }

    @Test
    void firstBomSurfacesTheOnlyTargetDraftsExactOutputContractFailure() throws Exception {
        BomRecipe draft = recipe(null);
        ProductProcessWorkflowRevision revision = revision(oneToTwo(false));
        revision.setRevisionHash(snapshotService.hash(revision));
        when(revisionRepository.findCurrentFactoryDraftRevisions(FACTORY)).thenReturn(List.of(revision));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.autoBindUniqueDraft(FACTORY, draft));

        assertEquals("BOM_WORKFLOW_MULTI_OUTPUT_CONTRACT_REQUIRED", error.getErrorCode());
        assertTrue(error.getMessage().contains("角色和成本分摊比例"));
        verify(recipeRepository, never()).saveAndFlush(draft);
    }

    @Test
    void actualIoDraftAutoBindsWithoutUserAuthoredOutputRolesOrRatios() throws Exception {
        BomRecipe draft = recipe(null);
        ProductProcessWorkflowDTO definition = oneToTwo(false);
        definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind()))
                .forEach(node -> node.getData().put("reportingSelectionMode", "ACTUAL_IO"));
        ProductProcessWorkflowRevision revision = revision(definition);
        revision.setRevisionHash(snapshotService.hash(revision));
        when(revisionRepository.findCurrentFactoryDraftRevisions(FACTORY))
                .thenReturn(List.of(revision));
        when(recipeRepository.saveAndFlush(draft)).thenReturn(draft);

        BomWorkflowRevisionService.WorkflowBinding binding =
                service.autoBindUniqueDraft(FACTORY, draft);

        assertEquals(revision.getId(), draft.getWorkflowRevisionId());
        assertEquals("finished", draft.getTargetTerminalNodeId());
        assertEquals(PRODUCT, binding.target().productTypeId());
        verify(recipeRepository).saveAndFlush(draft);
    }

    @Test
    void currentUnreferencedDraftRepairsCorruptSnapshotBeforeAutoBinding() throws Exception {
        BomRecipe draft = recipe(null);
        ProductProcessWorkflowDTO definition = oneToOne();
        definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind()))
                .forEach(node -> node.getData().put("reportingSelectionMode", "ACTUAL_IO"));
        ProductProcessWorkflowRevision revision = revision(definition);

        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(revision.getWorkflowId());
        workflow.setFactoryId(FACTORY);
        workflow.setProductTypeId(revision.getProductTypeId());
        workflow.setStatus(ProductProcessWorkflow.Status.DRAFT);
        workflow.setDefinitionVersion(revision.getDefinitionVersion());
        workflow.setSchemaVersion(revision.getSchemaVersion());
        workflow.setNodesJson(revision.getNodesJson());
        workflow.setEdgesJson(revision.getEdgesJson());
        workflow.setViewportJson(revision.getViewportJson());
        workflow.setCurrentRevisionId(revision.getId());
        String advertisedHash = snapshotService.hash(workflow);
        workflow.setCurrentRevisionHash(advertisedHash);
        revision.setRevisionHash(advertisedHash);
        revision.setNodesJson("[]");

        when(revisionRepository.findCurrentFactoryDraftRevisions(FACTORY))
                .thenReturn(List.of(revision));
        when(workflowRepository.findByIdAndFactoryId(revision.getWorkflowId(), FACTORY))
                .thenReturn(Optional.of(workflow));
        for (BomRecipe.Status status : BomRecipe.Status.values()) {
            when(recipeRepository
                    .findByFactoryIdAndWorkflowRevisionIdAndStatusOrderByProductTypeIdAsc(
                            FACTORY, revision.getId(), status))
                    .thenReturn(List.of());
        }
        when(revisionRepository.saveAndFlush(revision)).thenReturn(revision);
        when(workflowRepository.saveAndFlush(workflow)).thenReturn(workflow);
        when(recipeRepository.saveAndFlush(draft)).thenReturn(draft);

        BomWorkflowRevisionService.WorkflowBinding binding =
                service.autoBindUniqueDraft(FACTORY, draft);

        assertEquals(advertisedHash, snapshotService.hash(revision));
        assertEquals(revision.getId(), binding.revision().getId());
        assertEquals(revision.getId(), draft.getWorkflowRevisionId());
        verify(revisionRepository).saveAndFlush(revision);
        verify(workflowRepository).saveAndFlush(workflow);
    }

    @Test
    void firstBomRejectsAmbiguousCompatibleDraftsBeforeWriting() throws Exception {
        BomRecipe draft = recipe(null);
        ProductProcessWorkflowRevision first = revision(oneToOne());
        ProductProcessWorkflowRevision second = revision(oneToOne());
        second.setId(72L);
        second.setWorkflowId(42L);
        when(revisionRepository.findCurrentFactoryDraftRevisions(FACTORY)).thenReturn(List.of(first, second));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.autoBindUniqueDraft(FACTORY, draft));

        assertEquals("BOM_WORKFLOW_DRAFT_AMBIGUOUS", error.getErrorCode());
        verify(recipeRepository, never()).saveAndFlush(draft);
    }

    @Test
    void stableInputSlotsRetainEveryConvergingRootAndPortIdentity() throws Exception {
        PinnedWorkflowGraph graph = service.resolvePinnedGraph(FACTORY, recipe(snapshot(twoToOne())));

        List<BomWorkflowRevisionService.InputSlot> slots =
                BomWorkflowRevisionService.resolveInputSlots(graph);

        assertEquals(List.of("raw-a:blend:in-a", "raw-b:blend:in-b"), slots.stream()
                .map(slot -> slot.materialNodeId() + ":" + slot.processNodeId() + ":" + slot.inputPortId())
                .toList());
        assertEquals(List.of("RM-A", "RM-B"), slots.stream()
                .map(BomWorkflowRevisionService.InputSlot::materialTypeId)
                .toList());
        assertEquals(List.of("e1", "e2"), slots.stream()
                .map(BomWorkflowRevisionService.InputSlot::edgeId)
                .toList());
    }

    @Test
    void selectorShowsSavedPublishedAndEnabledStateAndRecommendsLatestDraft() throws Exception {
        BomRecipe recipe = recipe(null);
        recipe.setWorkflowRevisionId(71L);
        ProductProcessWorkflowRevision draft = revision(oneToOne());
        draft.setId(72L);
        draft.setWorkflowId(42L);
        draft.setRevisionNumber(4);
        draft.setStatus(ProductProcessWorkflowRevision.Status.DRAFT);
        draft.setCreatedAt(LocalDateTime.of(2026, 7, 20, 12, 30));
        draft.setRevisionHash(snapshotService.hash(draft));
        ProductProcessWorkflowRevision published = revision(oneToOne());
        published.setId(71L);
        published.setStatus(ProductProcessWorkflowRevision.Status.PUBLISHED);
        published.setCreatedAt(LocalDateTime.of(2026, 7, 20, 12, 0));
        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId(FACTORY);
        activation.setProductTypeId(PRODUCT);
        activation.setActiveWorkflowId(41L);
        activation.setEnabled(true);
        when(recipeRepository.findById("BOM-1")).thenReturn(Optional.of(recipe));
        when(revisionRepository.findCurrentFactoryDraftRevisions(FACTORY)).thenReturn(List.of(draft));
        when(revisionRepository.findByIdAndFactoryId(71L, FACTORY)).thenReturn(Optional.of(published));
        when(workflowRepository.findByFactoryIdAndProductTypeIdOrderByDefinitionVersionDesc(FACTORY, PRODUCT))
                .thenReturn(List.of());
        when(activationRepository.findByFactoryIdAndProductTypeId(FACTORY, PRODUCT))
                .thenReturn(Optional.of(activation));

        var candidates = service.listCompatible(FACTORY, "BOM-1");

        assertEquals(List.of("DRAFT", "PUBLISHED"), candidates.stream()
                .map(candidate -> candidate.getStatus()).toList());
        assertTrue(candidates.getFirst().isRecommended());
        assertFalse(candidates.getFirst().isEnabled());
        assertTrue(candidates.get(1).isEnabled());
        assertEquals(1, candidates.getFirst().getProcessCount());
    }

    @Test
    void rejectsMultiOutputWithoutRoleAllocation() throws Exception {
        BusinessException error = assertThrows(BusinessException.class, () ->
                service.resolvePinnedGraph(FACTORY, recipe(snapshot(oneToTwo(false)))));
        assertEquals("BOM_WORKFLOW_MULTI_OUTPUT_CONTRACT_REQUIRED", error.getErrorCode());
    }

    @Test
    void rejectsDuplicateTargetTerminalInsteadOfChoosingFirst() throws Exception {
        ProductProcessWorkflowDTO definition = oneToTwo(true);
        definition.getNodes().stream().filter(node -> "byproduct".equals(node.getId()))
                .forEach(node -> node.getData().put("skuId", PRODUCT));

        BusinessException error = assertThrows(BusinessException.class, () ->
                service.resolvePinnedGraph(FACTORY, recipe(snapshot(definition))));
        assertEquals("BOM_WORKFLOW_TARGET_TERMINAL_INVALID", error.getErrorCode());
    }

    @Test
    void rejectsCrossFactoryAndCrossSkuWithoutFallback() throws Exception {
        BomRecipe crossFactory = recipe(snapshot(oneToOne()));
        crossFactory.setFactoryId("F007");
        BusinessException factoryError = assertThrows(BusinessException.class,
                () -> service.resolvePinnedGraph(FACTORY, crossFactory));
        assertEquals("BOM_WORKFLOW_RECIPE_NOT_FOUND", factoryError.getErrorCode());

        ProductProcessWorkflowDTO crossSku = oneToOne();
        crossSku.getNodes().stream().filter(node -> "finished".equals(node.getId()))
                .forEach(node -> node.getData().put("skuId", "FG-OTHER"));
        BusinessException skuError = assertThrows(BusinessException.class,
                () -> service.resolvePinnedGraph(FACTORY, recipe(snapshot(crossSku))));
        assertEquals("BOM_WORKFLOW_TARGET_TERMINAL_INVALID", skuError.getErrorCode());
    }

    @Test
    void rejectsNoRootCycleAndOrphanGraphsFailClosed() throws Exception {
        ProductProcessWorkflowDTO noRoot = oneToOne();
        noRoot.getNodes().removeIf(node -> "raw-a".equals(node.getId()));
        noRoot.getEdges().removeIf(edge -> "raw-a".equals(edge.getSource()));
        assertThrows(BusinessException.class,
                () -> service.resolvePinnedGraph(FACTORY, recipe(snapshot(noRoot))));

        ProductProcessWorkflowDTO cycle = oneToOne();
        cycle.getEdges().add(edge("cycle", "finished", "output", "process-1", "in-a"));
        assertThrows(BusinessException.class,
                () -> service.resolvePinnedGraph(FACTORY, recipe(snapshot(cycle))));

        ProductProcessWorkflowDTO orphan = oneToOne();
        orphan.getNodes().add(material("orphan", "RAW_MATERIAL", "RM-ORPHAN", 900));
        assertThrows(BusinessException.class,
                () -> service.resolvePinnedGraph(FACTORY, recipe(snapshot(orphan))));
    }

    private ProductProcessWorkflowDTO oneToOne() {
        List<ProductProcessWorkflowDTO.Node> nodes = new ArrayList<>(List.of(
                material("raw-a", "RAW_MATERIAL", "RM-A", 0),
                process("process-1", "WP-1", List.of(
                        port("in-a", "INPUT", "raw-a", "RAW_MATERIAL", 0, null, null),
                        port("out", "OUTPUT", "finished", "FINISHED_GOOD", 0, "MAIN", "100")), 100),
                material("finished", "FINISHED_GOOD", PRODUCT, 200)));
        return definition(nodes, List.of(
                edge("e1", "raw-a", "output", "process-1", "in-a"),
                edge("e2", "process-1", "out", "finished", "input")));
    }

    private ProductProcessWorkflowDTO twoToOne() {
        List<ProductProcessWorkflowDTO.Node> nodes = new ArrayList<>(List.of(
                material("raw-a", "RAW_MATERIAL", "RM-A", 0),
                material("raw-b", "RAW_MATERIAL", "RM-B", 0),
                process("blend", "WP-BLEND", List.of(
                        port("in-a", "INPUT", "raw-a", "RAW_MATERIAL", 0, null, null),
                        port("in-b", "INPUT", "raw-b", "RAW_MATERIAL", 1, null, null),
                        port("out", "OUTPUT", "finished", "FINISHED_GOOD", 0, "MAIN", "100")), 100),
                material("finished", "FINISHED_GOOD", PRODUCT, 200)));
        return definition(nodes, List.of(
                edge("e1", "raw-a", "output", "blend", "in-a"),
                edge("e2", "raw-b", "output", "blend", "in-b"),
                edge("e3", "blend", "out", "finished", "input")));
    }

    private ProductProcessWorkflowDTO oneToTwo(boolean roles) {
        List<Map<String, Object>> ports = List.of(
                port("in", "INPUT", "raw-a", "RAW_MATERIAL", 0, null, null),
                port("out-main", "OUTPUT", "finished", "FINISHED_GOOD", 0,
                        roles ? "MAIN" : null, roles ? "100" : null),
                port("out-by", "OUTPUT", "byproduct", "FINISHED_GOOD", 1,
                        roles ? "BY_PRODUCT" : null, roles ? "0" : null));
        return definition(new ArrayList<>(List.of(
                material("raw-a", "RAW_MATERIAL", "RM-A", 0),
                process("split", "WP-SPLIT", ports, 100),
                material("finished", "FINISHED_GOOD", PRODUCT, 200),
                material("byproduct", "FINISHED_GOOD", "FG-BY", 220))), List.of(
                edge("e1", "raw-a", "output", "split", "in"),
                edge("e2", "split", "out-main", "finished", "input"),
                edge("e3", "split", "out-by", "byproduct", "input")));
    }

    private ProductProcessWorkflowDTO twoToTwo() {
        return definition(new ArrayList<>(List.of(
                material("raw-a", "RAW_MATERIAL", "RM-A", 0),
                material("raw-b", "RAW_MATERIAL", "RM-B", 0),
                process("blend", "WP-BLEND", List.of(
                        port("in-a", "INPUT", "raw-a", "RAW_MATERIAL", 0, null, null),
                        port("in-b", "INPUT", "raw-b", "RAW_MATERIAL", 1, null, null),
                        port("out-semi", "OUTPUT", "semi", "SEMI_FINISHED", 0, "MAIN", "100")), 100),
                material("semi", "SEMI_FINISHED", "SEMI-1", 200),
                process("split", "WP-SPLIT", List.of(
                        port("in-semi", "INPUT", "semi", "SEMI_FINISHED", 0, null, null),
                        port("out-main", "OUTPUT", "finished", "FINISHED_GOOD", 0, "MAIN", "75"),
                        port("out-by", "OUTPUT", "byproduct", "FINISHED_GOOD", 1, "CO_PRODUCT", "25")), 300),
                material("finished", "FINISHED_GOOD", PRODUCT, 400),
                material("byproduct", "FINISHED_GOOD", "FG-BY", 420))), List.of(
                edge("e1", "raw-a", "output", "blend", "in-a"),
                edge("e2", "raw-b", "output", "blend", "in-b"),
                edge("e3", "blend", "out-semi", "semi", "input"),
                edge("e4", "semi", "output", "split", "in-semi"),
                edge("e5", "split", "out-main", "finished", "input"),
                edge("e6", "split", "out-by", "byproduct", "input")));
    }

    private ProductProcessWorkflowDTO definition(List<ProductProcessWorkflowDTO.Node> nodes,
                                                  List<ProductProcessWorkflowDTO.Edge> edges) {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setId(41L);
        dto.setFactoryId(FACTORY);
        dto.setProductTypeId(PRODUCT);
        dto.setSchemaVersion(1);
        dto.setVersion(2);
        dto.setNodes(nodes);
        dto.setEdges(new ArrayList<>(edges));
        dto.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));
        return dto;
    }

    private ProductProcessWorkflowDTO.Node material(String id, String kind, String skuId, double x) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", id);
        data.put("skuId", skuId);
        data.put("skuCode", skuId);
        return new ProductProcessWorkflowDTO.Node(id, kind,
                new ProductProcessWorkflowDTO.Position(x, 0D), data);
    }

    private ProductProcessWorkflowDTO.Node process(String id, String workProcessId,
                                                   List<Map<String, Object>> ports, double x) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", workProcessId);
        data.put("processName", id);
        data.put("inputUnit", "kg");
        data.put("outputUnit", "kg");
        data.put("ports", new ArrayList<>(ports));
        data.put("conversionRule", Map.of("mode", "ACTUAL_WEIGHT"));
        return new ProductProcessWorkflowDTO.Node(id, "PROCESS",
                new ProductProcessWorkflowDTO.Position(x, 0D), data);
    }

    private Map<String, Object> port(String id, String direction, String materialNodeId,
                                     String materialKind, int ordinal, String role, String ratio) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("id", id);
        port.put("direction", direction);
        port.put("materialNodeId", materialNodeId);
        port.put("materialKind", materialKind);
        port.put("unit", "kg");
        port.put("ordinal", ordinal);
        if (role != null) port.put("outputRole", role);
        if (ratio != null) port.put("costAllocationRatio", new BigDecimal(ratio));
        return port;
    }

    private ProductProcessWorkflowDTO.Edge edge(String id, String source, String sourceHandle,
                                                 String target, String targetHandle) {
        return new ProductProcessWorkflowDTO.Edge(id, source, sourceHandle, target, targetHandle);
    }

    private ProductProcessWorkflowRevision revision(ProductProcessWorkflowDTO definition) throws Exception {
        ProductProcessWorkflowRevision revision = new ProductProcessWorkflowRevision();
        revision.setId(71L);
        revision.setFactoryId(FACTORY);
        revision.setProductTypeId(PRODUCT);
        revision.setWorkflowId(41L);
        revision.setDefinitionVersion(2);
        revision.setRevisionNumber(3);
        revision.setSchemaVersion(1);
        revision.setStatus(ProductProcessWorkflowRevision.Status.DRAFT);
        revision.setNodesJson(objectMapper.writeValueAsString(definition.getNodes()));
        revision.setEdgesJson(objectMapper.writeValueAsString(definition.getEdges()));
        revision.setViewportJson(objectMapper.writeValueAsString(definition.getViewport()));
        revision.setRevisionHash(snapshotService.hash(revision));
        revision.setProcessCount((int) definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind())).count());
        revision.setStructurallyComplete(true);
        return revision;
    }

    private Snapshot snapshot(ProductProcessWorkflowDTO definition) throws Exception {
        return new Snapshot(objectMapper.writeValueAsString(definition.getNodes()),
                objectMapper.writeValueAsString(definition.getEdges()));
    }

    private BomRecipe recipe(Snapshot snapshot) {
        BomRecipe recipe = BomRecipe.builder().id("BOM-1").factoryId(FACTORY).recipeCode("BOM-1")
                .productTypeId(PRODUCT).productName("成品").outputQuantityPerUnit(BigDecimal.ONE)
                .status(BomRecipe.Status.DRAFT).build();
        if (snapshot != null) {
            recipe.setWorkflowRevisionId(71L);
            recipe.setWorkflowId(41L);
            recipe.setWorkflowDefinitionVersion(2);
            recipe.setWorkflowRevisionHash("snapshot-hash");
            recipe.setWorkflowSchemaVersion(1);
            recipe.setWorkflowNodesSnapshotJson(snapshot.nodes());
            recipe.setWorkflowEdgesSnapshotJson(snapshot.edges());
        }
        return recipe;
    }

    private record Snapshot(String nodes, String edges) { }
}
