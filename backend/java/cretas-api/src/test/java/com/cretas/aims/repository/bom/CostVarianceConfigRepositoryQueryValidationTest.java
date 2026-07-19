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
 * Real Hibernate/JPA startup validation for the canonical cost-variance model.
 *
 * <p>The removed cost_variance_configs table has no JPA model. This slice test
 * proves that the retained ProductCostVarianceConfig Entity and all derived
 * ProductCostVarianceConfigRepository queries still initialize together.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("ProductCostVarianceConfigRepository JPA startup validation")
class CostVarianceConfigRepositoryQueryValidationTest {

    @Autowired
    private ProductCostVarianceConfigRepository repository;

    @Test
    @DisplayName("canonical Entity and all declared repository queries initialize")
    void repositoryBoots() {
        assertNotNull(repository);
    }
}
