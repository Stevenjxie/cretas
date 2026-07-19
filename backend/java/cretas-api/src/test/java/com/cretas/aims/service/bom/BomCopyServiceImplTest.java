package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.BomCopyCandidateDTO;
import com.cretas.aims.dto.bom.BomCopyToDraftRequest;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomProcessInjectionConfig;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomProcessInjectionConfigRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.bom.impl.BomCopyServiceImpl;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.WorkflowProcessPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomCopyServiceImplTest {

    private static final String FACTORY = "F006";
    private static final String TARGET = "target";
    private static final String SOURCE = "source";
    private static final String SOURCE_RECIPE = "source-recipe";

    @Mock BomRecipeRepository recipeRepo;
    @Mock BomRecipeItemRepository itemRepo;
    @Mock BomSeasoningItemRepository seasoningRepo;
    @Mock BomProcessInjectionConfigRepository processInjectionConfigRepo;
    @Mock ProductTypeRepository productTypeRepo;
    @Mock WorkProcessRepository workProcessRepo;
    @Mock ProductWorkflowResolutionService workflowResolutionService;
    @InjectMocks BomCopyServiceImpl service;

    private ProductType target;
    private BomRecipe source;

    @BeforeEach
    void setUp() {
        target = product(TARGET, FACTORY, "干式熟成鸡 400g", "袋", "400");
        source = activeRecipe(SOURCE_RECIPE, SOURCE, FACTORY);
        when(productTypeRepo.findByIdAndFactoryIdForUpdate(TARGET, FACTORY))
                .thenReturn(Optional.of(target));
    }

    @Test
    void candidatesRequireSameRawRootAndSharedWorkProcessAndHideIncompatibleRules() {
        BomRecipe valid = source;
        BomRecipe differentRaw = activeRecipe("recipe-other-raw", "other-raw", FACTORY);
        BomRecipe noShared = activeRecipe("recipe-no-shared", "no-shared", FACTORY);
        ProductType validProduct = product(SOURCE, FACTORY, "干式熟成鸡 350g", "袋", "350");
        ProductType otherRawProduct = product("other-raw", FACTORY, "其他原料产品", "袋", "300");
        ProductType noSharedProduct = product("no-shared", FACTORY, "同源无共序产品", "袋", "300");
        when(productTypeRepo.findByIdAndFactoryId(TARGET, FACTORY)).thenReturn(Optional.of(target));
        when(recipeRepo.findByFactoryIdAndStatus(eq(FACTORY), eq(BomRecipe.Status.ACTIVE), any()))
                .thenReturn(new PageImpl<>(List.of(valid, differentRaw, noShared)));
        when(productTypeRepo.findByIdIn(any())).thenReturn(List.of(validProduct, otherRawProduct, noSharedProduct));
        when(workflowResolutionService.resolveProcessPath(FACTORY, TARGET))
                .thenReturn(Optional.of(path(TARGET, "raw-chicken", "p1", "p2")));
        when(workflowResolutionService.resolveProcessPath(FACTORY, SOURCE))
                .thenReturn(Optional.of(path(SOURCE, "raw-chicken", "p1", "p3")));
        when(workflowResolutionService.resolveProcessPath(FACTORY, "other-raw"))
                .thenReturn(Optional.of(path("other-raw", "raw-pork", "p1")));
        when(workflowResolutionService.resolveProcessPath(FACTORY, "no-shared"))
                .thenReturn(Optional.of(path("no-shared", "raw-chicken", "p9")));
        WorkProcess p1 = new WorkProcess();
        p1.setId("p1");
        p1.setProcessName("熟制");
        when(workProcessRepo.findByFactoryIdAndIdIn(eq(FACTORY), anyList())).thenReturn(List.of(p1));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc(SOURCE_RECIPE)).thenReturn(List.of(item(1L, "mat-1")));
        when(seasoningRepo.findByRecipeIdOrderBySeqAsc(SOURCE_RECIPE)).thenReturn(List.of(
                seasoning(11L, "p1"), seasoning(12L, "p3"), seasoning(13L, null)));
        when(processInjectionConfigRepo.findByRecipeIdAndDeletedAtIsNull(SOURCE_RECIPE)).thenReturn(List.of(
                injectionConfig(21L, "p1"), injectionConfig(22L, "p3")));

        List<BomCopyCandidateDTO> candidates = service.listCandidates(FACTORY, TARGET);

        assertThat(candidates).hasSize(1);
        BomCopyCandidateDTO candidate = candidates.get(0);
        assertThat(candidate.getSourceProductTypeId()).isEqualTo(SOURCE);
        assertThat(candidate.getSharedProcesses()).extracting(BomCopyCandidateDTO.SharedProcessDTO::getWorkProcessId)
                .containsExactly("p1");
        assertThat(candidate.getSeasoningItems()).extracting(BomCopyCandidateDTO.SeasoningRuleDTO::getId)
                .containsExactly(11L);
        assertThat(candidate.getProcessInjectionConfigs())
                .extracting(BomCopyCandidateDTO.ProcessInjectionConfigRuleDTO::getId).containsExactly(21L);
    }

    @Test
    void copyCreatesEditableDraftFromOnlySelectedRulesUsingTargetSkuFieldsAndPreservingSubProduct() {
        prepareCopyBase();
        BomRecipeItem selected = item(1L, "mat-selected");
        selected.setSubProductTypeId("semi-product-1");
        selected.setStandardQuantity(new BigDecimal("2.5000"));
        BomRecipeItem unselected = item(2L, "mat-unselected");
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc(SOURCE_RECIPE)).thenReturn(List.of(selected, unselected));
        when(seasoningRepo.findByRecipeIdOrderBySeqAsc(SOURCE_RECIPE))
                .thenReturn(List.of(seasoning(11L, "p1"), seasoning(12L, "p1")));
        when(processInjectionConfigRepo.findByRecipeIdAndDeletedAtIsNull(SOURCE_RECIPE))
                .thenReturn(List.of(injectionConfig(21L, "p1"), injectionConfig(22L, "p1")));
        when(recipeRepo.findMaxVersion(FACTORY, TARGET)).thenReturn(null);
        when(recipeRepo.countByRecipeCodePrefix(eq(FACTORY), any())).thenReturn(0L);
        when(recipeRepo.save(any(BomRecipe.class))).thenAnswer(invocation -> {
            BomRecipe recipe = invocation.getArgument(0);
            if (recipe.getId() == null) recipe.setId("new-draft");
            return recipe;
        });
        BomCopyToDraftRequest request = request(List.of(1L), List.of(11L), List.of(21L));

        BomRecipe result = service.copySelectedRulesToDraft(FACTORY, request);

        assertThat(result.getStatus()).isEqualTo(BomRecipe.Status.DRAFT);
        assertThat(result.getIsCurrent()).isFalse();
        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getProductTypeId()).isEqualTo(TARGET);
        assertThat(result.getProductName()).isEqualTo("干式熟成鸡 400g");
        assertThat(result.getOutputUnit()).isEqualTo("袋");
        assertThat(result.getOutputQuantityPerUnit()).isEqualByComparingTo("400");
        assertThat(result.getNotes()).contains("source-product", "source-recipe", "请核对数量");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BomRecipeItem>> itemCaptor = ArgumentCaptor.forClass(List.class);
        verify(itemRepo).saveAll(itemCaptor.capture());
        assertThat(itemCaptor.getValue()).hasSize(1);
        assertThat(itemCaptor.getValue().get(0).getMaterialTypeId()).isEqualTo("mat-selected");
        assertThat(itemCaptor.getValue().get(0).getSubProductTypeId()).isEqualTo("semi-product-1");
        assertThat(itemCaptor.getValue().get(0).getStandardQuantity()).isEqualByComparingTo("2.5000");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BomSeasoningItem>> seasoningCaptor = ArgumentCaptor.forClass(List.class);
        verify(seasoningRepo).saveAll(seasoningCaptor.capture());
        assertThat(seasoningCaptor.getValue()).extracting(BomSeasoningItem::getId).containsOnlyNulls();
        assertThat(seasoningCaptor.getValue()).extracting(BomSeasoningItem::getMaterialTypeId)
                .containsExactly("seasoning-11");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BomProcessInjectionConfig>> configCaptor = ArgumentCaptor.forClass(List.class);
        verify(processInjectionConfigRepo).saveAll(configCaptor.capture());
        assertThat(configCaptor.getValue()).hasSize(1);
        assertThat(configCaptor.getValue().get(0).getInjectionAmountKg()).isEqualByComparingTo("1.250");
    }

    @Test
    void copyRejectsCrossFactorySource() {
        when(productTypeRepo.findByIdAndFactoryId(TARGET, FACTORY)).thenReturn(Optional.of(target));
        source.setFactoryId("OTHER");
        when(recipeRepo.findById(SOURCE_RECIPE)).thenReturn(Optional.of(source));

        assertBusinessCode(() -> service.copySelectedRulesToDraft(FACTORY, request(List.of(1L), List.of(), List.of())),
                "BOM_COPY_CROSS_FACTORY");
    }

    @Test
    void copyRejectsNonCurrentSource() {
        when(productTypeRepo.findByIdAndFactoryId(TARGET, FACTORY)).thenReturn(Optional.of(target));
        source.setIsCurrent(false);
        when(recipeRepo.findById(SOURCE_RECIPE)).thenReturn(Optional.of(source));

        assertBusinessCode(() -> service.copySelectedRulesToDraft(FACTORY, request(List.of(1L), List.of(), List.of())),
                "BOM_COPY_SOURCE_NOT_CURRENT");
    }

    @Test
    void copyRejectsDuplicateSelectionIds() {
        prepareCopyBase();

        assertBusinessCode(() -> service.copySelectedRulesToDraft(
                FACTORY, request(List.of(1L, 1L), List.of(), List.of())), "BOM_COPY_INVALID_SELECTION");
        verify(itemRepo, never()).saveAll(anyList());
    }

    @Test
    void copyRejectsForeignRuleId() {
        prepareCopyBase();
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc(SOURCE_RECIPE)).thenReturn(List.of(item(1L, "mat-1")));
        when(seasoningRepo.findByRecipeIdOrderBySeqAsc(SOURCE_RECIPE)).thenReturn(List.of());
        when(processInjectionConfigRepo.findByRecipeIdAndDeletedAtIsNull(SOURCE_RECIPE)).thenReturn(List.of());

        assertBusinessCode(() -> service.copySelectedRulesToDraft(
                FACTORY, request(List.of(999L), List.of(), List.of())), "BOM_COPY_FOREIGN_RULE_ID");
        verify(itemRepo, never()).saveAll(anyList());
    }

    @Test
    void copyRejectsUnboundHistoricalSeasoningRule() {
        prepareCopyBase();
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc(SOURCE_RECIPE)).thenReturn(List.of());
        when(seasoningRepo.findByRecipeIdOrderBySeqAsc(SOURCE_RECIPE))
                .thenReturn(List.of(seasoning(11L, null)));
        when(processInjectionConfigRepo.findByRecipeIdAndDeletedAtIsNull(SOURCE_RECIPE)).thenReturn(List.of());

        assertBusinessCode(() -> service.copySelectedRulesToDraft(
                FACTORY, request(List.of(), List.of(11L), List.of())), "BOM_COPY_INCOMPATIBLE_RULE");
        verify(seasoningRepo, never()).saveAll(anyList());
    }

    @Test
    void copyRejectsWhenTargetAlreadyHasDraft() {
        when(productTypeRepo.findByIdAndFactoryId(TARGET, FACTORY)).thenReturn(Optional.of(target));
        when(recipeRepo.findById(SOURCE_RECIPE)).thenReturn(Optional.of(source));
        BomRecipe draft = new BomRecipe();
        draft.setStatus(BomRecipe.Status.DRAFT);
        when(recipeRepo.findByFactoryIdAndProductTypeIdOrderByVersionDesc(FACTORY, TARGET))
                .thenReturn(List.of(draft));

        assertBusinessCode(() -> service.copySelectedRulesToDraft(
                FACTORY, request(List.of(1L), List.of(), List.of())), "BOM_TARGET_DRAFT_EXISTS");
        verify(workflowResolutionService, never()).resolveProcessPath(any(), any());
    }

    private void prepareCopyBase() {
        when(productTypeRepo.findByIdAndFactoryId(TARGET, FACTORY)).thenReturn(Optional.of(target));
        when(recipeRepo.findById(SOURCE_RECIPE)).thenReturn(Optional.of(source));
        when(recipeRepo.findByFactoryIdAndProductTypeIdOrderByVersionDesc(FACTORY, TARGET))
                .thenReturn(List.of());
        when(workflowResolutionService.resolveProcessPath(FACTORY, TARGET))
                .thenReturn(Optional.of(path(TARGET, "raw-chicken", "p1", "p2")));
        when(workflowResolutionService.resolveProcessPath(FACTORY, SOURCE))
                .thenReturn(Optional.of(path(SOURCE, "raw-chicken", "p1", "p3")));
    }

    private BomCopyToDraftRequest request(List<Long> items, List<Long> seasonings, List<Long> params) {
        BomCopyToDraftRequest request = new BomCopyToDraftRequest();
        request.setTargetProductTypeId(TARGET);
        request.setSourceRecipeId(SOURCE_RECIPE);
        request.setRecipeItemIds(items);
        request.setSeasoningItemIds(seasonings);
        request.setProcessInjectionConfigIds(params);
        return request;
    }

    private void assertBusinessCode(Runnable action, String errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(errorCode));
    }

    private ProductType product(String id, String factory, String name, String unit, String grams) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId(factory);
        product.setName(name);
        product.setUnit(unit);
        product.setGramsPerUnit(new BigDecimal(grams));
        product.setIsActive(true);
        return product;
    }

    private BomRecipe activeRecipe(String id, String productId, String factory) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(id);
        recipe.setFactoryId(factory);
        recipe.setProductTypeId(productId);
        recipe.setProductName("source-product");
        recipe.setRecipeCode(id);
        recipe.setVersion(2);
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setIsCurrent(true);
        return recipe;
    }

    private WorkflowProcessPath path(String terminal, String rawRoot, String... processes) {
        List<WorkflowProcessPath.ProcessStep> steps = new ArrayList<>();
        for (int i = 0; i < processes.length; i++) {
            steps.add(new WorkflowProcessPath.ProcessStep("node-" + processes[i], processes[i], i + 1));
        }
        return new WorkflowProcessPath(1L, 1, rawRoot, "RAW_MATERIAL_TYPE", terminal, rawRoot, steps);
    }

    private BomRecipeItem item(Long id, String materialId) {
        BomRecipeItem item = new BomRecipeItem();
        item.setId(id);
        item.setMaterialTypeId(materialId);
        item.setMaterialName(materialId);
        item.setStandardQuantity(BigDecimal.ONE);
        item.setYieldRate(new BigDecimal("100.00"));
        item.setUnit("kg");
        item.setMaterialCategory("RAW");
        item.setSortOrder(id.intValue());
        item.setIsOptional(false);
        item.setPerPortion(false);
        return item;
    }

    private BomSeasoningItem seasoning(Long id, String processId) {
        BomSeasoningItem item = new BomSeasoningItem();
        item.setId(id);
        item.setMaterialTypeId("seasoning-" + id);
        item.setName("seasoning-" + id);
        item.setSection("COOKING");
        item.setSeq(id.intValue());
        item.setDosagePerKgG(new BigDecimal("3.5000"));
        item.setCountInSeasoning(true);
        item.setWorkProcessId(processId);
        return item;
    }

    private BomProcessInjectionConfig injectionConfig(Long id, String processId) {
        BomProcessInjectionConfig config = new BomProcessInjectionConfig();
        config.setId(id);
        config.setWorkProcessId(processId);
        config.setInjectionAmountKg(new BigDecimal("1.250"));
        return config;
    }
}
