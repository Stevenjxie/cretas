package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.TransferStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Real Hibernate startup gate for the production-plan rolling-transfer lookup. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("Internal transfer repository query startup validation")
class InternalTransferRepositoryQueryValidationTest {

    @Autowired private InternalTransferRepository transferRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("factory-scoped production-plan lookup parses in a real JPA context")
    void productionPlanTransferLookupBoots() {
        assertNotNull(transferRepository);
        assertNotNull(entityManager.getMetamodel().entity(
                com.cretas.aims.entity.inventory.InternalTransfer.class));
        assertNotNull(entityManager.getMetamodel().entity(
                com.cretas.aims.entity.inventory.InternalTransferItem.class)
                .getAttribute("packageQuantitySnapshot"));

        assertThatNoException().isThrownBy(() -> transferRepository
                .findBySourceFactoryIdAndProductionPlanIdAndStatusInOrderByCreatedAtDesc(
                        "F006", "plan-test",
                        List.of(TransferStatus.DRAFT, TransferStatus.REQUESTED, TransferStatus.APPROVED)));
    }
}
