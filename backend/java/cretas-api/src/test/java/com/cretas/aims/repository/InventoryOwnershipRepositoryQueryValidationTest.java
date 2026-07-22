package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
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
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Real Hibernate startup gate for inventory ownership and source-lineage mappings. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("Inventory ownership repository query startup validation")
class InventoryOwnershipRepositoryQueryValidationTest {

    @Autowired private MaterialBatchRepository materialBatchRepository;
    @Autowired private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Autowired private ProductionPlanRepository productionPlanRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("repositories boot and ownership/lineage attributes parse in a real JPA context")
    void repositoriesAndMappingsBoot() {
        assertNotNull(materialBatchRepository);
        assertNotNull(finishedGoodsBatchRepository);
        assertNotNull(productionPlanRepository);

        var material = entityManager.getMetamodel().entity(MaterialBatch.class);
        assertThat(material.getAttribute("ownership")).isNotNull();
        assertThat(material.getAttribute("ownerCustomerId")).isNotNull();
        assertThat(material.getAttribute("sourceSalesOrderId")).isNotNull();
        assertThat(material.getAttribute("sourceSalesOrderItemId")).isNotNull();
        assertThat(material.getAttribute("sourceEventKey")).isNotNull();

        var finished = entityManager.getMetamodel().entity(FinishedGoodsBatch.class);
        assertThat(finished.getAttribute("ownership")).isNotNull();
        assertThat(finished.getAttribute("ownerCustomerId")).isNotNull();
        assertThat(finished.getAttribute("sourceSalesOrderId")).isNotNull();
        assertThat(finished.getAttribute("sourceSalesOrderItemId")).isNotNull();

        var plan = entityManager.getMetamodel().entity(ProductionPlan.class);
        assertThat(plan.getAttribute("customerId")).isNotNull();
        assertThat(plan.getAttribute("processingMode")).isNotNull();
        assertThat(plan.getAttribute("materialSupplyMode")).isNotNull();
        assertThat(plan.getAttribute("outputOwnership")).isNotNull();

        assertThatNoException().isThrownBy(() -> {
            materialBatchRepository.sumAvailableRawStockQuantityByMaterialType("F006", "MAT-1");
            materialBatchRepository.sumAvailableCustomerSuppliedRawStock(
                    "F006", "MAT-1", "CUSTOMER-1", "SO-1");
            materialBatchRepository.findRawStockUnitsByMaterialType("F006", "MAT-1");
            materialBatchRepository.findCustomerSuppliedRawStockUnits(
                    "F006", "MAT-1", "CUSTOMER-1", "SO-1");
        });
    }
}
