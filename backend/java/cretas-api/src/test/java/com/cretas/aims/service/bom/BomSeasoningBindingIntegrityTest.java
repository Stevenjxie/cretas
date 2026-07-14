package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("BOM seasoning binding integrity")
class BomSeasoningBindingIntegrityTest {

    private static final String FACTORY = "F-BINDING";
    private static final String OTHER_FACTORY = "F-OTHER";

    @Autowired private BomRecipeRepository recipeRepository;
    @Autowired private BomSeasoningItemRepository seasoningItemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void installH2EquivalentUniqueIndex() {
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_bsi_recipe_wp_material_test
                    ON bom_seasoning_items(recipe_id, work_process_id, material_type_id)
                """);
    }

    @Test
    void bindingLookupsAreScopedToRecipeProcessMaterialAndBindingId() {
        BomRecipe recipe = recipe("recipe-lookup", FACTORY, BomRecipe.Status.DRAFT, 0L);
        recipeRepository.saveAndFlush(recipe);
        BomSeasoningItem item = seasoningItem(recipe.getId(), "process-a", "salt");
        item.setSubsequentPotRatio(new BigDecimal("0.6000"));
        BomSeasoningItem saved = seasoningItemRepository.saveAndFlush(item);

        BomSeasoningItem byNaturalKey = seasoningItemRepository
                .findByRecipeIdAndWorkProcessIdAndMaterialTypeId(recipe.getId(), "process-a", "salt")
                .orElseThrow();
        assertEquals(saved.getId(), byNaturalKey.getId());
        assertEquals(new BigDecimal("0.6000"), byNaturalKey.getSubsequentPotRatio());
        assertTrue(seasoningItemRepository.findByIdAndRecipeId(saved.getId(), recipe.getId()).isPresent());
        assertTrue(seasoningItemRepository.findByIdAndRecipeId(saved.getId(), "another-recipe").isEmpty());
    }

    @Test
    void sameMaterialMayBeBoundToDifferentProcessesButNotTwiceToSameProcess() {
        BomRecipe recipe = recipe("recipe-unique", FACTORY, BomRecipe.Status.DRAFT, 0L);
        recipeRepository.saveAndFlush(recipe);
        seasoningItemRepository.saveAndFlush(seasoningItem(recipe.getId(), "process-a", "salt"));
        assertDoesNotThrow(() -> seasoningItemRepository.saveAndFlush(
                seasoningItem(recipe.getId(), "process-b", "salt")));

        assertThrows(DataIntegrityViolationException.class, () -> seasoningItemRepository.saveAndFlush(
                seasoningItem(recipe.getId(), "process-a", "salt")));
    }

    @Test
    void revisionClaimRequiresMatchingFactoryDraftStatusAndExpectedRevision() {
        recipeRepository.saveAndFlush(recipe("recipe-claim", FACTORY, BomRecipe.Status.DRAFT, 4L));

        assertEquals(0, recipeRepository.claimSeasoningRevision(
                "recipe-claim", OTHER_FACTORY, 4L));
        assertEquals(0, recipeRepository.claimSeasoningRevision(
                "recipe-claim", FACTORY, 3L));
        assertEquals(1, recipeRepository.claimSeasoningRevision(
                "recipe-claim", FACTORY, 4L));
        assertEquals(0, recipeRepository.claimSeasoningRevision(
                "recipe-claim", FACTORY, 4L));
        assertEquals(5L, recipeRepository.findById("recipe-claim").orElseThrow().getSeasoningRevision());

        recipeRepository.saveAndFlush(recipe("recipe-active", FACTORY, BomRecipe.Status.ACTIVE, 0L));
        assertEquals(0, recipeRepository.claimSeasoningRevision(
                "recipe-active", FACTORY, 0L));
    }

    @Test
    void migrationBackfillsBindingRatioAndDefinesNullablePartialUniqueKey() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/flyway/"
                + "V20261028_66__seasoning_binding_revision_and_pot_rule.sql"))
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertTrue(sql.contains("add column if not exists subsequent_pot_ratio numeric(8,4)"));
        assertTrue(sql.contains("add column if not exists seasoning_revision bigint not null default 0"));
        assertTrue(sql.contains("from bom_process_seasoning"));
        assertTrue(sql.contains("bsi.subsequent_pot_ratio is null"));
        assertTrue(sql.contains("where deleted_at is null and work_process_id is not null and material_type_id is not null"));
    }

    private BomRecipe recipe(String id, String factoryId, BomRecipe.Status status, Long revision) {
        return BomRecipe.builder()
                .id(id)
                .factoryId(factoryId)
                .recipeCode("BOM-" + id)
                .productTypeId("product-1")
                .productName("Product")
                .outputQuantityPerUnit(BigDecimal.ONE)
                .status(status)
                .seasoningRevision(revision)
                .build();
    }

    private BomSeasoningItem seasoningItem(String recipeId, String processId, String materialId) {
        return BomSeasoningItem.builder()
                .recipeId(recipeId)
                .factoryId(FACTORY)
                .materialTypeId(materialId)
                .workProcessId(processId)
                .section("COOKING")
                .seq(0)
                .name("Salt")
                .dosagePerKgG(BigDecimal.ONE)
                .build();
    }
}
