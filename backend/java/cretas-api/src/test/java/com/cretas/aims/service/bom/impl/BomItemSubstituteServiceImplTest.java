package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomItemSubstituteDTO;
import com.cretas.aims.dto.bom.BomSubstituteInput;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomItemSubstitute;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomItemSubstituteRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Structured BOM substitute relation service")
class BomItemSubstituteServiceImplTest {

    private static final String FACTORY = "F006";
    private static final String RECIPE = "recipe-v2";

    @Mock BomItemSubstituteRepository repository;
    @Mock BomRecipeRepository recipeRepository;
    @Mock BomRecipeItemRepository recipeItemRepository;
    @Mock BomSeasoningItemRepository seasoningItemRepository;
    @Mock RawMaterialTypeRepository materialRepository;
    @Mock UnitContractService unitContractService;
    @Mock EntityManager entityManager;

    private BomItemSubstituteServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BomItemSubstituteServiceImpl(
                repository, recipeRepository, recipeItemRepository, seasoningItemRepository,
                materialRepository, unitContractService, entityManager);
        lenient().when(unitContractService.normalize(eq(FACTORY), anyString()))
                .thenAnswer(invocation -> normalized(invocation.getArgument(1)));
        lenient().when(repository.saveAllAndFlush(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("one RAW parent accepts multiple distinct substitutes without double parent rows")
    void replacesRawParentWithMultipleSubstitutes() {
        stubDraftAndRecipeParent(10L, "A", "kg", "RAW", null);
        when(materialRepository.findById("B")).thenReturn(Optional.of(material("B", "替代B", "kg", "001", FACTORY, true)));
        when(materialRepository.findById("C")).thenReturn(Optional.of(material("C", "替代C", "kg", "001", FACTORY, true)));
        when(repository.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(FACTORY, RECIPE)).thenReturn(List.of());

        List<BomItemSubstituteDTO> result = service.replaceForRecipeItem(
                FACTORY, RECIPE, 10L,
                List.of(new BomSubstituteInput("B", null),
                        new BomSubstituteInput("C", new BigDecimal("1.25"))));

        assertThat(result).extracting(BomItemSubstituteDTO::getSubstituteMaterialTypeId)
                .containsExactly("B", "C");
        assertThat(result).extracting(BomItemSubstituteDTO::getConversionFactor)
                .containsExactly(BigDecimal.ONE, new BigDecimal("1.25"));
        assertThat(result).extracting(BomItemSubstituteDTO::isConversionExplicit)
                .containsExactly(false, true);
    }

    @Test
    @DisplayName("same parent rejects self substitution and duplicate candidates")
    void rejectsSelfAndDuplicate() {
        stubDraftAndRecipeParent(10L, "A", "kg", "RAW", null);

        BusinessException self = assertThrows(BusinessException.class,
                () -> service.replaceForRecipeItem(
                        FACTORY, RECIPE, 10L, List.of(new BomSubstituteInput("A", null))));
        assertEquals("BOM_SUBSTITUTE_SELF_REFERENCE", self.getErrorCode());

        when(materialRepository.findById("B")).thenReturn(Optional.of(material("B", "替代B", "kg", "001", FACTORY, true)));
        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> service.replaceForRecipeItem(
                        FACTORY, RECIPE, 10L,
                        List.of(new BomSubstituteInput("B", null), new BomSubstituteInput("B", null))));
        assertEquals("BOM_SUBSTITUTE_DUPLICATE", duplicate.getErrorCode());
    }

    @Test
    @DisplayName("different or cross-dimension units require an explicit positive equivalence factor")
    void requiresExplicitFactorWhenUnitsDiffer() {
        stubDraftAndRecipeParent(10L, "A", "kg", "RAW", null);
        when(materialRepository.findById("B")).thenReturn(Optional.of(material("B", "替代B", "g", "001", FACTORY, true)));

        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.replaceForRecipeItem(
                        FACTORY, RECIPE, 10L, List.of(new BomSubstituteInput("B", null))));
        assertEquals("BOM_SUBSTITUTE_CONVERSION_REQUIRED", missing.getErrorCode());

        List<BomItemSubstituteDTO> result = service.replaceForRecipeItem(
                FACTORY, RECIPE, 10L,
                List.of(new BomSubstituteInput("B", new BigDecimal("1000"))));
        assertEquals(new BigDecimal("1E+3"), result.get(0).getConversionFactor());
        assertThat(result.get(0).isConversionExplicit()).isTrue();
    }

