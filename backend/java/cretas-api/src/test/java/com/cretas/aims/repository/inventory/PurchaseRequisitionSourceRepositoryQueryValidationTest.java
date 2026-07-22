package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PurchaseRequisition;
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

/** Real Hibernate startup gate for shortage-source identity and repository derivation. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("Purchase requisition source repository mapping validation")
class PurchaseRequisitionSourceRepositoryQueryValidationTest {

    @Autowired private PurchaseRequisitionRepository repository;
    @Autowired private EntityManager entityManager;

    @Test
    void repositoryAndSourceIdentityMappingBoot() {
        assertNotNull(repository);
        assertThat(entityManager.getMetamodel().entity(PurchaseRequisition.class)
                .getAttribute("sourceType")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(PurchaseRequisition.class)
                .getAttribute("sourceId")).isNotNull();
    }
}
