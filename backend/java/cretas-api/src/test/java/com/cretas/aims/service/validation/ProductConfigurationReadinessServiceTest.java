package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.workflow.ProductConfigurationCompletenessReport;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.product.ProductPackagingSpec;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.product.ProductPackagingSpecRepository;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductConfigurationReadinessServiceTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "FG-001";

    @Mock ProductProcessWorkflowRepository workflowRepository;
    @Mock ProductProcessWorkflowActivationRepository activationRepository;
    @Mock ProductProcessWorkflowValidator workflowValidator;
    @Mock ProductProcessWorkflowCatalogValidator catalogValidator;
    @Mock ProductProcessWorkflowUnitValidator unitValidator;
    @Mock BomWorkflowRevisionService bomWorkflowRevisionService;
    @Mock BomRecipeRepository recipeRepository;
    @Mock BomRecipeItemRepository itemRepository;
    @Mock BomSeasoningItemRepository seasoningRepository;
    @Mock ProductPackagingSpecRepository packagingSpecRepository;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock UnitContractService unitContractService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProductConfigurationReadinessService service;

    @BeforeEach
    void setUp() {
        service = new ProductConfigurationReadinessService(
                workflowRepository,
                activationRepository,
                workflowValidator,
                catalogValidator,
                unitValidator,
                bomWorkflowRevisionService,
                recipeRepository,
                itemRepository,
                seasoningRepository,
                packagingSpecRepository,
                productTypeRepository,
                unitContractService,
                objectMapper);
    }

    @Test
    void activePinnedBomWithoutMutableDraftUsesMultiEntryTargetGraphOnce() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.ACTIVE, true);
        ProductProcessWorkflowDTO.Node process = processNode("process-shared", "WP-SHARED", "REQUIRED");
        PinnedWorkflowGraph graph = graph(
                List.of("RAW-A", "RAW-B"),
                List.of(process),
                List.of(new PinnedWorkflowGraph.ProcessStep("process-shared", "WP-SHARED", 1)));
        stubCommon(recipe, List.of(binding("process-shared", "WP-SHARED")));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductConfigurationCompletenessReport report = service.evaluate(FACTORY, PRODUCT, null);

        assertTrue(report.isWorkflowDraftComplete());
        assertTrue(report.isBomComplete());
        assertTrue(report.isBomActive());
        assertEquals("BOM_ACTIVE", report.getStage());
        assertEquals(1, report.getProcessAuxiliaryStatuses().size());
        assertEquals("process-shared",
                report.getProcessAuxiliaryStatuses().getFirst().getWorkflowProcessNodeId());
        assertEquals(1, report.getProcessAuxiliaryStatuses().getFirst().getBindingCount());
        assertTrue(report.getIssues().stream()
                .noneMatch(issue -> "WORKFLOW_DRAFT_MISSING".equals(issue.getCode())));
        verifyNoInteractions(workflowRepository);
    }

    @Test
    void mainOutputReadinessIncludesItsOwnOutputExclusiveAuxiliary() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        recipe.setSharedRecipeId(recipe.getId());
        BomSeasoningItem exclusive = binding("process-package-main", "WP-PACK-MAIN");
        exclusive.setCostScope("OUTPUT_EXCLUSIVE");
        ProductProcessWorkflowDTO.Node process =
                processNode("process-package-main", "WP-PACK-MAIN", "REQUIRED");
        PinnedWorkflowGraph graph = graph(
                List.of("RAW-A"),
                List.of(process),
                List.of(new PinnedWorkflowGraph.ProcessStep(
                        "process-package-main", "WP-PACK-MAIN", 1)));
        stubCommon(recipe, List.of(exclusive));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductConfigurationCompletenessReport report =
                service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertTrue(report.isBomComplete());
        assertEquals(1, report.getProcessAuxiliaryStatuses().getFirst().getBindingCount());
        assertTrue(report.getProcessAuxiliaryStatuses().getFirst().isComplete());
    }

    @Test
    void pinnedRepeatedMasterProcessNodesMayOmitAuxiliaryBindings() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        ProductProcessWorkflowDTO.Node first = processNode("process-first", "WP-SAME", "REQUIRED");
        ProductProcessWorkflowDTO.Node second = processNode("process-second", "WP-SAME", "REQUIRED");
        PinnedWorkflowGraph graph = graph(
                List.of("RAW-A", "RAW-B"),
                List.of(first, second),
                List.of(
                        new PinnedWorkflowGraph.ProcessStep("process-first", "WP-SAME", 1),
                        new PinnedWorkflowGraph.ProcessStep("process-second", "WP-SAME", 2)));
        stubCommon(recipe, List.of(binding("process-first", "WP-SAME")));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductConfigurationCompletenessReport report = service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertTrue(report.isBomComplete());
        assertEquals(2, report.getProcessAuxiliaryStatuses().size());
        ProductConfigurationCompletenessReport.ProcessAuxiliaryStatus firstStatus =
                report.getProcessAuxiliaryStatuses().get(0);
        ProductConfigurationCompletenessReport.ProcessAuxiliaryStatus secondStatus =
                report.getProcessAuxiliaryStatuses().get(1);
        assertEquals("process-first", firstStatus.getWorkflowProcessNodeId());
        assertEquals(1, firstStatus.getBindingCount());
        assertTrue(firstStatus.isComplete());
        assertEquals("process-second", secondStatus.getWorkflowProcessNodeId());
        assertEquals(0, secondStatus.getBindingCount());
        assertTrue(secondStatus.isComplete());
        assertTrue(report.getIssues().stream().noneMatch(issue ->
                issue.getCode().startsWith("BOM_AUXILIARY")));
    }

    @Test
    void legacyUnpinnedRecipeStillMatchesSeasoningByMasterProcessId() throws Exception {
        BomRecipe recipe = recipe(BomRecipe.Status.DRAFT, false);
        ProductProcessWorkflowDTO.Node process = processNode("legacy-node", "WP-LEGACY", "REQUIRED");
        ProductProcessWorkflow draft = new ProductProcessWorkflow();
        draft.setId(41L);
        draft.setFactoryId(FACTORY);
        draft.setProductTypeId(PRODUCT);
        draft.setDefinitionVersion(2);
        draft.setSchemaVersion(1);
        draft.setStatus(ProductProcessWorkflow.Status.DRAFT);
        draft.setNodesJson(objectMapper.writeValueAsString(List.of(process)));
        draft.setEdgesJson("[]");
        draft.setViewportJson("{\"x\":0,\"y\":0,\"zoom\":1}");
        BomSeasoningItem legacy = binding(null, "WP-LEGACY");
        stubCommon(recipe, List.of(legacy));
        when(workflowRepository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                FACTORY, PRODUCT, ProductProcessWorkflow.Status.DRAFT)).thenReturn(Optional.of(draft));

        ProductConfigurationCompletenessReport report = service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertTrue(report.isBomComplete());
        assertEquals("legacy-node",
                report.getProcessAuxiliaryStatuses().getFirst().getWorkflowProcessNodeId());
        assertEquals(1, report.getProcessAuxiliaryStatuses().getFirst().getBindingCount());
        verify(bomWorkflowRevisionService, never()).resolvePinnedGraph(FACTORY, recipe);
    }

    @Test
    void activePinnedBomWithMissingAuxiliaryPolicyRemainsUsableWhenRawConfigured() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.ACTIVE, true);
        ProductProcessWorkflowDTO.Node process = processNode("legacy-process", "WP-LEGACY", null);
        PinnedWorkflowGraph graph = graph(
                List.of("RAW-A"),
                List.of(process),
                List.of(new PinnedWorkflowGraph.ProcessStep("legacy-process", "WP-LEGACY", 1)));
        stubCommon(recipe, List.of());
        when(recipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, PRODUCT, BomRecipe.Status.ACTIVE)).thenReturn(Optional.of(recipe));
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductConfigurationCompletenessReport activeReport =
                service.requireActiveBomComplete(FACTORY, PRODUCT);
        ProductConfigurationCompletenessReport report = service.evaluate(FACTORY, PRODUCT, recipe.getId());
        assertTrue(activeReport.isBomActive());
        assertTrue(report.isBomComplete());
        assertTrue(report.getProcessAuxiliaryStatuses().getFirst().isComplete());
        assertTrue(report.getIssues().stream().noneMatch(issue ->
                issue.getCode().startsWith("BOM_AUXILIARY")));
    }

    @Test
    void missingRawMaterialStillBlocksBomReadiness() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        PinnedWorkflowGraph graph = graph(List.of(), List.of(), List.of());
        stubCommon(recipe, List.of());
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId())).thenReturn(List.of());
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductConfigurationCompletenessReport report =
                service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertFalse(report.isBomComplete());
        assertTrue(report.getIssues().stream().anyMatch(issue ->
                "BOM_RAW_REQUIRED".equals(issue.getCode())));
    }

    @Test
    void packagingLevelsAreOptionalWhenRawMaterialIsConfigured() {
        BomRecipe recipe = pinnedRecipe(BomRecipe.Status.DRAFT, false);
        PinnedWorkflowGraph graph = graph(List.of("RAW-A"), List.of(), List.of());
        stubCommon(recipe, List.of());
        when(bomWorkflowRevisionService.resolvePinnedGraph(FACTORY, recipe)).thenReturn(graph);

        ProductType product = new ProductType();
        product.setId(PRODUCT);
        product.setFactoryId(FACTORY);
        product.setUnit("袋");
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT, FACTORY)).thenReturn(Optional.of(product));
        when(unitContractService.describe(FACTORY, "袋")).thenReturn(Optional.of(new CanonicalUnit(
                "bag", UnitDimension.PACKAGE, "bag", BigDecimal.ONE, "袋", 0)));

        ProductPackagingSpec outerCase = new ProductPackagingSpec();
        outerCase.setId("PACK-CASE");
        outerCase.setFactoryId(FACTORY);
        outerCase.setProductTypeId(PRODUCT);
        outerCase.setName("外箱");
        outerCase.setPackageUnit("箱");
        outerCase.setBaseUnit("袋");
        when(packagingSpecRepository
                .findByFactoryIdAndProductTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(FACTORY, PRODUCT))
                .thenReturn(List.of(outerCase));

        ProductConfigurationCompletenessReport report =
                service.evaluate(FACTORY, PRODUCT, recipe.getId());

        assertTrue(report.isBomComplete());
        assertEquals(2, report.getPackagingLevels().size());
        assertTrue(report.getPackagingLevels().stream()
                .allMatch(ProductConfigurationCompletenessReport.PackagingLevelStatus::isComplete));
        assertTrue(report.getIssues().stream().noneMatch(issue ->
                issue.getCode().startsWith("BOM_PACKAGING")
                        || "BOM_BASE_PACKAGING_REQUIRED".equals(issue.getCode())));
    }

    private void stubCommon(BomRecipe recipe, List<BomSeasoningItem> bindings) {
        when(recipeRepository.findByFactoryIdAndProductTypeIdOrderByVersionDesc(FACTORY, PRODUCT))
                .thenReturn(List.of(recipe));
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId()))
                .thenReturn(List.of(rawItem(recipe.getId())));
        when(seasoningRepository.findByRecipeIdOrderBySeqAsc(recipe.getId())).thenReturn(bindings);
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT, FACTORY)).thenReturn(Optional.empty());
        when(packagingSpecRepository
                .findByFactoryIdAndProductTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(FACTORY, PRODUCT))
                .thenReturn(List.of());
        when(activationRepository.findByFactoryIdAndProductTypeId(FACTORY, PRODUCT))
                .thenReturn(Optional.empty());
    }

    private BomRecipe pinnedRecipe(BomRecipe.Status status, boolean current) {
        BomRecipe recipe = recipe(status, current);
        recipe.setWorkflowRevisionId(71L);
        recipe.setWorkflowId(41L);
        recipe.setWorkflowDefinitionVersion(2);
        recipe.setWorkflowRevisionHash("revision-hash");
        recipe.setWorkflowSchemaVersion(1);
        recipe.setWorkflowNodesSnapshotJson("[]");
        recipe.setWorkflowEdgesSnapshotJson("[]");
        return recipe;
    }

    private BomRecipe recipe(BomRecipe.Status status, boolean current) {
        return BomRecipe.builder()
                .id("BOM-1")
                .factoryId(FACTORY)
                .recipeCode("BOM-1")
                .productTypeId(PRODUCT)
                .productName("Finished")
                .version(1)
                .outputQuantityPerUnit(BigDecimal.ONE)
                .status(status)
                .isCurrent(current)
                .build();
    }

    private BomRecipeItem rawItem(String recipeId) {
        return BomRecipeItem.builder()
                .recipeId(recipeId)
                .factoryId(FACTORY)
                .materialTypeId("RAW-A")
                .unit("kg")
                .materialCategory("RAW")
                .build();
    }

    private BomSeasoningItem binding(String processNodeId, String workProcessId) {
        BomSeasoningItem item = new BomSeasoningItem();
        item.setRecipeId("BOM-1");
        item.setFactoryId(FACTORY);
        item.setWorkflowProcessNodeId(processNodeId);
        item.setWorkProcessId(workProcessId);
        return item;
    }

    private PinnedWorkflowGraph graph(
            List<String> roots,
            List<ProductProcessWorkflowDTO.Node> nodes,
            List<PinnedWorkflowGraph.ProcessStep> processes) {
        return new PinnedWorkflowGraph(
                71L,
                41L,
                2,
                "revision-hash",
                PRODUCT,
                "finished",
                roots,
                processes,
                nodes,
                List.of());
    }

    private ProductProcessWorkflowDTO.Node processNode(
            String id,
            String workProcessId,
            String auxiliaryPolicy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", workProcessId);
        data.put("processName", id);
        data.put("auxiliaryPolicy", auxiliaryPolicy);
        data.put("ports", new ArrayList<>());
        return new ProductProcessWorkflowDTO.Node(
                id,
                "PROCESS",
                new ProductProcessWorkflowDTO.Position(0D, 0D),
                data);
    }
}
