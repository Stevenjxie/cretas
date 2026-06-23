package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.BomRecipeMigrationReport;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.entity.recipe.RecipeIngredient;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.recipe.ProductRecipeRepository;
import com.cretas.aims.repository.recipe.RecipeIngredientRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("U3 迁移 product_recipes → BOM")
@ExtendWith(MockitoExtension.class)
class BomRecipeMigrationServiceTest {

    @Mock ProductRecipeRepository productRecipeRepo;
    @Mock RecipeIngredientRepository ingredientRepo;
    @Mock BomRecipeRepository bomRecipeRepo;
    @Mock BomSeasoningItemRepository bomSeasoningItemRepo;

    @InjectMocks BomRecipeMigrationService service;

    private static final String F = "F006";
    private static final String PT = "PT-猪蹄";

    private ProductRecipe activeRecipe() {
        ProductRecipe pr = new ProductRecipe();
        pr.setId("pr-1");
        pr.setFactoryId(F);
        pr.setProductTypeId(PT);
        pr.setName("卤猪蹄");
        pr.setStatus("ACTIVE");
        pr.setCookingPotBaseKg(new BigDecimal("160.000"));
        pr.setSubsequentPotRatio(new BigDecimal("0.3333"));
        pr.setInjectionRate(new BigDecimal("0.2000"));
        return pr;
    }

    private RecipeIngredient ing(String section, String name, String dosage, boolean countIn, int seq) {
        RecipeIngredient i = new RecipeIngredient();
        i.setSection(section);
        i.setName(name);
        i.setSeq(seq);
        i.setDosagePerKgG(new BigDecimal(dosage));
        i.setPriceSource1(new BigDecimal("8"));
        i.setPriceSource2(null);
        i.setCountInSeasoning(countIn);
        return i;
    }

    private BomRecipe currentBom() {
        BomRecipe b = new BomRecipe();
        b.setId("bom-1");
        b.setFactoryId(F);
        b.setProductTypeId(PT);
        b.setProductName("卤猪蹄");
        return b;
    }

    @Test
    @DisplayName("迁移: 锅序参数写进 BOM + ingredient 1:1 拷贝(含老汤 countInSeasoning=false 保留)")
    void migrate_copiesPotParamsAndIngredients() {
        ProductRecipe pr = activeRecipe();
        when(productRecipeRepo.findByFactoryId(F)).thenReturn(List.of(pr));
        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(F, PT))
                .thenReturn(Optional.of(currentBom()));
        when(bomSeasoningItemRepo.countByRecipeIdIncludingDeleted("bom-1")).thenReturn(0L);
        when(ingredientRepo.findByRecipeIdOrderBySeqAsc("pr-1")).thenReturn(List.of(
                ing("INJECTION", "盐水", "1200", true, 0),
                ing("COOKING", "卤料包", "800", true, 1),
                ing("COOKING", "老汤", "2000", false, 2)));

        BomRecipeMigrationReport report = service.migrate(F, false);

        assertEquals(1, report.getMigrated());
        assertEquals(0, report.getSkippedNoBom());

        // 锅序参数写进 BOM
        ArgumentCaptor<BomRecipe> bomCap = ArgumentCaptor.forClass(BomRecipe.class);
        verify(bomRecipeRepo).save(bomCap.capture());
        BomRecipe saved = bomCap.getValue();
        assertEquals(new BigDecimal("160.000"), saved.getCookingPotBaseKg());
        assertEquals(new BigDecimal("0.3333"), saved.getSubsequentPotRatio());
        assertEquals(new BigDecimal("0.2000"), saved.getInjectionRate());