    @Test
    @DisplayName("cross-factory and inactive substitute identities fail closed")
    void enforcesFactoryAndSelectableMaterial() {
        stubDraftAndRecipeParent(10L, "A", "kg", "RAW", null);
        when(materialRepository.findById("X"))
                .thenReturn(Optional.of(material("X", "跨厂", "kg", "001", "OTHER", true)));
        BusinessException crossFactory = assertThrows(BusinessException.class,
                () -> service.replaceForRecipeItem(
                        FACTORY, RECIPE, 10L, List.of(new BomSubstituteInput("X", null))));
        assertEquals("BOM_SUBSTITUTE_MATERIAL_FACTORY_MISMATCH", crossFactory.getErrorCode());

        when(materialRepository.findById("I"))
                .thenReturn(Optional.of(material("I", "停用", "kg", "001", FACTORY, false)));
        BusinessException inactive = assertThrows(BusinessException.class,
                () -> service.replaceForRecipeItem(
                        FACTORY, RECIPE, 10L, List.of(new BomSubstituteInput("I", null))));
        assertEquals("BOM_SUBSTITUTE_MATERIAL_INACTIVE", inactive.getErrorCode());
    }

    @Test
    @DisplayName("parent recipe identity and positive factor are enforced before mutation")
    void enforcesParentRecipeAndPositiveFactor() {
        stubDraft();
        BomRecipeItem wrongRecipe = recipeItem(10L, "A", "kg", "RAW", null);
        wrongRecipe.setRecipeId("another-recipe");
        when(entityManager.find(BomRecipeItem.class, 10L, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(wrongRecipe);
        BusinessException wrongParent = assertThrows(BusinessException.class,
                () -> service.replaceForRecipeItem(
                        FACTORY, RECIPE, 10L, List.of(new BomSubstituteInput("B", null))));
        assertEquals("BOM_SUBSTITUTE_PARENT_RECIPE_MISMATCH", wrongParent.getErrorCode());

        stubDraftAndRecipeParent(11L, "A", "kg", "RAW", null);
        when(materialRepository.findById("B"))
                .thenReturn(Optional.of(material("B", "替代B", "kg", "001", FACTORY, true)));
        BusinessException invalidFactor = assertThrows(BusinessException.class,
                () -> service.replaceForRecipeItem(
                        FACTORY, RECIPE, 11L,
                        List.of(new BomSubstituteInput("B", BigDecimal.ZERO))));
        assertEquals("BOM_SUBSTITUTE_FACTOR_INVALID", invalidFactor.getErrorCode());
        verify(repository, never()).saveAllAndFlush(anyList());
    }

    @Test
    @DisplayName("a substitute from a different material family is rejected")
    void rejectsDifferentMaterialFamily() {
        stubDraftAndRecipeParent(10L, "A", "kg", "RAW", null);
        when(materialRepository.findById("PK"))
                .thenReturn(Optional.of(material("PK", "包材", "box", "003", FACTORY, true)));

        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> service.replaceForRecipeItem(
                        FACTORY, RECIPE, 10L,
                        List.of(new BomSubstituteInput("PK", BigDecimal.ONE))));

        assertEquals("BOM_SUBSTITUTE_MATERIAL_CATEGORY_MISMATCH", mismatch.getErrorCode());
    }

    @Test
    @DisplayName("a process seasoning parent snapshots process scope")
    void supportsProcessScopedAuxiliarySubstitution() {
        stubDraft();
        BomSeasoningItem seasoning = BomSeasoningItem.builder()
                .id(20L).factoryId(FACTORY).recipeId(RECIPE)
                .materialTypeId("AUX-A").name("辅料A")
                .workProcessId("process-1").workflowProcessNodeId("node-process-1")
                .section(BomSeasoningItem.SECTION_COOKING)
                .dosagePerKgG(BigDecimal.ONE).build();
        when(entityManager.find(BomSeasoningItem.class, 20L, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(seasoning);
        when(materialRepository.findById("AUX-A"))
                .thenReturn(Optional.of(material("AUX-A", "辅料A", "kg", "002", FACTORY, true)));
        when(materialRepository.findById("AUX-B"))
                .thenReturn(Optional.of(material("AUX-B", "辅料B", "kg", "002", FACTORY, true)));
        when(repository.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(FACTORY, RECIPE)).thenReturn(List.of());

        BomItemSubstituteDTO result = service.replaceForSeasoningItem(
                FACTORY, RECIPE, 20L, List.of(new BomSubstituteInput("AUX-B", null))).get(0);

        assertEquals("AUXILIARY", result.getMaterialCategory());
        assertEquals("process-1", result.getWorkProcessId());
        assertEquals("node-process-1", result.getWorkflowProcessNodeId());
        assertEquals(BomItemSubstitute.ParentKind.SEASONING_ITEM, result.getParentKind());
    }

    @Test
    @DisplayName("packaging substitutions inherit package level and role from the parent")
    void snapshotsPackagingScope() {
        stubDraftAndRecipeParent(30L, "PK-A", "box", "PACKAGING", "OUTER_CARTON");
        when(materialRepository.findById("PK-B"))
                .thenReturn(Optional.of(material("PK-B", "替代外箱", "box", "003", FACTORY, true)));
        when(repository.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(FACTORY, RECIPE)).thenReturn(List.of());

        BomItemSubstituteDTO result = service.replaceForRecipeItem(
                FACTORY, RECIPE, 30L, List.of(new BomSubstituteInput("PK-B", null))).get(0);

        assertEquals("PACKAGING", result.getMaterialCategory());
        assertEquals("OUTER_CARTON", result.getPackagingRole());
        assertEquals("spec-case", result.getPackagingSpecId());
    }

    @Test
    @DisplayName("packaging substitutions reject a different classification family even with the same unit")
    void rejectsCrossPackagingRoleSubstitution() {
        stubDraftAndRecipeParent(31L, "PK-BOX", "box", "PACKAGING", "OUTER_CARTON");
        RawMaterialType parent = material("PK-BOX", "外箱", "box", "003", FACTORY, true);
        parent.setCode("0030010001000001");
        parent.setCategory("外箱");
        RawMaterialType film = material("PK-FILM", "封膜", "box", "003", FACTORY, true);
        film.setCode("0030010002000001");
        film.setCategory("封膜");
        when(materialRepository.findById("PK-BOX")).thenReturn(Optional.of(parent));
        when(materialRepository.findById("PK-FILM")).thenReturn(Optional.of(film));

        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> service.replaceForRecipeItem(
                        FACTORY, RECIPE, 31L,
                        List.of(new BomSubstituteInput("PK-FILM", null))));

        assertEquals("BOM_SUBSTITUTE_PACKAGING_ROLE_MISMATCH", mismatch.getErrorCode());
    }

    @Test
    @DisplayName("packaging substitutions fail closed when classification cannot prove the role")
    void rejectsUnclassifiedPackagingSubstitution() {
        stubDraftAndRecipeParent(32L, "PK-LEGACY-A", "box", "PACKAGING", "OUTER_CARTON");
        RawMaterialType parent = material("PK-LEGACY-A", "历史外箱", "box", "003", FACTORY, true);
        parent.setCode("PK-LEGACY-A");
        parent.setCategory("包材");
        RawMaterialType substitute = material("PK-LEGACY-B", "历史替代箱", "box", "003", FACTORY, true);
        substitute.setCode("PK-LEGACY-B");
        substitute.setCategory("包材");
        when(materialRepository.findById("PK-LEGACY-A")).thenReturn(Optional.of(parent));
        when(materialRepository.findById("PK-LEGACY-B")).thenReturn(Optional.of(substitute));

        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> service.replaceForRecipeItem(
                        FACTORY, RECIPE, 32L,
                        List.of(new BomSubstituteInput("PK-LEGACY-B", null))));

        assertEquals("BOM_SUBSTITUTE_PACKAGING_CLASSIFICATION_REQUIRED", mismatch.getErrorCode());
    }

    @Test
    @DisplayName("recipe graph rejects A to B when an existing B to A relation would create a cycle")
    void rejectsCyclesAcrossParents() {
        stubDraftAndRecipeParent(10L, "A", "kg", "RAW", null);
        when(materialRepository.findById("B")).thenReturn(Optional.of(material("B", "替代B", "kg", "001", FACTORY, true)));
        BomItemSubstitute existing = relation(11L, "B", "A");
        when(repository.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(FACTORY, RECIPE))
                .thenReturn(List.of(existing));

        BusinessException cycle = assertThrows(BusinessException.class,
                () -> service.replaceForRecipeItem(
                        FACTORY, RECIPE, 10L, List.of(new BomSubstituteInput("B", null))));

        assertEquals("BOM_SUBSTITUTE_CYCLE", cycle.getErrorCode());
        verify(repository, never()).saveAllAndFlush(anyList());
    }

    @Test
    @DisplayName("unchanged payload is an idempotent no-op")
    void unchangedReplaceDoesNotWrite() {
        stubDraftAndRecipeParent(10L, "A", "kg", "RAW", null);
        when(materialRepository.findById("B")).thenReturn(Optional.of(material("B", "替代B", "kg", "001", FACTORY, true)));
        BomItemSubstitute existing = relation(10L, "A", "B");
        when(repository.findByFactoryIdAndRecipeIdAndParentKindAndParentRecipeItemIdOrderByCreatedAtAsc(
                FACTORY, RECIPE, BomItemSubstitute.ParentKind.RECIPE_ITEM, 10L))
                .thenReturn(List.of(existing));

        List<BomItemSubstituteDTO> result = service.replaceForRecipeItem(
                FACTORY, RECIPE, 10L, List.of(new BomSubstituteInput("B", null)));

        assertThat(result).hasSize(1);
        verify(repository, never()).saveAllAndFlush(anyList());
    }

    @Test
    @DisplayName("clone preserves exact rules against mapped version-local parent ids")
    void clonePreservesSnapshotAndScope() {
        String sourceRecipe = "recipe-v1";
        when(entityManager.find(BomRecipe.class, sourceRecipe, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(recipe(sourceRecipe, BomRecipe.Status.ACTIVE));
        when(entityManager.find(BomRecipe.class, RECIPE, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(recipe(RECIPE, BomRecipe.Status.DRAFT));
        BomRecipeItem targetParent = recipeItem(100L, "A", "kg", "RAW", null);
        when(entityManager.find(BomRecipeItem.class, 100L, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(targetParent);
        when(materialRepository.findById("A"))
                .thenReturn(Optional.of(material("A", "主料A", "kg", "001", FACTORY, true)));

        BomItemSubstitute source = relation(10L, "A", "B");
        source.setRecipeId(sourceRecipe);
        when(repository.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(FACTORY, sourceRecipe))
                .thenReturn(List.of(source));
        when(repository.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(FACTORY, RECIPE))
                .thenReturn(List.of());

        BomItemSubstituteDTO cloned = service.cloneRelations(
                FACTORY, sourceRecipe, RECIPE, Map.of(10L, 100L), Map.of()).get(0);

        assertEquals(100L, cloned.getParentRecipeItemId());
        assertEquals("B", cloned.getSubstituteMaterialTypeId());
        assertEquals(RECIPE, cloned.getRecipeId());
    }

    @Test
    @DisplayName("Workflow Family owner migration moves substitute relations with the parent item")
    void reassignsRelationsWithWorkflowOwnedParent() {
        String sourceRecipe = "recipe-source";
        when(entityManager.find(BomRecipe.class, sourceRecipe, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(recipe(sourceRecipe, BomRecipe.Status.DRAFT));
        when(entityManager.find(BomRecipe.class, RECIPE, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(recipe(RECIPE, BomRecipe.Status.DRAFT));
        BomItemSubstitute relation = relation(10L, "A", "B");
        relation.setRecipeId(sourceRecipe);
        when(repository.findByFactoryIdAndRecipeIdAndParentKindAndParentRecipeItemIdOrderByCreatedAtAsc(
                FACTORY, sourceRecipe, BomItemSubstitute.ParentKind.RECIPE_ITEM, 10L))
                .thenReturn(List.of(relation));
        when(repository.findByFactoryIdAndRecipeIdAndParentKindAndParentRecipeItemIdOrderByCreatedAtAsc(
                FACTORY, RECIPE, BomItemSubstitute.ParentKind.RECIPE_ITEM, 10L))
                .thenReturn(List.of());

        service.reassignRecipeItemRelations(
                FACTORY, sourceRecipe, RECIPE, 10L);

        assertEquals(RECIPE, relation.getRecipeId());
        verify(repository).saveAllAndFlush(List.of(relation));
    }

    private void stubDraftAndRecipeParent(
            Long id, String materialId, String unit, String category, String packagingRole) {
        stubDraft();
        BomRecipeItem item = recipeItem(id, materialId, unit, category, packagingRole);
        when(entityManager.find(BomRecipeItem.class, id, LockModeType.PESSIMISTIC_WRITE)).thenReturn(item);
        String primaryCode = "PACKAGING".equals(category) ? "003" : "001";
        when(materialRepository.findById(materialId))
                .thenReturn(Optional.of(material(materialId, "主项" + materialId, unit, primaryCode, FACTORY, true)));
        lenient().when(repository.findByFactoryIdAndRecipeIdAndParentKindAndParentRecipeItemIdOrderByCreatedAtAsc(
                FACTORY, RECIPE, BomItemSubstitute.ParentKind.RECIPE_ITEM, id))
                .thenReturn(List.of());
    }

    private void stubDraft() {
        when(entityManager.find(BomRecipe.class, RECIPE, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(recipe(RECIPE, BomRecipe.Status.DRAFT));
    }

    private BomRecipe recipe(String id, BomRecipe.Status status) {
        return BomRecipe.builder().id(id).factoryId(FACTORY).recipeCode("BOM-TEST")
                .productTypeId("P1").productName("产品").status(status)
                .outputQuantityPerUnit(BigDecimal.ONE).outputUnit("box").build();
    }

    private BomRecipeItem recipeItem(
            Long id, String materialId, String unit, String category, String packagingRole) {
        return BomRecipeItem.builder().id(id).factoryId(FACTORY).recipeId(RECIPE)
                .materialTypeId(materialId).materialName("主项" + materialId)
                .materialCategory(category).unit(unit)
                .packagingSpecId(packagingRole == null ? null : "spec-case")
                .packagingRole(packagingRole).build();
    }

    private RawMaterialType material(
            String id, String name, String unit, String primaryCode, String factoryId, boolean active) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId(factoryId);
        material.setCode("003".equals(primaryCode)
                ? "003001000100000" + (Math.abs(id.hashCode()) % 9 + 1)
                : "CODE-" + id);
        material.setName(name);
        material.setUnit(unit);
        material.setPrimaryCode(primaryCode);
        if ("003".equals(primaryCode)) material.setCategory("外箱");
        material.setIsActive(active);
        return material;
    }

    private UnitNormalizationResult normalized(String raw) {
        String code = "L".equals(raw) ? "l" : raw;
        UnitDimension dimension = switch (code) {
            case "kg", "g" -> UnitDimension.MASS;
            case "ml", "l" -> UnitDimension.VOLUME;
            default -> UnitDimension.PACKAGE;
        };
        CanonicalUnit unit = new CanonicalUnit(code, dimension, code, BigDecimal.ONE, code, 3);
        return new UnitNormalizationResult(raw, code, unit);
    }

    private BomItemSubstitute relation(Long parentId, String parentMaterialId, String substituteId) {
        return BomItemSubstitute.builder()
                .id("rel-" + parentId + "-" + substituteId)
                .factoryId(FACTORY).recipeId(RECIPE)
                .parentKind(BomItemSubstitute.ParentKind.RECIPE_ITEM)
                .parentRecipeItemId(parentId)
                .parentMaterialTypeIdSnapshot(parentMaterialId)
                .parentMaterialNameSnapshot("主项" + parentMaterialId)
                .materialCategorySnapshot("RAW")
                .substituteMaterialTypeId(substituteId)
                .substituteMaterialCodeSnapshot("CODE-" + substituteId)
                .substituteMaterialNameSnapshot("替代" + substituteId)
                .parentUnitSnapshot("kg").substituteUnitSnapshot("kg")
                .conversionFactor(BigDecimal.ONE).conversionExplicit(false)
                .version(0L).build();
    }
}
