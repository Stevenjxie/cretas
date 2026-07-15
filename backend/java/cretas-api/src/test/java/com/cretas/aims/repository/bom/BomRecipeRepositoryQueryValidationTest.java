package com.cretas.aims.repository.bom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Hibernate startup validation for {@link BomRecipeRepository} queries.
 *
 * <p>Mockito service tests do not parse JPQL. This slice test prevents an invalid
 * nested-enum literal from reaching the blue-green deployment health gate again.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("BomRecipeRepository JPQL startup validation")
class BomRecipeRepositoryQueryValidationTest {

    @Autowired
    private BomRecipeRepository repository;

    @Test
    @DisplayName("repository bean starts and all declared JPQL queries validate")
    void repositoryBoots() {
        assertNotNull(repository);
    }
}
