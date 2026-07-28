package com.cretas.aims.service.bom;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.workflow.WorkflowBomSyncPreflightResponse;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow/BOM synchronization preflight")
class WorkflowBomSynchronizationServiceTest {

    private static final String FACTORY = "F006";
    private static final String OWNER = "RAW-OWNER";
    private static final String MAIN_PRODUCT = "P-MAIN";
    private static final String CO_PRODUCT = "P-CO";
    private static final String MATERIAL = "MAT-SHARED";

    @Mock
    private BomRecipeRepository recipeRepository;
    @Mock
    private BomRecipeItemRepository itemRepository;
    @Mock
    private BomWorkflowRevisionService revisionService;
    @Mock
    private BomRecipeService recipeService;

    private WorkflowBomSynchronizationService service;
    private BomRecipe main;
    private BomRecipe coProduct;
    private ProductProcessWorkflowRevision target;
    private List<BomWorkflowRevisionService.TerminalOutput> outputs;

    @BeforeEach
    void setUp() {
        service = new WorkflowBomSynchronizationService(
                recipeRepository, itemRepository, revisionService, recipeService);
        main = recipe("R-MAIN", MAIN_PRODUCT, "terminal:old-main", BomRecipe.OutputRole.MAIN);
        coProduct = recipe("R-CO", CO_PRODUCT, "terminal:old-co", BomRecipe.OutputRole.CO_PRODUCT);
        target = new ProductProcessWorkflowRevision();
        target.setId(22L);
        target.setFactoryId(FACTORY);
        target.setProductTypeId(OWNER);
        target.setWorkflowId(10L);
        target.setRevisionHash("revision-22");
        target.setDefinitionVersion(2);
        outputs = List.of(
                output("terminal:new-main", MAIN_PRODUCT, BomRecipe.OutputRole.MAIN, "60"),
                output("terminal:new-co", CO_PRODUCT, BomRecipe.OutputRole.CO_PRODUCT, "40"));
        when(revisionService.resolveMainOutputProductTypeId(FACTORY, target))
                .thenReturn(MAIN_PRODUCT);
        when(recipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, MAIN_PRODUCT, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(main));
    }

    @Test
    @DisplayName("exact revision is not READY when the complete active family gate fails")
    void exactRevisionRequiresCompleteActiveFamily() {
        main.setWorkflowRevisionId(target.getId());
        main.setWorkflowRevisionHash(target.getRevisionHash());
        BusinessException incomplete = new BusinessException(409, "co-product BOM is missing")
                .withCode("WORKFLOW_ACTIVE_BOM_FAMILY_INCOMPLETE")
                .withHint("complete the BOM family");
        when(revisionService.requireActiveBomPinsRevision(FACTORY, OWNER, target))
                .thenThrow(incomplete);

        WorkflowBomSyncPreflightResponse result =
                service.preflight(FACTORY, OWNER, target);

        assertThat(result.getClassification())
                .isEqualTo(WorkflowBomSyncPreflightResponse.Classification.CONFLICT);
        assertThat(result.isCanCompleteAutomatically()).isFalse();
        assertThat(result.getConflicts()).extracting(
                        WorkflowBomSyncPreflightResponse.SyncIssue::getCode)
                .containsExactly("WORKFLOW_ACTIVE_BOM_FAMILY_INCOMPLETE");
    }

    @Test
    @DisplayName("same material in different terminal output scopes is not a false ambiguity")
    void sameMaterialInDifferentOutputScopesMapsIndependently() {
        stubMultiOutputFamily(
                List.of(item(1L, main.getId()), item(2L, coProduct.getId())));

        WorkflowBomSyncPreflightResponse result =
                service.preflight(FACTORY, OWNER, target);

        assertThat(result.getClassification())
                .isEqualTo(WorkflowBomSyncPreflightResponse.Classification.AUTO_MIGRATABLE);
        assertThat(result.isCanCompleteAutomatically()).isTrue();
        assertThat(result.getConflicts()).isEmpty();
        assertThat(result.getAutomaticMappings()).hasSize(2);
        assertThat(result.getAutomaticMappings())
                .extracting(WorkflowBomSyncPreflightResponse.AutomaticMapping::getCostScope)
                .containsOnly("OUTPUT_EXCLUSIVE");
        assertThat(result.getAutomaticMappings())
                .extracting(WorkflowBomSyncPreflightResponse.AutomaticMapping::getCostScopeKey)
                .containsExactlyInAnyOrder("terminal:new-main", "terminal:new-co");
    }

    @Test
    @DisplayName("missing co-product scoped input is reported by preflight before synchronization")
    void missingCoProductInputIsBlockedBeforeActualSynchronization() {
        stubMultiOutputFamily(List.of(item(1L, main.getId())));

        WorkflowBomSyncPreflightResponse result =
                service.preflight(FACTORY, OWNER, target);

        assertThat(result.getClassification())
                .isEqualTo(WorkflowBomSyncPreflightResponse.Classification.USER_INPUT_REQUIRED);
        assertThat(result.isCanCompleteAutomatically()).isFalse();
        assertThat(result.getMissingItems()).extracting(
                        WorkflowBomSyncPreflightResponse.SyncIssue::getCode)
                .contains("BOM_WORKFLOW_INPUT_ITEM_MISSING");
        verifyNoInteractions(recipeService);
    }

