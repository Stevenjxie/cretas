package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.ReturnType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real Hibernate startup gate for the document-trace source-order lookup.
 *
 * <p>Mockito service tests stub the repository, so a derived-query typo (e.g. a property
 * that does not exist on {@code ReturnOrder}) would survive compilation and only blow up
 * when Spring starts in production.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("Return order repository query startup validation")
class ReturnOrderRepositoryQueryValidationTest {

    @Autowired private ReturnOrderRepository returnOrderRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("document-trace source-order lookup parses in a real JPA context")
    void sourceOrderLookupBoots() {
        assertNotNull(returnOrderRepository);
        assertNotNull(entityManager.getMetamodel()
                .entity(com.cretas.aims.entity.inventory.ReturnOrder.class)
                .getAttribute("sourceOrderId"));

        assertThatNoException().isThrownBy(() -> returnOrderRepository
                .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                        "F006", ReturnType.SALES_RETURN, "so-test"));
        assertThatNoException().isThrownBy(() -> returnOrderRepository
                .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                        "F006", ReturnType.PURCHASE_RETURN, "po-test"));
    }
}
