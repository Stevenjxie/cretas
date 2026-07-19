package com.cretas.aims.repository.inventory;

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

/** Real Hibernate startup validation after removal of the legacy WorkOrder mapping. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("Canonical order repositories JPA startup validation")
class LegacyWorkOrderRemovalRepositoryQueryValidationTest {

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("sales and purchase repositories start without the generic WorkOrder entity")
    void canonicalRepositoriesBootWithoutLegacyWorkOrder() {
        assertNotNull(salesOrderRepository);
        assertNotNull(purchaseOrderRepository);

        var entityNames = entityManager.getMetamodel().getEntities().stream()
                .map(entityType -> entityType.getJavaType().getSimpleName())
                .toList();
        assertThat(entityNames).contains("SalesOrder", "PurchaseOrder");
        assertThat(entityNames).doesNotContain("WorkOrder");
    }
}