    @Test
    @DisplayName("removed Workflow output conflicts with the extra active BOM family member")
    void removedOutputIsBlockedBeforeActualSynchronization() {
        outputs = List.of(output(
                "terminal:new-main", MAIN_PRODUCT, BomRecipe.OutputRole.MAIN, "100"));
        stubMultiOutputFamily(List.of(item(1L, main.getId())));

        WorkflowBomSyncPreflightResponse result =
                service.preflight(FACTORY, OWNER, target);

        assertThat(result.getClassification())
                .isEqualTo(WorkflowBomSyncPreflightResponse.Classification.CONFLICT);
        assertThat(result.isCanCompleteAutomatically()).isFalse();
        assertThat(result.getConflicts())
                .extracting(
                        WorkflowBomSyncPreflightResponse.SyncIssue::getCode,
                        WorkflowBomSyncPreflightResponse.SyncIssue::getMaterialName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "BOM_FAMILY_OUTPUT_REMOVED", CO_PRODUCT));
        verifyNoInteractions(recipeService);
    }

    private void stubMultiOutputFamily(List<BomRecipeItem> items) {
        when(recipeRepository.findByFactoryIdAndBomFamilyIdAndStatusOrderByProductTypeIdAsc(
                FACTORY, "FAMILY-1", BomRecipe.Status.ACTIVE))
                .thenReturn(List.of(main, coProduct));
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc(main.getId()))
                .thenReturn(items.stream()
                        .filter(item -> main.getId().equals(item.getRecipeId())).toList());
        when(itemRepository.findByRecipeIdOrderBySortOrderAsc(coProduct.getId()))
                .thenReturn(items.stream()
                        .filter(item -> coProduct.getId().equals(item.getRecipeId())).toList());
        when(revisionService.resolveTerminalOutputsForRevision(FACTORY, target))
                .thenReturn(outputs);
        when(revisionService.resolveExactBinding(FACTORY, main, target.getId()))
                .thenReturn(binding(main, "main"));
        if (outputs.stream().anyMatch(
                output -> CO_PRODUCT.equals(output.productTypeId()))) {
            when(revisionService.resolveExactBinding(FACTORY, coProduct, target.getId()))
                    .thenReturn(binding(coProduct, "co"));
        }
    }

    private BomRecipe recipe(
            String id,
            String productTypeId,
            String terminalNodeId,
            BomRecipe.OutputRole role) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(id);
        recipe.setFactoryId(FACTORY);
        recipe.setProductTypeId(productTypeId);
        recipe.setBomFamilyId("FAMILY-1");
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setIsCurrent(true);
        recipe.setWorkflowId(10L);
        recipe.setWorkflowRevisionId(11L);
        recipe.setWorkflowRevisionHash("revision-11");
        recipe.setTargetTerminalNodeId(terminalNodeId);
        recipe.setOutputRole(role);
        recipe.setVersion(1);
        return recipe;
    }

    private BomRecipeItem item(Long id, String recipeId) {
        BomRecipeItem item = new BomRecipeItem();
        item.setId(id);
        item.setRecipeId(recipeId);
        item.setFactoryId(FACTORY);
        item.setMaterialTypeId(MATERIAL);
        item.setMaterialName(MATERIAL);
        item.setMaterialCategory("RAW");
        item.setUnit("kg");
        return item;
    }

    private BomWorkflowRevisionService.TerminalOutput output(
            String terminalNodeId,
            String productTypeId,
            BomRecipe.OutputRole role,
            String ratio) {
        return new BomWorkflowRevisionService.TerminalOutput(
                terminalNodeId,
                productTypeId,
                "process:" + productTypeId,
                "output",
                role,
                new BigDecimal(ratio),
                "kg");
    }

    private BomWorkflowRevisionService.WorkflowBinding binding(
            BomRecipe recipe,
            String suffix) {
        String materialNodeId = "material:" + suffix;
        String processNodeId = "process:" + suffix;
        String portId = "input:" + suffix;
        Map<String, Object> materialData = new LinkedHashMap<>();
        materialData.put("skuId", MATERIAL);
        ProductProcessWorkflowDTO.Node material = new ProductProcessWorkflowDTO.Node(
                materialNodeId,
                "RAW_MATERIAL",
                new ProductProcessWorkflowDTO.Position(0D, 0D),
                materialData);
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("ports", List.of(Map.of(
                "id", portId,
                "direction", "INPUT",
                "materialNodeId", materialNodeId,
                "materialKind", "RAW_MATERIAL",
                "unit", "kg",
                "ordinal", 0)));
        ProductProcessWorkflowDTO.Node process = new ProductProcessWorkflowDTO.Node(
                processNodeId,
                "PROCESS",
                new ProductProcessWorkflowDTO.Position(100D, 0D),
                processData);
        ProductProcessWorkflowDTO.Edge edge = new ProductProcessWorkflowDTO.Edge(
                "edge:" + suffix,
                materialNodeId,
                "output",
                processNodeId,
                portId);
        PinnedWorkflowGraph graph = new PinnedWorkflowGraph(
                target.getId(),
                target.getWorkflowId(),
                target.getDefinitionVersion(),
                target.getRevisionHash(),
                recipe.getProductTypeId(),
                "terminal:new-" + suffix,
                List.of(MATERIAL),
                List.of(new PinnedWorkflowGraph.ProcessStep(processNodeId, "WP-1", 1)),
                List.of(material, process),
                List.of(edge));
        return new BomWorkflowRevisionService.WorkflowBinding(
                target, null, graph, outputs, outputs.stream()
                .filter(output -> recipe.getProductTypeId().equals(output.productTypeId()))
                .findFirst().orElseThrow());
    }
}
