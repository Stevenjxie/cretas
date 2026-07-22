package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
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

/** Real Hibernate startup gate for the sales processing/supply enum mappings. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("Sales order material-supply repository mapping validation")
class SalesOrderSupplyContractRepositoryQueryValidationTest {

    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private SalesOrderItemRepository salesOrderItemRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("order and line enum snapshots parse in a real JPA context")
    void repositoriesAndMappingsBoot() {
        assertNotNull(salesOrderRepository);
        assertNotNull(salesOrderItemRepository);
        assertThat(entityManager.getMetamodel().entity(SalesOrder.class)
                .getAttribute("processingMode")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(SalesOrder.class)
                .getAttribute("materialSupplyMode")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(SalesOrderItem.class)
                .getAttribute("processingMode")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(SalesOrderItem.class)
                .getAttribute("materialSupplyMode")).isNotNull();
    }
}
