package com.cretas.aims.repository.inventory;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderSuppliedMaterialRequirementStatus;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesOrderSuppliedMaterialRequirement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.factory.WarehouseInventoryGuardService;
import com.cretas.aims.service.inventory.SalesOrderSuppliedMaterialRequirementService;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.unit.UnitUsageScope;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Real Hibernate startup and customer-supplied warehouse projection contract gate. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({SalesOrderSuppliedMaterialRequirementService.class, MaterialBatchMapper.class})
class SalesOrderSuppliedMaterialRequirementRepositoryQueryValidationTest {

    @Autowired EntityManager entityManager;
    @Autowired SalesOrderSuppliedMaterialRequirementRepository requirementRepository;
    @Autowired MaterialBatchRepository materialBatchRepository;
    @Autowired SalesOrderSuppliedMaterialRequirementService requirementService;

    @MockBean UnitContractService unitContractService;
    @MockBean WarehouseInventoryGuardService warehouseInventoryGuardService;

    @BeforeEach
    void configureUnitContract() {
        CanonicalUnit kg = new CanonicalUnit(
                "kg", UnitDimension.MASS, "kg", BigDecimal.ONE, "千克", 4,
                Set.of(UnitUsageScope.INVENTORY_QUANTITY), "kg", true);
        when(unitContractService.normalize(anyString(), anyString()))
                .thenAnswer(invocation -> new UnitNormalizationResult(
                        invocation.getArgument(1), "kg", kg));
        when(unitContractService.supportsUsage(
                anyString(), anyString(), org.mockito.ArgumentMatchers.eq(UnitUsageScope.INVENTORY_QUANTITY)))
                .thenReturn(true);
    }

    @Test
    void createUpdateAndIllegalModeContractsAreFailClosed() {
        Fixture fixture = fixture("F-SUPPLY-CREATE", SalesOrderStatus.DRAFT, true);
        CreateSalesOrderRequest.SuppliedMaterialRequirementDTO request = request(
                fixture.material().getId(), fixture.warehouse().getId(), fixture.item().getId(), "3.50");

        List<SalesOrderSuppliedMaterialRequirement> created =
                requirementService.createForOrder(fixture.order(), List.of(request));
        String stableTaskId = created.get(0).getId();
        assertThat(created).singleElement().satisfies(requirement -> {
            assertThat(requirement.getMaterialName()).isEqualTo(fixture.material().getName());
            assertThat(requirement.getUnit()).isEqualTo("kg");
            assertThat(requirement.getRemainingQuantity()).isEqualByComparingTo("3.50");
        });

        CreateSalesOrderRequest.SuppliedMaterialRequirementDTO replacement = request(
                fixture.material().getId(), fixture.warehouse().getId(), fixture.item().getId(), "8.00");
        List<SalesOrderSuppliedMaterialRequirement> updated =
                requirementService.updateForOrder(fixture.order(), List.of(replacement), false);
        assertThat(updated).singleElement()
                .satisfies(requirement -> {
                    assertThat(requirement.getId()).isEqualTo(stableTaskId);
                    assertThat(requirement.getExpectedQuantity())
                            .isEqualByComparingTo("8.00");
                });
        assertThat(requirementRepository
                .findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(fixture.order().getId()))
                .singleElement()
                .extracting(SalesOrderSuppliedMaterialRequirement::getExpectedQuantity)
                .isEqualTo(new BigDecimal("8.00"));

        CreateSalesOrderRequest.SuppliedMaterialRequirementDTO excessivePrecision = request(
                fixture.material().getId(), fixture.warehouse().getId(), fixture.item().getId(), "8.001");
        assertThatThrownBy(() -> requirementService.updateForOrder(
                fixture.order(), List.of(excessivePrecision), false))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo("CUSTOMER_SUPPLIED_QUANTITY_PRECISION_INVALID"));

