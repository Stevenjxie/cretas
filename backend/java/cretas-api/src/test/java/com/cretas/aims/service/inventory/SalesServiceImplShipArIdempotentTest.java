package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the AR_INVOICE idempotency guard introduced in
 * {@code SalesServiceImpl.shipDelivery}
 * (fix commit: "skip duplicate auto receivable on shipment").
 *
 * <p>Fool-proof Rule 4: write operations must be idempotent — repeated shipment confirmation
 * must NOT create duplicate AR_INVOICE records.
 *
 * <p>Three cases covered:
 * <ol>
 *   <li>GUARD ACTIVE  — AR_INVOICE already exists → {@code arApService.recordReceivable} never called.</li>
 *   <li>GUARD PASSIVE — No AR yet                 → {@code arApService.recordReceivable} called exactly once.</li>
 *   <li>NULL GUARD    — {@code arApTransactionRepository} absent (legacy test context) → no NPE, fail-open.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SalesServiceImplShipArIdempotentTest {

    // --- ctor-injected required dependencies ---
    @Mock SalesOrderRepository           salesOrderRepository;
    @Mock SalesDeliveryRecordRepository  deliveryRecordRepository;
    @Mock SalesOrderItemRepository       salesOrderItemRepository;
    @Mock FinishedGoodsBatchRepository   finishedGoodsBatchRepository;
    @Mock CustomerRepository             customerRepository;
    @Mock ProductTypeRepository          productTypeRepository;
    @Mock ArApService                    arApService;
    @Mock ApplicationEventPublisher      eventPublisher;

    // --- optional @Autowired(required=false) field dependencies ---
    @Mock ArApTransactionRepository      arApTransactionRepository;
    @Mock WarehouseResolver              warehouseResolver;
    @Mock UserRepository                 userRepository;

    SalesServiceImpl salesService;

    private static final String FACTORY_ID  = "F006";
    private static final String ORDER_ID    = "SO-F006-0001";
    private static final String DELIVERY_ID = "DLV-F006-0001";
    private static final Long   USER_ID     = 1L;

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                salesOrderRepository,
                salesOrderItemRepository,
                deliveryRecordRepository,
                finishedGoodsBatchRepository,
                customerRepository,
                productTypeRepository,
                arApService,
                eventPublisher);

        // Inject optional @Autowired(required=false) fields via ReflectionTestUtils,
        // consistent with SalesServiceImplSalespersonTest pattern.
        ReflectionTestUtils.setField(salesService, "arApTransactionRepository", arApTransactionRepository);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(salesService, "userRepository", userRepository);
    }

    // ---------------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------------

    /** Minimal DRAFT delivery record with a sales-order link and no line items. */
    private SalesDeliveryRecord makeDelivery() {
        SalesDeliveryRecord d = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(d, "id", DELIVERY_ID);
        d.setDeliveryNumber("DN-0001");
        d.setFactoryId(FACTORY_ID);
        d.setSalesOrderId(ORDER_ID);
        d.setStatus(SalesDeliveryStatus.DRAFT);
        d.setItems(new ArrayList<>()); // empty — batchAllocationService null-guard skips check
        return d;
    }

    private SalesOrder makeOrder() {
        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", ORDER_ID);
        o.setFactoryId(FACTORY_ID);
        o.setCustomerId("CUST-001");
        o.setTotalAmount(new BigDecimal("9900.00"));
        o.setStatus(com.cretas.aims.entity.enums.SalesOrderStatus.CONFIRMED);
        o.setItems(new ArrayList<>());
        return o;
    }

    /**
     * Shared repository stubs required by {@code shipDelivery} regardless of AR guard outcome:
     * <ul>
     *   <li>{@code deliveryRecordRepository.findById} — load delivery</li>
     *   <li>{@code salesOrderRepository.findById} — called twice: updateOrderDeliveryStatus + AR block</li>
     *   <li>{@code salesOrderItemRepository.findBySalesOrderId} — called inside updateOrderDeliveryStatus</li>
     *   <li>{@code deliveryRecordRepository.save} / {@code salesOrderRepository.save} — persist results</li>
     * </ul>
     */
    private void stubCommonDeliveryDeps(SalesDeliveryRecord delivery, SalesOrder order) {
        when(deliveryRecordRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(Collections.emptyList());
        when(deliveryRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------------------
    // Test 1 — GUARD ACTIVE: AR already exists → no duplicate write.
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("shipDelivery: AR_INVOICE already exists → recordReceivable must NOT be called (idempotent guard)")
    void shipDelivery_whenArAlreadyExists_skipsRecordReceivable() {
        SalesDeliveryRecord delivery = makeDelivery();
        SalesOrder          order    = makeOrder();
        stubCommonDeliveryDeps(delivery, order);

        // Simulate: AR_INVOICE already present for this sales order.
        when(arApTransactionRepository.existsByFactoryIdAndSalesOrderIdAndTransactionType(
                eq(FACTORY_ID), eq(ORDER_ID), eq(ArApTransactionType.AR_INVOICE)))
                .thenReturn(true);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        // Critical: recordReceivable must NEVER be called when AR already exists.
        verify(arApService, never()).recordReceivable(any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------------------
    // Test 2 — GUARD PASSIVE: No AR yet → recordReceivable called exactly once.
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("shipDelivery: no prior AR_INVOICE → recordReceivable called exactly once (first shipment)")
    void shipDelivery_whenArNotYetExists_callsRecordReceivableOnce() {
        SalesDeliveryRecord delivery = makeDelivery();
        SalesOrder          order    = makeOrder();
        stubCommonDeliveryDeps(delivery, order);

        // Simulate: no AR_INVOICE yet.
        when(arApTransactionRepository.existsByFactoryIdAndSalesOrderIdAndTransactionType(
                eq(FACTORY_ID), eq(ORDER_ID), eq(ArApTransactionType.AR_INVOICE)))
                .thenReturn(false);

        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        // Critical: recordReceivable called exactly once for a fresh shipment.
        verify(arApService, times(1)).recordReceivable(
                eq(FACTORY_ID),
                eq("CUST-001"),
                eq(ORDER_ID),
                eq(new BigDecimal("9900.00")),
                any(),          // due-date: LocalDate.now() + 30 — not worth pinning
                eq(USER_ID),
                contains("DN-0001"));
    }

    // ---------------------------------------------------------------------------
    // Test 3 — NULL GUARD: repo absent in legacy context → fail-open, no NPE.
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("shipDelivery: arApTransactionRepository null (legacy) → no NPE, recordReceivable called (fail-open)")
    void shipDelivery_whenRepoIsNull_doesNotThrow_andCallsRecordReceivable() {
        // Override the setUp injection — simulate legacy context where optional bean is absent.
        ReflectionTestUtils.setField(salesService, "arApTransactionRepository", null);

        SalesDeliveryRecord delivery = makeDelivery();
        SalesOrder          order    = makeOrder();
        stubCommonDeliveryDeps(delivery, order);

        // Must not throw even though arApTransactionRepository is null.
        salesService.shipDelivery(FACTORY_ID, DELIVERY_ID, USER_ID);

        // Fail-open: when guard cannot check (repo null), still create AR to avoid silent data loss.
        verify(arApService, times(1)).recordReceivable(any(), any(), any(), any(), any(), any(), any());
    }
}
