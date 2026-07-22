package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.inventory.UpdateSalesOrderRequest;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Sales order source warehouse persistence contract")
class SalesServiceImplSourceWarehouseContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String ORDER_ID = "SO-SOURCE-ROUNDTRIP";
    private static final String PRODUCT_ID = "42321d1c-fdc3-457b-b78d-a781df12050d";

    @Mock SalesOrderRepository salesOrderRepository;
    @Mock SalesOrderItemRepository salesOrderItemRepository;
    @Mock SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock CustomerRepository customerRepository;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock ArApService arApService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock UserRepository userRepository;
    @Mock WarehouseResolver warehouseResolver;
    @Mock FactoryWarehouseRepository factoryWarehouseRepository;
    @Mock EntityManager entityManager;
    @Mock Query nativeQuery;

    private SalesServiceImpl service;
    private final List<SalesOrderItem> persistedItems = new ArrayList<>();

    @BeforeEach
    void setUp() {
        persistedItems.clear();
        service = new SalesServiceImpl(
                salesOrderRepository, salesOrderItemRepository, deliveryRecordRepository,
                finishedGoodsBatchRepository, customerRepository, productTypeRepository,
                arApService, eventPublisher);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "factoryWarehouseRepository", factoryWarehouseRepository);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.getSingleResult()).thenReturn(1L);
        when(salesOrderRepository.findMaxOrderNumberByPrefix(anyString(), anyString())).thenReturn(null);
        when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(invocation -> {
            SalesOrder order = invocation.getArgument(0);
            if (order.getId() == null) {
                ReflectionTestUtils.setField(order, "id", ORDER_ID);
            }
            return order;
        });
        when(salesOrderItemRepository.saveAll(anyList())).thenAnswer(invocation -> {
            persistedItems.clear();
            persistedItems.addAll(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        Customer customer = new Customer();
        customer.setId("CUSTOMER-1");
        customer.setFactoryId(FACTORY_ID);
        customer.setName("测试客户");
        when(customerRepository.findByIdAndFactoryId(customer.getId(), FACTORY_ID))
                .thenReturn(Optional.of(customer));

        ProductType product = new ProductType();
        product.setId(PRODUCT_ID);
        product.setFactoryId(FACTORY_ID);
        product.setName("黄油鸡-成品800g");
        product.setUnit("box");
        when(productTypeRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, "WH-LOG"))
                .thenReturn(Optional.of(warehouse("WH-LOG", "物流仓")));
    }

    @Test
    @DisplayName("create then GET retains canonical box, WH-LOG and no packaging spec")
    void createAndGetRoundTripRetainsSourceWarehouseAndUnitContract() {
        CreateSalesOrderRequest request = new CreateSalesOrderRequest();
        request.setCustomerId("CUSTOMER-1");
        request.setProcessingMode(com.cretas.aims.entity.enums.SalesProcessingMode.STANDARD_SALE);
        request.setMaterialSupplyMode(com.cretas.aims.entity.enums.MaterialSupplyMode.FACTORY_SUPPLIED);
        CreateSalesOrderRequest.SalesOrderItemDTO line = new CreateSalesOrderRequest.SalesOrderItemDTO();
        line.setProductTypeId(PRODUCT_ID);
        line.setQuantity(new BigDecimal("5"));
        line.setUnit("box");
        line.setUnitPrice(new BigDecimal("20"));
        line.setTaxRate(new BigDecimal("13"));
        line.setBoxQuantity(new BigDecimal("0.63"));
        line.setPackagingSpecId(null);
        line.setSourceWarehouseCode("WH-LOG");
        request.setItems(List.of(line));

        SalesOrder created = service.createSalesOrder(FACTORY_ID, request, 1L);
        assertThat(created.getProcessingMode())
                .isEqualTo(com.cretas.aims.entity.enums.SalesProcessingMode.STANDARD_SALE);
        assertThat(created.getMaterialSupplyMode())
                .isEqualTo(com.cretas.aims.entity.enums.MaterialSupplyMode.FACTORY_SUPPLIED);
        assertThat(persistedItems).singleElement().satisfies(saved -> {
            assertThat(saved.getUnit()).isEqualTo("box");
            assertThat(saved.getSourceWarehouseCode()).isEqualTo("WH-LOG");
            assertThat(saved.getPackagingSpecId()).isNull();
            assertThat(saved.getBoxQuantity()).isEqualByComparingTo("0.63");
            assertThat(saved.getProcessingMode())
                    .isEqualTo(com.cretas.aims.entity.enums.SalesProcessingMode.STANDARD_SALE);
            assertThat(saved.getMaterialSupplyMode())
                    .isEqualTo(com.cretas.aims.entity.enums.MaterialSupplyMode.FACTORY_SUPPLIED);
        });

        created.setItems(new ArrayList<>(persistedItems));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(created));
        SalesOrder detail = service.getSalesOrderById(FACTORY_ID, ORDER_ID);
        assertThat(detail.getItems()).singleElement().satisfies(saved -> {
            assertThat(saved.getUnit()).isEqualTo("box");
            assertThat(saved.getSourceWarehouseCode()).isEqualTo("WH-LOG");
            assertThat(saved.getPackagingSpecId()).isNull();
            assertThat(saved.getBoxQuantity()).isEqualByComparingTo("0.63");
        });
    }

    @Test
    @DisplayName("new orders fail closed when the supply contract is missing or mixed by line")
    void createRejectsMissingOrMixedSupplyContractBeforeWrite() {
        CreateSalesOrderRequest missing = baseSupplyRequest();
        assertThatThrownBy(() -> service.createSalesOrder(FACTORY_ID, missing, 1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo("SALES_ORDER_SUPPLY_CONTRACT_REQUIRED"));

        CreateSalesOrderRequest mixed = baseSupplyRequest();
        mixed.setProcessingMode(com.cretas.aims.entity.enums.SalesProcessingMode.TOLL_PROCESSING);
        mixed.setMaterialSupplyMode(com.cretas.aims.entity.enums.MaterialSupplyMode.CUSTOMER_SUPPLIED);
        mixed.getItems().get(0).setMaterialSupplyMode(
                com.cretas.aims.entity.enums.MaterialSupplyMode.FACTORY_SUPPLIED);
        assertThatThrownBy(() -> service.createSalesOrder(FACTORY_ID, mixed, 1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo("SALES_ORDER_MIXED_SUPPLY_MODE_UNSUPPORTED"));

        verify(salesOrderRepository, never()).save(any(SalesOrder.class));
    }

    @Test
    @DisplayName("standard sale cannot silently opt into customer-owned material")
    void createRejectsCustomerSuppliedStandardSale() {
        CreateSalesOrderRequest request = baseSupplyRequest();
        request.setProcessingMode(com.cretas.aims.entity.enums.SalesProcessingMode.STANDARD_SALE);
        request.setMaterialSupplyMode(com.cretas.aims.entity.enums.MaterialSupplyMode.CUSTOMER_SUPPLIED);

        assertThatThrownBy(() -> service.createSalesOrder(FACTORY_ID, request, 1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo("SALES_ORDER_SUPPLY_CONTRACT_INVALID"));
    }

    @Test
    @DisplayName("editing a legacy draft writes one explicit header contract and aligned line snapshots")
    void updateLegacyDraftPersistsAlignedSupplySnapshots() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-LEGACY-DRAFT");
        order.setFactoryId(FACTORY_ID);
        order.setStatus(SalesOrderStatus.DRAFT);
        order.setTotalAmount(BigDecimal.ONE);
        SalesOrderItem legacyLine = orderItem("WH-LOG");
        legacyLine.setSalesOrderId(order.getId());
        when(salesOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(order.getId()))
                .thenReturn(List.of(legacyLine));

        UpdateSalesOrderRequest request = new UpdateSalesOrderRequest();
        request.setProcessingMode(com.cretas.aims.entity.enums.SalesProcessingMode.TOLL_PROCESSING);
        request.setMaterialSupplyMode(com.cretas.aims.entity.enums.MaterialSupplyMode.CUSTOMER_SUPPLIED);

        SalesOrder updated = service.updateSalesOrder(FACTORY_ID, order.getId(), request);

        assertThat(updated.getProcessingMode())
                .isEqualTo(com.cretas.aims.entity.enums.SalesProcessingMode.TOLL_PROCESSING);
        assertThat(updated.getMaterialSupplyMode())
                .isEqualTo(com.cretas.aims.entity.enums.MaterialSupplyMode.CUSTOMER_SUPPLIED);
        assertThat(legacyLine.getProcessingMode()).isEqualTo(updated.getProcessingMode());
        assertThat(legacyLine.getMaterialSupplyMode()).isEqualTo(updated.getMaterialSupplyMode());
        verify(salesOrderItemRepository).saveAll(List.of(legacyLine));
    }

    @Test
    @DisplayName("copy preserves valid header and line snapshots and rejects legacy-null sources")
    void copyPreservesSupplyContractAndRejectsLegacyNullSource() {
        SalesOrder source = new SalesOrder();
        source.setId("SO-SOURCE");
        source.setFactoryId(FACTORY_ID);
        source.setOrderNumber("SO-20260721-0001");
        source.setCustomerId("CUSTOMER-1");
        source.setOrderDate(java.time.LocalDate.now());
        source.setProcessingMode(com.cretas.aims.entity.enums.SalesProcessingMode.TOLL_PROCESSING);
        source.setMaterialSupplyMode(com.cretas.aims.entity.enums.MaterialSupplyMode.CUSTOMER_SUPPLIED);
        SalesOrderItem sourceLine = orderItem("WH-LOG");
        sourceLine.setSalesOrderId(source.getId());
        sourceLine.setProcessingMode(source.getProcessingMode());
        sourceLine.setMaterialSupplyMode(source.getMaterialSupplyMode());
        source.setItems(new ArrayList<>(List.of(sourceLine)));
        when(salesOrderRepository.findById(source.getId())).thenReturn(Optional.of(source));

        SalesOrder copied = service.copySalesOrder(FACTORY_ID, source.getId(), 2L);

        assertThat(copied.getProcessingMode()).isEqualTo(source.getProcessingMode());
        assertThat(copied.getMaterialSupplyMode()).isEqualTo(source.getMaterialSupplyMode());
        assertThat(persistedItems).singleElement().satisfies(item -> {
            assertThat(item.getProcessingMode()).isEqualTo(source.getProcessingMode());
            assertThat(item.getMaterialSupplyMode()).isEqualTo(source.getMaterialSupplyMode());
        });

        SalesOrder legacy = new SalesOrder();
        legacy.setId("SO-LEGACY");
        legacy.setFactoryId(FACTORY_ID);
        legacy.setItems(new ArrayList<>());
        when(salesOrderRepository.findById(legacy.getId())).thenReturn(Optional.of(legacy));
        assertThatThrownBy(() -> service.copySalesOrder(FACTORY_ID, legacy.getId(), 2L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo("SALES_ORDER_SUPPLY_CONTRACT_REQUIRED"));
    }

    private CreateSalesOrderRequest baseSupplyRequest() {
        CreateSalesOrderRequest request = new CreateSalesOrderRequest();
        request.setCustomerId("CUSTOMER-1");
        CreateSalesOrderRequest.SalesOrderItemDTO line = new CreateSalesOrderRequest.SalesOrderItemDTO();
        line.setProductTypeId(PRODUCT_ID);
        line.setQuantity(BigDecimal.ONE);
        line.setUnit("box");
        line.setUnitPrice(BigDecimal.ONE);
        request.setItems(List.of(line));
        return request;
    }

    @Test
    @DisplayName("historical bridge fills only a missing value and same-value replay is a no-op")
    void repairMissingSourceWarehouseIsIdempotent() {
        SalesOrder order = approvedOrder();
        SalesOrderItem item = orderItem(null);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findById(726L)).thenReturn(Optional.of(item));
        when(salesOrderItemRepository.saveAndFlush(item)).thenReturn(item);

        SalesOrderItem first = service.repairMissingSourceWarehouse(FACTORY_ID, ORDER_ID, 726L, " WH-LOG ");
        SalesOrderItem replay = service.repairMissingSourceWarehouse(FACTORY_ID, ORDER_ID, 726L, "WH-LOG");

        assertThat(first.getSourceWarehouseCode()).isEqualTo("WH-LOG");
        assertThat(replay.getSourceWarehouseCode()).isEqualTo("WH-LOG");
        verify(salesOrderItemRepository).saveAndFlush(item);
        verify(salesOrderRepository, never()).save(order);
    }

    @Test
    @DisplayName("invalid warehouse code fails closed and existing different source is never overwritten")
    void repairRejectsInvalidOrConflictingSourceWarehouse() {
        SalesOrder order = approvedOrder();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        SalesOrderItem missing = orderItem(null);
        when(salesOrderItemRepository.findById(726L)).thenReturn(Optional.of(missing));
        assertThatThrownBy(() -> service.repairMissingSourceWarehouse(
                FACTORY_ID, ORDER_ID, 726L, "WH-NOT-FOUND"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getErrorCode()).isEqualTo("SOURCE_WAREHOUSE_INVALID");
                });

        SalesOrderItem conflicting = orderItem("WH-FG");
        when(salesOrderItemRepository.findById(726L)).thenReturn(Optional.of(conflicting));
        assertThatThrownBy(() -> service.repairMissingSourceWarehouse(
                FACTORY_ID, ORDER_ID, 726L, "WH-LOG"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getErrorCode()).isEqualTo("SOURCE_WAREHOUSE_REPAIR_CONFLICT");
                });
        verify(salesOrderItemRepository, never()).saveAndFlush(missing);
        verify(salesOrderItemRepository, never()).saveAndFlush(conflicting);
    }

    private SalesOrder approvedOrder() {
        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY_ID);
        order.setOrderNumber("SO-20260720-0001");
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        order.setVersion(3L);
        return order;
    }

    private SalesOrderItem orderItem(String sourceWarehouseCode) {
        SalesOrderItem item = new SalesOrderItem();
        item.setId(726L);
        item.setSalesOrderId(ORDER_ID);
        item.setProductTypeId(PRODUCT_ID);
        item.setQuantity(new BigDecimal("5"));
        item.setUnit("box");
        item.setUnitPrice(new BigDecimal("20"));
        item.setTaxRate(new BigDecimal("13"));
        item.setBoxQuantity(new BigDecimal("0.63"));
        item.setSourceWarehouseCode(sourceWarehouseCode);
        return item;
    }

    private FactoryWarehouse warehouse(String code, String name) {
        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setId("WAREHOUSE-" + code);
        warehouse.setFactoryId(FACTORY_ID);
        warehouse.setCode(code);
        warehouse.setName(name);
        warehouse.setType(FactoryWarehouse.WarehouseType.FINISHED);
        warehouse.setIsActive(true);
        return warehouse;
    }
}