        assertThatThrownBy(() -> requirementService.updateForOrder(
                fixture.order(), List.of(replacement, replacement), false))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo("CUSTOMER_SUPPLIED_REQUIREMENT_DUPLICATE"));

        SalesOrderSuppliedMaterialRequirement received = updated.get(0);
        received.setReceivedQuantity(BigDecimal.ONE);
        received.setStatus(SalesOrderSuppliedMaterialRequirementStatus.PARTIALLY_RECEIVED);
        requirementRepository.saveAndFlush(received);

        fixture.order().setProcessingMode(SalesProcessingMode.STANDARD_SALE);
        fixture.order().setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        assertThatThrownBy(() -> requirementService.updateForOrder(
                fixture.order(), null, false))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo("CUSTOMER_SUPPLIED_REQUIREMENT_RECEIVED_IMMUTABLE"));

        fixture.order().setProcessingMode(SalesProcessingMode.TOLL_PROCESSING);
        fixture.order().setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        assertThatThrownBy(() -> requirementService.updateForOrder(
                fixture.order(), List.of(), false))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo("CUSTOMER_SUPPLIED_REQUIREMENTS_REQUIRED"));
    }

    @Test
    void taskProjectionRequiresApprovalRemainingQuantityAndFactoryOwnership() {
        Fixture approved = fixture("F-SUPPLY-A", SalesOrderStatus.FINANCE_APPROVED, true);
        persistRequirement(approved, "REQ-APPROVED", "10", "2");

        Fixture draft = fixture("F-SUPPLY-DRAFT", SalesOrderStatus.DRAFT, true);
        persistRequirement(draft, "REQ-DRAFT", "10", "0");

        Fixture completed = fixture("F-SUPPLY-A-DONE", SalesOrderStatus.FINANCE_APPROVED, true);
        persistRequirement(completed, "REQ-DONE", "5", "5");

        Fixture otherFactory = fixture("F-SUPPLY-B", SalesOrderStatus.FINANCE_APPROVED, true);
        persistRequirement(otherFactory, "REQ-OTHER", "4", "0");
        entityManager.flush();
        entityManager.clear();

        assertThat(requirementService.getPendingReceivingTasks("F-SUPPLY-A"))
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.getTaskId()).isEqualTo("REQ-APPROVED");
                    assertThat(task.getSourceType()).isEqualTo("SALES_ORDER_CUSTOMER_SUPPLIED");
                    assertThat(task.getFactoryId()).isEqualTo("F-SUPPLY-A");
                    assertThat(task.getCustomerId()).isEqualTo(approved.customer().getId());
                    assertThat(task.getSalesOrderId()).isEqualTo(approved.order().getId());
                    assertThat(task.getSalesOrderItemId()).isEqualTo(approved.item().getId());
                    assertThat(task.getRemainingQuantity()).isEqualByComparingTo("8");
                    assertThat(task.getTargetWarehouseId()).isEqualTo(approved.warehouse().getId());
                });
        assertThat(requirementService.getPendingReceivingTasks("F-SUPPLY-DRAFT")).isEmpty();
        assertThat(requirementService.getPendingReceivingTasks("F-SUPPLY-A-DONE")).isEmpty();
        assertThat(requirementService.getPendingReceivingTasks("F-SUPPLY-B"))
                .singleElement()
                .extracting(task -> task.getTaskId())
                .isEqualTo("REQ-OTHER");
    }

    @Test
    void historicalNullSupplyContractRemainsReadableAndProducesNoTask() {
        Fixture historical = fixture("F-SUPPLY-HISTORY", SalesOrderStatus.FINANCE_APPROVED, false);
        entityManager.flush();
        entityManager.clear();

        SalesOrder reloaded = entityManager.find(SalesOrder.class, historical.order().getId());
        assertThat(reloaded.getProcessingMode()).isNull();
        assertThat(reloaded.getMaterialSupplyMode()).isNull();
        assertThat(reloaded.getSuppliedMaterials()).isEmpty();
        assertThat(requirementService.getPendingReceivingTasks("F-SUPPLY-HISTORY")).isEmpty();
        assertThat(entityManager.getMetamodel()
                .entity(SalesOrderSuppliedMaterialRequirement.class)
                .getAttribute("receivedQuantity")).isNotNull();
    }

    @Test
    void lockedAllocationQueriesKeepCompanyAndCustomerOwnedInventorySeparated() {
        Fixture fixture = fixture("F-SUPPLY-OWNERSHIP", SalesOrderStatus.FINANCE_APPROVED, true);
        MaterialBatch company = batch(fixture, "B-COMPANY", InventoryOwnership.COMPANY_OWNED);
        MaterialBatch customer = batch(fixture, "B-CUSTOMER", InventoryOwnership.CUSTOMER_OWNED);
        entityManager.persist(company);
        entityManager.persist(customer);
        entityManager.flush();
        entityManager.clear();

        assertThat(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                fixture.order().getFactoryId(), fixture.material().getId(), fixture.warehouse().getId()))
                .extracting(MaterialBatch::getBatchNumber)
                .containsExactly("B-COMPANY");
        assertThat(materialBatchRepository
                .findAvailableCustomerSuppliedBatchesFEFOByWarehouseForUpdate(
                        fixture.order().getFactoryId(),
                        fixture.material().getId(),
                        fixture.warehouse().getId(),
                        fixture.customer().getId(),
                        fixture.order().getId()))
                .extracting(MaterialBatch::getBatchNumber)
                .containsExactly("B-CUSTOMER");
    }

    private Fixture fixture(String factoryId, SalesOrderStatus status, boolean withSupplyContract) {
        Factory factory = new Factory();
        factory.setId(factoryId);
        factory.setName(factoryId);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);

        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername("supply-" + factoryId);
        user.setPasswordHash("test-only");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();

        Customer customer = new Customer();
        customer.setId("C-" + factoryId);
        customer.setFactoryId(factoryId);
        customer.setCode("C-" + factoryId);
        customer.setCustomerCode("C-" + factoryId);
        customer.setName("Customer " + factoryId);
        customer.setIsActive(true);
        customer.setCreatedBy(user.getId());
        entityManager.persist(customer);

        RawMaterialType material = new RawMaterialType();
        material.setId("M-" + factoryId);
        material.setFactoryId(factoryId);
        material.setCode("RM-" + factoryId);
        material.setName("Material " + factoryId);
        material.setUnit("kg");
        material.setIsActive(true);
        material.setCreatedBy(user.getId());
        entityManager.persist(material);

        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setId("W-" + factoryId);
        warehouse.setFactoryId(factoryId);
        warehouse.setCode("WH-RAW-" + factoryId);
        warehouse.setName("Raw warehouse " + factoryId);
        warehouse.setType(FactoryWarehouse.WarehouseType.RAW);
        warehouse.setIsActive(true);
        entityManager.persist(warehouse);

        SalesOrder order = new SalesOrder();
        order.setId("SO-" + factoryId);
        order.setFactoryId(factoryId);
        order.setOrderNumber("SO-NO-" + factoryId);
        order.setCustomerId(customer.getId());
        order.setOrderDate(LocalDate.of(2026, 7, 22));
        order.setStatus(status);
        order.setCreatedBy(user.getId());
        if (withSupplyContract) {
            order.setProcessingMode(SalesProcessingMode.TOLL_PROCESSING);
            order.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        }
        entityManager.persist(order);

        ProductType product = new ProductType();
        product.setId("P-" + factoryId);
        product.setFactoryId(factoryId);
        product.setCode("P-" + factoryId);
        product.setName("Product " + factoryId);
        product.setUnit("box");
        product.setIsActive(true);
        product.setCreatedBy(user.getId());
        entityManager.persist(product);

        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrderId(order.getId());
        item.setProductTypeId("P-" + factoryId);
        item.setProductName("Product " + factoryId);
        item.setQuantity(BigDecimal.ONE);
        item.setUnit("box");
        if (withSupplyContract) {
            item.setProcessingMode(SalesProcessingMode.TOLL_PROCESSING);
            item.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        }
        entityManager.persist(item);
        entityManager.flush();
        return new Fixture(order, item, customer, material, warehouse);
    }

    private CreateSalesOrderRequest.SuppliedMaterialRequirementDTO request(
            String materialId, String warehouseId, Long salesOrderItemId, String quantity) {
        CreateSalesOrderRequest.SuppliedMaterialRequirementDTO request =
                new CreateSalesOrderRequest.SuppliedMaterialRequirementDTO();
        request.setMaterialTypeId(materialId);
        request.setMaterialName("client display value");
        request.setExpectedQuantity(new BigDecimal(quantity));
        request.setUnit("公斤");
        request.setExpectedArrivalAt(LocalDateTime.of(2026, 7, 23, 9, 30));
        request.setTargetWarehouseId(warehouseId);
        request.setSalesOrderItemId(salesOrderItemId);
        return request;
    }

    private void persistRequirement(
            Fixture fixture, String id, String expected, String received) {
        SalesOrderSuppliedMaterialRequirement requirement =
                new SalesOrderSuppliedMaterialRequirement();
        requirement.setId(id);
        requirement.setFactoryId(fixture.order().getFactoryId());
        requirement.setCustomerId(fixture.customer().getId());
        requirement.setSalesOrderId(fixture.order().getId());
        requirement.setSalesOrderItemId(fixture.item().getId());
        requirement.setMaterialTypeId(fixture.material().getId());
        requirement.setMaterialName(fixture.material().getName());
        requirement.setExpectedQuantity(new BigDecimal(expected));
        requirement.setReceivedQuantity(new BigDecimal(received));
        requirement.setUnit("kg");
        requirement.setExpectedArrivalAt(LocalDateTime.of(2026, 7, 23, 9, 30));
        requirement.setTargetWarehouseId(fixture.warehouse().getId());
        requirement.setStatus(new BigDecimal(received).signum() == 0
                ? SalesOrderSuppliedMaterialRequirementStatus.PENDING
                : SalesOrderSuppliedMaterialRequirementStatus.PARTIALLY_RECEIVED);
        entityManager.persist(requirement);
    }

    private MaterialBatch batch(
            Fixture fixture, String batchNumber, InventoryOwnership ownership) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("ID-" + batchNumber);
        batch.setFactoryId(fixture.order().getFactoryId());
        batch.setBatchNumber(batchNumber);
        batch.setMaterialTypeId(fixture.material().getId());
        batch.setReceiptDate(LocalDate.of(2026, 7, 22));
        batch.setExpireDate(LocalDate.of(2026, 7, 29));
        batch.setWarehouseId(fixture.warehouse().getId());
        batch.setReceiptQuantity(new BigDecimal("10.00"));
        batch.setQuantityUnit("kg");
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setCreatedBy(fixture.order().getCreatedBy());
        // H2 does not emulate PostgreSQL jsonb quoting for Hypersistence's JSON type.
        // This query-gate fixture does not exercise custom fields, so keep it null.
        batch.setCustomFields(null);
        batch.setOwnership(ownership);
        if (ownership == InventoryOwnership.CUSTOMER_OWNED) {
            batch.setOwnerCustomerId(fixture.customer().getId());
            batch.setSourceSalesOrderId(fixture.order().getId());
            batch.setSourceSalesOrderItemId(String.valueOf(fixture.item().getId()));
        }
        return batch;
    }

    private record Fixture(
            SalesOrder order,
            SalesOrderItem item,
            Customer customer,
            RawMaterialType material,
            FactoryWarehouse warehouse) {
    }
}