        // ingredient 1:1 → bom_seasoning_items
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BomSeasoningItem>> seasCap = ArgumentCaptor.forClass(List.class);
        verify(bomSeasoningItemRepo).saveAll(seasCap.capture());
        List<BomSeasoningItem> seas = seasCap.getValue();
        assertEquals(3, seas.size());
        assertTrue(seas.stream().allMatch(s -> "bom-1".equals(s.getRecipeId()) && F.equals(s.getFactoryId())));
        BomSeasoningItem oldSoup = seas.stream().filter(s -> "老汤".equals(s.getName())).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, oldSoup.getCountInSeasoning(), "老汤 countInSeasoning=false 必须保留");
        assertEquals("COOKING", oldSoup.getSection());
        assertEquals(2, oldSoup.getSeq());
        assertEquals(3, report.getResults().get(0).getSeasoningItemCount());
    }

    @Test
    @DisplayName("幂等: BOM 已有调料 → 跳过不重复写")
    void migrate_idempotent_skipAlreadyMigrated() {
        when(productRecipeRepo.findByFactoryId(F)).thenReturn(List.of(activeRecipe()));
        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(F, PT))
                .thenReturn(Optional.of(currentBom()));
        when(bomSeasoningItemRepo.countByRecipeIdIncludingDeleted("bom-1")).thenReturn(3L);

        BomRecipeMigrationReport report = service.migrate(F, false);

        assertEquals(0, report.getMigrated());
        assertEquals(1, report.getSkippedAlready());
        verify(bomRecipeRepo, never()).save(any());
        verify(bomSeasoningItemRepo, never()).saveAll(any());
        assertEquals(BomRecipeMigrationReport.SKIPPED_ALREADY, report.getResults().get(0).getAction());
    }

    @Test
    @DisplayName("无 BOM 的 SKU: 不自动建, 记 SKIPPED_NO_BOM 警告")
    void migrate_noBom_warnsNoAutoCreate() {
        when(productRecipeRepo.findByFactoryId(F)).thenReturn(List.of(activeRecipe()));
        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(F, PT))
                .thenReturn(Optional.empty());

        BomRecipeMigrationReport report = service.migrate(F, false);

        assertEquals(0, report.getMigrated());
        assertEquals(1, report.getSkippedNoBom());
        assertEquals(1, report.getWarnings().size());
        assertNull(report.getResults().get(0).getBomRecipeId());
        assertEquals(BomRecipeMigrationReport.SKIPPED_NO_BOM, report.getResults().get(0).getAction());
        verify(bomRecipeRepo, never()).save(any());
        verify(bomSeasoningItemRepo, never()).saveAll(any());
    }

    @Test
    @DisplayName("dryRun=true: 报告显示将迁移但不写库")
    void migrate_dryRun_noWrite() {
        when(productRecipeRepo.findByFactoryId(F)).thenReturn(List.of(activeRecipe()));
        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(F, PT))
                .thenReturn(Optional.of(currentBom()));
        when(bomSeasoningItemRepo.countByRecipeIdIncludingDeleted("bom-1")).thenReturn(0L);
        when(ingredientRepo.findByRecipeIdOrderBySeqAsc("pr-1")).thenReturn(List.of(
                ing("COOKING", "卤料包", "800", true, 0)));

        BomRecipeMigrationReport report = service.migrate(F, true);

        assertTrue(report.isDryRun());
        assertEquals(1, report.getMigrated());
        assertEquals(1, report.getResults().get(0).getSeasoningItemCount());
        verify(bomRecipeRepo, never()).save(any());
        verify(bomSeasoningItemRepo, never()).saveAll(any());
    }

    @Test
    @DisplayName("只处理 ACTIVE 配方 (非 ACTIVE 跳过)")
    void migrate_onlyActive() {
        ProductRecipe draft = activeRecipe();
        draft.setId("pr-draft");
        draft.setStatus("DRAFT");
        when(productRecipeRepo.findByFactoryId(F)).thenReturn(List.of(draft));

        BomRecipeMigrationReport report = service.migrate(F, false);

        assertEquals(0, report.getSkusProcessed());
        verify(bomRecipeRepo, never()).findByFactoryIdAndProductTypeIdAndIsCurrentTrue(anyString(), anyString());
    }
}
