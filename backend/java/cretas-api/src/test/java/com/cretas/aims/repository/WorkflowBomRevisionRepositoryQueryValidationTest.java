package com.cretas.aims.repository;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup gate for immutable Workflow revisions and BOM node bindings. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class WorkflowBomRevisionRepositoryQueryValidationTest {

    @Autowired ProductProcessWorkflowRepository workflowRepository;
    @Autowired ProductProcessWorkflowRevisionRepository revisionRepository;
    @Autowired BomRecipeRepository recipeRepository;
    @Autowired BomSeasoningItemRepository seasoningRepository;

    @Test
    void repositoriesBootAndNewDerivedAndLockQueriesParse() {
        assertThat(workflowRepository
                .findByFactoryIdAndProductTypeIdOrderByDefinitionVersionDesc("F-JPA", "FG-NONE"))
                .isEmpty();
        assertThat(revisionRepository
                .findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc("F-JPA", "FG-NONE"))
                .isEmpty();
        assertThat(revisionRepository.findMaxRevisionNumber(-1L)).isZero();
        assertThat(recipeRepository
                .findFirstByFactoryIdAndProductTypeIdAndWorkflowRevisionIdAndStatusOrderByVersionDesc(
                        "F-JPA", "FG-NONE", -1L, BomRecipe.Status.ACTIVE))
                .isEmpty();
        assertThat(recipeRepository.lockByIdAndFactoryId("BOM-NONE", "F-JPA")).isEmpty();
        assertThat(seasoningRepository
                .findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc("BOM-NONE", "process-node"))
                .isEmpty();
        assertThat(seasoningRepository
                .findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                        "BOM-NONE", "process-node", "RM-NONE"))
                .isEmpty();
    }
}
