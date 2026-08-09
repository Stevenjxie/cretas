package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.CustomerMaterialArrivalNotice;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.FgReservationLedgerRepository;
import com.cretas.aims.repository.inventory.CustomerMaterialArrivalNoticeRepository;
import com.cretas.aims.entity.enums.CustomerMaterialArrivalStatus;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.EnumSet;
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
    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private FgReservationLedgerRepository fgReservationLedgerRepository;
    @Autowired private CustomerMaterialArrivalNoticeRepository arrivalNoticeRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("repositories boot and ownership/lineage attributes parse in a real JPA context")
    void repositoriesAndMappingsBoot() {
        assertNotNull(materialBatchRepository);
        assertNotNull(finishedGoodsBatchRepository);
        assertNotNull(productionPlanRepository);
        assertNotNull(salesOrderRepository);
        assertNotNull(fgReservationLedgerRepository);
        assertNotNull(arrivalNoticeRepository);

        var material = entityManager.getMetamodel().entity(MaterialBatch.class);
        assertThat(material.getAttribute("ownership")).isNotNull();
        assertThat(material.getAttribute("ownerCustomerId")).isNotNull();
        assertThat(material.getAttribute("sourceSalesOrderId")).isNotNull();
        assertThat(material.getAttribute("sourceSalesOrderItemId")).isNotNull();
        assertThat(material.getAttribute("sourceEventKey")).isNotNull();
        assertThat(material.getAttribute("materialTypeId")).isNotNull();
        assertThat(material.getAttribute("productTypeId")).isNotNull();
        assertThat(material.getAttribute("productType")).isNotNull();

        var finished = entityManager.getMetamodel().entity(FinishedGoodsBatch.class);
        assertThat(finished.getAttribute("ownership")).isNotNull();
        assertThat(finished.getAttribute("ownerCustomerId")).isNotNull();
        assertThat(finished.getAttribute("sourceSalesOrderId")).isNotNull();
        assertThat(finished.getAttribute("sourceSalesOrderItemId")).isNotNull();

        var salesOrder = entityManager.getMetamodel().entity(SalesOrder.class);
        assertThat(salesOrder.getAttribute("customerStockFulfillmentMode")).isNotNull();

        var arrivalNotice = entityManager.getMetamodel().entity(CustomerMaterialArrivalNotice.class);
        assertThat(arrivalNotice.getAttribute("reason")).isNotNull();
        assertThat(arrivalNotice.getAttribute("customerId")).isNotNull();
        assertThat(arrivalNotice.getAttribute("status")).isNotNull();
        assertThat(arrivalNotice.getAttribute("reviewedBy")).isNotNull();
        assertThat(arrivalNotice.getAttribute("reviewedAt")).isNotNull();
        assertThat(arrivalNotice.getAttribute("reviewRemark")).isNotNull();

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
            materialBatchRepository.calculateInventoryValue("F006");
            materialBatchRepository.sumQuantityByMaterialType("F006");
            materialBatchRepository.findStockSummaryByFactory("F006");
            materialBatchRepository.sumAvailableQuantityByMaterialType("F006", "MAT-1");
            materialBatchRepository.findStockUnitsByMaterialType("F006", "MAT-1");
            materialBatchRepository.sumAvailableQuantityByMaterialTypeAndWarehouse(
                    "F006", "MAT-1", "WH-WKS");
            materialBatchRepository.calculateConsumedValue(
                    "F006", LocalDateTime.of(2026, 7, 1, 0, 0),
                    LocalDateTime.of(2026, 8, 1, 0, 0));
            materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                    "F006", "MAT-1", "WH-WKS");
            materialBatchRepository.findAvailableCustomerSuppliedBatchesFEFOByWarehouseForUpdate(
                    "F006", "MAT-1", "WH-WKS", "CUSTOMER-1", "SO-1");
            materialBatchRepository.findAvailableUnassignedCustomerOwnedBatchesFEFOByWarehouseForUpdate(
                    "F006", "MAT-1", "WH-WKS", "CUSTOMER-1");
            materialBatchRepository.sumAvailableUnassignedCustomerOwnedRawStock(
                    "F006", "MAT-1", "CUSTOMER-1");
            materialBatchRepository.findUnassignedCustomerOwnedRawStockUnits(
                    "F006", "MAT-1", "CUSTOMER-1");
            materialBatchRepository.findAllAvailableInWarehouse("F006", "WH-WKS");
            materialBatchRepository.countLowStockMaterials("F006");

            finishedGoodsBatchRepository.findAvailableBatchesByWarehouse(
                    "F006", "SKU-1", "WH-LOG");
            finishedGoodsBatchRepository.findShippableBatchesByWarehouse(
                    "F006", "SKU-1", "WH-LOG");
            finishedGoodsBatchRepository.findAvailableCustomerOwnedBatchesByWarehouse(
                    "F006", "SKU-1", "WH-LOG", "CUSTOMER-1", "SO-1");
            finishedGoodsBatchRepository.findAvailableCustomerOwnedBatchesFefoAllWarehousesExcluding(
                    "F006", "SKU-1", "WH-RD", "CUSTOMER-1", "SO-1");
            finishedGoodsBatchRepository.findShippableCustomerOwnedBatchesByWarehouse(
                    "F006", "SKU-1", "WH-LOG", "CUSTOMER-1", "SO-1");
            finishedGoodsBatchRepository.findShippableCustomerOwnedBatchesAllWarehousesExcluding(
                    "F006", "SKU-1", "WH-RD", "CUSTOMER-1", "SO-1");
            finishedGoodsBatchRepository.findAvailableUnassignedCustomerOwnedBatchesAllWarehousesExcluding(
                    "F006", "SKU-1", "WH-RD", "CUSTOMER-1");
            finishedGoodsBatchRepository.findShippablePrestockedBatchesByWarehouse(
                    "F006", "SKU-1", "WH-LOG", "CUSTOMER-1", "SO-1");
            finishedGoodsBatchRepository.findShippablePrestockedBatchesAllWarehousesExcluding(
                    "F006", "SKU-1", "WH-RD", "CUSTOMER-1", "SO-1");
            fgReservationLedgerRepository.sumActiveReservedBySalesOrderAndBatch("SO-1", "FG-1");
            arrivalNoticeRepository.findByFactoryIdAndStatusInOrderByExpectedArrivalAtAscCreatedAtAsc(
                    "F006", EnumSet.of(CustomerMaterialArrivalStatus.OPEN));
            arrivalNoticeRepository.findByIdAndFactoryIdForUpdate("NOTICE-1", "F006");
        });
    }
}
