package com.cretas.aims.repository.bom;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("BOM-only recipe JPA startup validation")
class LegacyProductRecipeRemovalRepositoryQueryValidationTest {

    @Autowired
    private BomRecipeRepository bomRecipeRepository;

    @Autowired
    private BomSeasoningItemRepository bomSeasoningItemRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("canonical repositories start and legacy recipe entities are absent")
    void canonicalRepositoriesBootWithoutLegacyEntities() {
        assertNotNull(bomRecipeRepository);
        assertNotNull(bomSeasoningItemRepository);

        var entityNames = entityManager.getMetamodel().getEntities().stream()
                .map(entityType -> entityType.getJavaType().getSimpleName())
                .toList();
        assertThat(entityNames).doesNotContain("ProductRecipe", "RecipeIngredient");
    }
}
