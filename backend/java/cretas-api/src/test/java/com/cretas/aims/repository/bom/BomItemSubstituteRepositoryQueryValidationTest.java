package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomItemSubstitute;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup gate for the structured BOM substitute entity and derived queries. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class BomItemSubstituteRepositoryQueryValidationTest {

    @Autowired BomItemSubstituteRepository repository;

    @Test
    void repositoryBootsAndAllParentQueriesParse() {
        assertThat(repository.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc("F-JPA", "recipe-none"))
                .isEmpty();
        assertThat(repository
                .findByFactoryIdAndRecipeIdAndParentKindAndParentRecipeItemIdOrderByCreatedAtAsc(
                        "F-JPA", "recipe-none", BomItemSubstitute.ParentKind.RECIPE_ITEM, 1L))
                .isEmpty();
        assertThat(repository
                .findByFactoryIdAndRecipeIdAndParentKindAndParentSeasoningItemIdOrderByCreatedAtAsc(
                        "F-JPA", "recipe-none", BomItemSubstitute.ParentKind.SEASONING_ITEM, 1L))
                .isEmpty();
    }
}
