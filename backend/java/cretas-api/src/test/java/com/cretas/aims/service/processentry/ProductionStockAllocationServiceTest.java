package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProductionStockShortageDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionInputAllocationRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.ProductionStockShortageException;
import com.cretas.aims.service.processentry.impl.ProductionStockAllocationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionStockAllocationServiceTest {

    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private ProductionInputAllocationRepository allocationRepository;
    @Mock
    private ProductionPlanRepository productionPlanRepository;
    @Mock
    private WarehouseResolver warehouseResolver;

    private ProductionStockAllocationService service;

    @BeforeEach
    void setUp() {
        service = new ProductionStockAllocationServiceImpl(
                materialBatchRepository, allocationRepository, productionPlanRepository, warehouseResolver);
        lenient().when(productionPlanRepository.findByIdAndFactoryId("PLAN-1", "F006"))
                .thenReturn(Optional.of(factorySuppliedPlan()));
    }

    @Test
    void allocatesWorkshopStockInFefoOrderAndSubtractsPendingFormalAllocations() {
        ProcessSheetRowRequest.MaterialInputTotal input = total("RAW-1", "6");
        MaterialBatch first = batch("B1", "RAW-1", "WKS-1", "3", LocalDate.of(2026, 7, 20));
        MaterialBatch second = batch("B2", "RAW-1", "WKS-1", "5", LocalDate.of(2026, 7, 25));

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "RAW-1", "WKS-1"))
                .thenReturn(List.of(first, second));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B1"))
                .thenReturn(new BigDecimal("1"));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B2"))
                .thenReturn(BigDecimal.ZERO);

        List<ProductionStockAllocationService.PlannedAllocation> result =
                service.plan("F006", "PLAN-1", List.of(input));

        assertThat(result).extracting(ProductionStockAllocationService.PlannedAllocation::materialBatchId)
                .containsExactly("B1", "B2");
        assertThat(result).extracting(ProductionStockAllocationService.PlannedAllocation::quantity)
                .containsExactly(new BigDecimal("2"), new BigDecimal("4"));
    }

    @Test
    void allocatesLegacyGramStockUsingKgReportingQuantity() {
        ProcessSheetRowRequest.MaterialInputTotal input = total("RAW-1", "2");
        MaterialBatch grams = batch("B1", "RAW-1", "WKS-1", "5000", LocalDate.of(2026, 7, 20));
        grams.setQuantityUnit("g");

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "RAW-1", "WKS-1"))
                .thenReturn(List.of(grams));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B1"))
                .thenReturn(new BigDecimal("1"));

        List<ProductionStockAllocationService.PlannedAllocation> result =
                service.plan("F006", "PLAN-1", List.of(input));

        assertThat(result).singleElement().satisfies(allocation -> {
            assertThat(allocation.quantity()).isEqualByComparingTo("2");
            assertThat(allocation.unit()).isEqualTo("kg");
        });
    }

    @Test
    void convertsWorkflowGramReportingQuantityToCanonicalKgAllocation() {
        ProcessSheetRowRequest.MaterialInputTotal input = total("RAW-1", "2000");
        input.setUnit("g");
        MaterialBatch stock = batch("B1", "RAW-1", "WKS-1", "5", LocalDate.of(2026, 7, 20));

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "RAW-1", "WKS-1"))
                .thenReturn(List.of(stock));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B1"))
                .thenReturn(BigDecimal.ZERO);

        assertThat(service.plan("F006", "PLAN-1", List.of(input)))
                .singleElement()
                .satisfies(allocation -> {
                    assertThat(allocation.quantity()).isEqualByComparingTo("2");
                    assertThat(allocation.unit()).isEqualTo("kg");
                });
    }

    @Test
    void allocatesPackagingInNativeUnitsWithFefoCostAndLineageMetadata() {
        MaterialBatch first = batch("BOX-1", "PKG-BOX", "WKS-1", "6", LocalDate.of(2026, 8, 1));
        first.setQuantityUnit("盒");
        first.setBatchNumber("PKG-BOX-B1");
        first.setUnitPrice(new BigDecimal("0.40"));
        MaterialBatch second = batch("BOX-2", "PKG-BOX", "WKS-1", "10", LocalDate.of(2026, 9, 1));
        second.setQuantityUnit("box");
        second.setBatchNumber("PKG-BOX-B2");
        second.setUnitPrice(new BigDecimal("0.50"));

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "PKG-BOX", "WKS-1"))
                .thenReturn(List.of(first, second));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "BOX-1"))
                .thenReturn(BigDecimal.ONE);
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "BOX-2"))
                .thenReturn(BigDecimal.ZERO);

        List<ProductionStockAllocationService.PlannedAllocation> result = service.planNative(
                "F006",
                "PLAN-1",
                List.of(new ProductionStockAllocationService.AutomaticRequirement(
                        "PKG-BOX", "800g包装盒", new BigDecimal("10"), "盒", "PACKAGING")));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ProductionStockAllocationService.PlannedAllocation::materialBatchId)
                .containsExactly("BOX-1", "BOX-2");
        assertThat(result).extracting(ProductionStockAllocationService.PlannedAllocation::quantity)
                .containsExactly(new BigDecimal("5"), new BigDecimal("5"));
        assertThat(result).allSatisfy(allocation -> {
            assertThat(allocation.unit()).isEqualTo("box");
            assertThat(allocation.sourceType()).isEqualTo("PACKAGING");
            assertThat(allocation.automatic()).isTrue();
        });
        assertThat(result).extracting(ProductionStockAllocationService.PlannedAllocation::totalCost)
                .containsExactly(new BigDecimal("2.00"), new BigDecimal("2.50"));
        assertThat(service.toRawInputs(result)).allSatisfy(input -> {
            assertThat(input.getSourceType()).isEqualTo("PACKAGING");
            assertThat(input.getUnit()).isEqualTo("box");
            assertThat(input.getAutomatic()).isTrue();
        });
    }

    @Test
    void allocatesSeasoningFromLegacyGramBatchWithKgReservationAndExactCost() {
        MaterialBatch seasoning = batch(
                "SEASONING-G-1", "SEASONING-1", "WKS-1", "1500", LocalDate.of(2026, 8, 1));
        seasoning.setQuantityUnit("g");
        seasoning.setBatchNumber("SEASONING-20260724-G");
        seasoning.setUnitPrice(new BigDecimal("0.02"));

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "SEASONING-1", "WKS-1"))
                .thenReturn(List.of(seasoning));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "SEASONING-G-1"))
                .thenReturn(BigDecimal.ZERO);

        assertThat(service.planNative(
                "F006",
                "PLAN-1",
                List.of(new ProductionStockAllocationService.AutomaticRequirement(
                        "SEASONING-1", "复合调料", new BigDecimal("0.75"), "kg", "SEASONING"))))
                .singleElement()
                .satisfies(allocation -> {
                    assertThat(allocation.quantity()).isEqualByComparingTo("0.75");
                    assertThat(allocation.unit()).isEqualTo("kg");
                    assertThat(allocation.unitPrice()).isEqualByComparingTo("20");
                    assertThat(allocation.totalCost()).isEqualByComparingTo("15");
                    assertThat(allocation.sourceType()).isEqualTo("SEASONING");
                });
    }

    @Test
    void nativePackagingShortageReportsTheRequestedUnitWithoutMassConversion() {
        MaterialBatch only = batch("CASE-1", "PKG-CASE", "WKS-1", "1", LocalDate.of(2026, 8, 1));
        only.setQuantityUnit("箱");

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "PKG-CASE", "WKS-1"))
                .thenReturn(List.of(only));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "CASE-1"))
                .thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.planNative(
                "F006",
                "PLAN-1",
                List.of(new ProductionStockAllocationService.AutomaticRequirement(
                        "PKG-CASE", "外箱", new BigDecimal("1.25"), "箱", "PACKAGING"))))
                .isInstanceOfSatisfying(ProductionStockShortageException.class, error -> {
                    assertThat(error.getShortage().getItems()).singleElement().satisfies(item -> {
                        assertThat(item.getMaterialName()).isEqualTo("外箱");
                        assertThat(item.getSourceType()).isEqualTo("PACKAGING");
                        assertThat(item.getRequired()).isEqualByComparingTo("1.25");
                        assertThat(item.getAvailable()).isEqualByComparingTo("1");
                        assertThat(item.getShortage()).isEqualByComparingTo("0.25");
                    });
                    assertThat(error.getMessage())
                            .contains("1.25case", "短缺明细：外箱（包材）", "缺少 0.25case");
                });
    }

    @Test
    void mixedPackagingShortageListsEachMaterialWithoutFakeMixedTotals() {
        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "PKG-BOX", "WKS-1"))
                .thenReturn(List.of());
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "PKG-CASE", "WKS-1"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.planNative(
                "F006",
                "PLAN-1",
                List.of(
                        new ProductionStockAllocationService.AutomaticRequirement(
                                "PKG-BOX", "800g包装盒", new BigDecimal("10"), "盒", "PACKAGING"),
                        new ProductionStockAllocationService.AutomaticRequirement(
                                "PKG-CASE", "外箱", new BigDecimal("1.25"), "箱", "PACKAGING"))))
                .isInstanceOfSatisfying(ProductionStockShortageException.class, error -> {
                    assertThat(error.getMessage())
                            .doesNotContain("0mixed")
                            .contains(
                                    "800g包装盒（包材）：需要 10box，可用 0box，缺少 10box",
                                    "外箱（包材）：需要 1.25case，可用 0case，缺少 1.25case");
                    assertThat(error.getShortage().getItems())
                            .extracting(
                                    ProductionStockShortageDTO.Item::getMaterialName,
                                    ProductionStockShortageDTO.Item::getSourceType)
                            .containsExactly(
                                    tuple("800g包装盒", "PACKAGING"),
                                    tuple("外箱", "PACKAGING"));
                });
    }

    @Test
    void rejectsFormalSubmissionWithStructuredShortageAndExactOperatorMessage() {
        ProcessSheetRowRequest.MaterialInputTotal input = total("RAW-1", "10");
        MaterialBatch only = batch("B1", "RAW-1", "WKS-1", "7", LocalDate.of(2026, 7, 20));

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "RAW-1", "WKS-1"))
                .thenReturn(List.of(only));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B1"))
                .thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.plan("F006", "PLAN-1", List.of(input)))
                .isInstanceOfSatisfying(ProductionStockShortageException.class, error -> {
                    assertThat(error.getMessage())
                            .isEqualTo("当前只能保存草稿，生产库中投料量不足。需要 10kg，可用 7kg，缺少 3kg，请联系仓管补料");
                    assertThat(error.getShortage().getRequired()).isEqualByComparingTo("10");
                    assertThat(error.getShortage().getAvailable()).isEqualByComparingTo("7");
                    assertThat(error.getShortage().getShortage()).isEqualByComparingTo("3");
                    assertThat(error.getShortage().getItems()).singleElement().satisfies(item -> {
                        assertThat(item.getMaterialTypeId()).isEqualTo("RAW-1");
                        assertThat(item.getRequired()).isEqualByComparingTo("10");
                        assertThat(item.getAvailable()).isEqualByComparingTo("7");
                        assertThat(item.getShortage()).isEqualByComparingTo("3");
                    });
                });
    }

    @Test
    void refusesToGuessWhenWorkshopWarehouseCannotBeResolved() {
        ProcessSheetRowRequest.MaterialInputTotal input = total("RAW-1", "1");
        when(warehouseResolver.resolveWorkshopId("F006"))
                .thenThrow(new BusinessException(500, "missing workshop")
                        .withCode("WORKSHOP_WAREHOUSE_NOT_CONFIGURED"));

        assertThatThrownBy(() -> service.plan("F006", "PLAN-1", List.of(input)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("WORKSHOP_WAREHOUSE_NOT_CONFIGURED"));
    }

    @Test
    void legacyExplicitBatchIsLockedAndPendingAllocationPreventsDoubleUse() {
        MaterialBatch batch = batch("B1", "RAW-1", "WKS-1", "7", LocalDate.of(2026, 7, 20));
        ProcessSheetRowRequest.RawInput input = new ProcessSheetRowRequest.RawInput();
        input.setMaterialBatchId("B1");
        input.setSkuId("RAW-1");
        input.setQuantity(new BigDecimal("5"));

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("B1", "F006"))
                .thenReturn(java.util.Optional.of(batch));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B1"))
                .thenReturn(new BigDecimal("3"));

        assertThatThrownBy(() -> service.planExplicit("F006", "PLAN-1", List.of(input)))
                .isInstanceOfSatisfying(ProductionStockShortageException.class, error -> {
                    assertThat(error.getShortage().getRequired()).isEqualByComparingTo("5");
                    assertThat(error.getShortage().getAvailable()).isEqualByComparingTo("4");
                    assertThat(error.getShortage().getShortage()).isEqualByComparingTo("1");
                });
    }

    @Test
    void legacyExplicitGramBatchIsComparedAndReservedInKg() {
        MaterialBatch grams = batch("B1", "RAW-1", "WKS-1", "5000", LocalDate.of(2026, 7, 20));
        grams.setQuantityUnit("g");
        ProcessSheetRowRequest.RawInput input = new ProcessSheetRowRequest.RawInput();
        input.setMaterialBatchId("B1");
        input.setSkuId("RAW-1");
        input.setQuantity(new BigDecimal("2"));

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("B1", "F006"))
                .thenReturn(java.util.Optional.of(grams));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B1"))
                .thenReturn(new BigDecimal("1"));

        assertThat(service.planExplicit("F006", "PLAN-1", List.of(input)))
                .singleElement()
                .satisfies(allocation -> {
                    assertThat(allocation.quantity()).isEqualByComparingTo("2");
                    assertThat(allocation.unit()).isEqualTo("kg");
                });
    }

    @Test
    void customerSuppliedPlanAllocatesOnlySameCustomerAndSalesOrderStock() {
        ProductionPlan plan = customerSuppliedPlan();
        MaterialBatch customerBatch = batch(
                "CB1", "RAW-1", "WKS-1", "5", LocalDate.of(2026, 7, 20));
        customerBatch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        customerBatch.setOwnerCustomerId("CUS-1");
        customerBatch.setSourceSalesOrderId("SO-1");
        customerBatch.setSourceSalesOrderItemId("ITEM-1");

        when(productionPlanRepository.findByIdAndFactoryId("PLAN-1", "F006"))
                .thenReturn(Optional.of(plan));
        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableCustomerSuppliedBatchesFEFOByWarehouseForUpdate(
                "F006", "RAW-1", "WKS-1", "CUS-1", "SO-1"))
                .thenReturn(List.of(customerBatch));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "CB1"))
                .thenReturn(BigDecimal.ZERO);

        assertThat(service.plan("F006", "PLAN-1", List.of(total("RAW-1", "3"))))
                .singleElement()
                .satisfies(allocation -> {
                    assertThat(allocation.materialBatchId()).isEqualTo("CB1");
                    assertThat(allocation.quantity()).isEqualByComparingTo("3");
                });
    }

    @Test
    void explicitCustomerBatchFromAnotherCustomerOrOrderIsRejected() {
        ProductionPlan plan = customerSuppliedPlan();
        MaterialBatch wrongCustomer = batch(
                "CB1", "RAW-1", "WKS-1", "5", LocalDate.of(2026, 7, 20));
        wrongCustomer.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        wrongCustomer.setOwnerCustomerId("CUS-OTHER");
        wrongCustomer.setSourceSalesOrderId("SO-OTHER");

        ProcessSheetRowRequest.RawInput input = new ProcessSheetRowRequest.RawInput();
        input.setMaterialBatchId("CB1");
        input.setSkuId("RAW-1");
        input.setQuantity(BigDecimal.ONE);

        when(productionPlanRepository.findByIdAndFactoryId("PLAN-1", "F006"))
                .thenReturn(Optional.of(plan));
        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("CB1", "F006"))
                .thenReturn(Optional.of(wrongCustomer));

        assertThatThrownBy(() -> service.planExplicit(
                "F006", "PLAN-1", List.of(input)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo("CUSTOMER_SUPPLIED_MATERIAL_SCOPE_MISMATCH"));
    }

    @Test
    void factorySuppliedPlanCannotExplicitlyConsumeCustomerOwnedStock() {
        MaterialBatch customerBatch = batch(
                "CB1", "RAW-1", "WKS-1", "5", LocalDate.of(2026, 7, 20));
        customerBatch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        customerBatch.setOwnerCustomerId("CUS-1");
        customerBatch.setSourceSalesOrderId("SO-1");

        ProcessSheetRowRequest.RawInput input = new ProcessSheetRowRequest.RawInput();
        input.setMaterialBatchId("CB1");
        input.setSkuId("RAW-1");
        input.setQuantity(BigDecimal.ONE);

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("CB1", "F006"))
                .thenReturn(Optional.of(customerBatch));

        assertThatThrownBy(() -> service.planExplicit(
                "F006", "PLAN-1", List.of(input)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo("CUSTOMER_OWNED_MATERIAL_FORBIDDEN"));
    }

    @Test
    void allocationFailsClosedWhenPlanIdentityCannotBeResolved() {
        when(productionPlanRepository.findByIdAndFactoryId("MISSING", "F006"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.plan(
                "F006", "MISSING", List.of(total("RAW-1", "1"))))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("PRODUCTION_PLAN_NOT_FOUND"));
    }

    private static ProductionPlan factorySuppliedPlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-1");
        plan.setFactoryId("F006");
        plan.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        return plan;
    }

    private static ProductionPlan customerSuppliedPlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-1");
        plan.setFactoryId("F006");
        plan.setCustomerId("CUS-1");
        plan.setSourceOrderId("SO-1");
        plan.setSourceOrderItemId("ITEM-1");
        plan.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        return plan;
    }

    @Test
    void countedInputAllocatesInItsOwnUnitInsteadOfBeingForcedToKg() {
        // 整鸡按「只」计 —— 以前 validateInput 直接 400 拒掉, 报工根本提不了
        ProcessSheetRowRequest.MaterialInputTotal input = countedTotal("RAW-CHICKEN", "201");
        MaterialBatch first = countedBatch("B1", "RAW-CHICKEN", "WKS-1", "120");
        MaterialBatch second = countedBatch("B2", "RAW-CHICKEN", "WKS-1", "200");

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "RAW-CHICKEN", "WKS-1"))
                .thenReturn(List.of(first, second));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B1"))
                .thenReturn(BigDecimal.ZERO);
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B2"))
                .thenReturn(BigDecimal.ZERO);

        List<ProductionStockAllocationService.PlannedAllocation> result =
                service.plan("F006", "PLAN-1", List.of(input));

        assertThat(result).extracting(ProductionStockAllocationService.PlannedAllocation::quantity)
                .containsExactly(new BigDecimal("120"), new BigDecimal("81"));
        // 数量不被折算; 本服务的 canonicalNativeUnit 对未登记单位原样返回,
        // 所以分配记录里就是用户配的「只」而不是归一后的 pcs
        assertThat(result).extracting(ProductionStockAllocationService.PlannedAllocation::unit)
                .containsOnly("只");
    }

    @Test
    void countedInputSkipsBatchesStoredInADifferentUnit() {
        // 非质量单位不停跨单位折算: kg 库存不能拿来充「只」的投料
        ProcessSheetRowRequest.MaterialInputTotal input = countedTotal("RAW-CHICKEN", "5");
        MaterialBatch massBatch = batch("B-KG", "RAW-CHICKEN", "WKS-1", "100", LocalDate.of(2026, 7, 20));

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "RAW-CHICKEN", "WKS-1"))
                .thenReturn(List.of(massBatch));

        assertThatThrownBy(() -> service.plan("F006", "PLAN-1", List.of(input)))
                .isInstanceOf(ProductionStockShortageException.class)
                .extracting(error -> ((ProductionStockShortageException) error).getShortage())
                .satisfies(shortage -> {
                    ProductionStockShortageDTO dto = (ProductionStockShortageDTO) shortage;
                    assertThat(dto.getUnit()).isEqualTo("只");
                    assertThat(dto.getShortage()).isEqualByComparingTo("5");
                });
    }

    @Test
    void massInputStillConvertsAndStillRejectsNonMassBatches() {
        // 原意图不变: 质量单位继续自动折算
        ProcessSheetRowRequest.MaterialInputTotal input = total("RAW-1", "2");
        MaterialBatch gramBatch = batch("B1", "RAW-1", "WKS-1", "5000", LocalDate.of(2026, 7, 20));
        gramBatch.setQuantityUnit("g");

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "RAW-1", "WKS-1"))
                .thenReturn(List.of(gramBatch));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B1"))
                .thenReturn(BigDecimal.ZERO);

        List<ProductionStockAllocationService.PlannedAllocation> result =
                service.plan("F006", "PLAN-1", List.of(input));

        assertThat(result).extracting(ProductionStockAllocationService.PlannedAllocation::unit)
                .containsOnly("kg");
        assertThat(result).extracting(ProductionStockAllocationService.PlannedAllocation::quantity)
                .containsExactly(new BigDecimal("2"));
    }

    private static ProcessSheetRowRequest.MaterialInputTotal countedTotal(
            String materialTypeId, String quantity) {
        ProcessSheetRowRequest.MaterialInputTotal input = new ProcessSheetRowRequest.MaterialInputTotal();
        input.setMaterialTypeId(materialTypeId);
        input.setQuantity(new BigDecimal(quantity));
        input.setUnit("只");
        return input;
    }

    private static MaterialBatch countedBatch(
            String id, String materialTypeId, String warehouseId, String quantity) {
        MaterialBatch batch = batch(id, materialTypeId, warehouseId, quantity, LocalDate.of(2026, 7, 20));
        batch.setQuantityUnit("只");
        return batch;
    }

    private static ProcessSheetRowRequest.MaterialInputTotal total(String materialTypeId, String quantity) {
        ProcessSheetRowRequest.MaterialInputTotal input = new ProcessSheetRowRequest.MaterialInputTotal();
        input.setMaterialTypeId(materialTypeId);
        input.setQuantity(new BigDecimal(quantity));
        input.setUnit("kg");
        return input;
    }

    private static MaterialBatch batch(
            String id,
            String materialTypeId,
            String warehouseId,
            String quantity,
            LocalDate expireDate) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId("F006");
        batch.setMaterialTypeId(materialTypeId);
        batch.setWarehouseId(warehouseId);
        batch.setReceiptQuantity(new BigDecimal(quantity));
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit("kg");
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setExpireDate(expireDate);
        return batch;
    }
}
