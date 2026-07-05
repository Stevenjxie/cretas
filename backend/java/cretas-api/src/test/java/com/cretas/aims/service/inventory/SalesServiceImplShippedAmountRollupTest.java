package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Bug fix (F006 live sales audit, 2026-07): SO detail header "已发货金额" was stuck at
 * ¥0.00 forever because {@code SalesOrder.actualShippedAmount} was defined on the entity
 * but never written by any service (confirmed via repo-wide grep — only comment mentions,
 * no {@code setActualShippedAmount} call anywhere). This test locks in the fix in the
 * private {@code SalesServiceImpl.updateOrderDeliveryStatus} (invoked from
 * {@code shipDelivery}/{@code warehouseConfirmDelivery}), which now rolls up
 * Σ(deliveredQuantity × discounted unitPrice) across order items and persists it onto
 * the order.
 *
 * <p>Tests invoke the private method directly via reflection rather than going through
 * the full {@code shipDelivery} flow, to isolate the rollup logic from FIFO inventory
 * deduction (which requires mocking FinishedGoodsBatch stock — out of scope here; that
 * path is already covered by other shipDelivery tests).
 */
@ExtendWith(MockitoExtension.class)
class SalesServiceImplShippedAmountRollupTest {

    @Mock SalesOrderRepository           salesOrderRepository;
    @Mock SalesDeliveryRecordRepository  deliveryRecordRepository;
    @Mock SalesOrderItemRepository       salesOrderItemRepository;
    @Mock FinishedGoodsBatchRepository   finishedGoodsBatchRepository;
    @Mock CustomerRepository             customerRepository;
    @Mock ProductTypeRepository          productTypeRepository;
    @Mock ArApService                    arApService;
    @Mock ApplicationEventPublisher      eventPublisher;

    @Mock ArApTransactionRepository      arApTransactionRepository;
    @Mock WarehouseResolver              warehouseResolver;
    @Mock UserRepository                 userRepository;

    SalesServiceImpl salesService;

    private static final String FACTORY_ID  = "F006";
    private static final String ORDER_ID    = "SO-F006-0010";
    private static final String DELIVERY_ID = "DLV-F006-0010";

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

        ReflectionTestUtils.setField(salesService, "arApTransactionRepository", arApTransactionRepository);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(salesService, "userRepository", userRepository);
    }

    private SalesOrderItem makeOrderItem(String productTypeId, BigDecimal quantity,
                                          BigDecimal unitPrice, BigDecimal discountRate,
                                          BigDecimal deliveredQuantity) {
        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrderId(ORDER_ID);
        item.setProductTypeId(productTypeId);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setDiscountRate(discountRate);
        item.setDeliveredQuantity(deliveredQuantity);
        return item;
    }

    private SalesDeliveryItem makeDeliveryItem(String productTypeId, BigDecimal deliveredQuantity) {
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setProductTypeId(productTypeId);
        item.setDeliveredQuantity(deliveredQuantity);
        return item;
    }

    private SalesDeliveryRecord makeDelivery(List<SalesDeliveryItem> items) {
        SalesDeliveryRecord d = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(d, "id", DELIVERY_ID);
        d.setDeliveryNumber("DN-0010");
        d.setFactoryId(FACTORY_ID);
        d.setSalesOrderId(ORDER_ID);
        d.setStatus(SalesDeliveryStatus.DRAFT);
        d.setItems(items);
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

    @Test
    @DisplayName("shipDelivery: fully shipped, no discount → actualShippedAmount = Σ(deliveredQty × unitPrice)")
    void shipDelivery_fullyShipped_rollsUpShippedAmount() {
        SalesOrderItem orderItem = makeOrderItem("PT-001",
                new BigDecimal("10.0000"), new BigDecimal("100.0000"), BigDecimal.ZERO,
                BigDecimal.ZERO); // not yet delivered before this shipment
        SalesDeliveryItem deliveryItem = makeDeliveryItem("PT-001", new BigDecimal("10.0000"));
        SalesDeliveryRecord delivery = makeDelivery(new ArrayList<>(List.of(deliveryItem)));
        SalesOrder order = makeOrder();

        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(new ArrayList<>(List.of(orderItem)));
        when(salesOrderItemRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<SalesOrder> savedOrderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        when(salesOrderRepository.save(savedOrderCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ReflectionTestUtils.invokeMethod(salesService, "updateOrderDeliveryStatus", delivery);

        SalesOrder savedOrder = savedOrderCaptor.getValue();
        assertThat(savedOrder.getActualShippedAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("shipDelivery: with 10% line discount → actualShippedAmount reflects discounted price, mirroring getLineAmount()")
    void shipDelivery_withDiscount_appliesDiscountToShippedAmount() {
        SalesOrderItem orderItem = makeOrderItem("PT-002",
                new BigDecimal("5.0000"), new BigDecimal("200.0000"), new BigDecimal("10.00"),
                BigDecimal.ZERO);
        SalesDeliveryItem deliveryItem = makeDeliveryItem("PT-002", new BigDecimal("5.0000"));
        SalesDeliveryRecord delivery = makeDelivery(new ArrayList<>(List.of(deliveryItem)));
        SalesOrder order = makeOrder();

        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(new ArrayList<>(List.of(orderItem)));
        when(salesOrderItemRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<SalesOrder> savedOrderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        when(salesOrderRepository.save(savedOrderCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ReflectionTestUtils.invokeMethod(salesService, "updateOrderDeliveryStatus", delivery);

        // 5 * 200 = 1000, minus 10% discount = 900.00
        SalesOrder savedOrder = savedOrderCaptor.getValue();
        assertThat(savedOrder.getActualShippedAmount()).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("shipDelivery: unitPrice null (unpriced draft line) → that line contributes 0, not whole-order null (mirrors totalAmount null-safe convention)")
    void shipDelivery_nullUnitPriceLine_contributesZero() {
        SalesOrderItem pricedItem = makeOrderItem("PT-003",
                new BigDecimal("2.0000"), new BigDecimal("50.0000"), BigDecimal.ZERO,
                BigDecimal.ZERO);
        SalesOrderItem unpricedItem = makeOrderItem("PT-004",
                new BigDecimal("3.0000"), null, BigDecimal.ZERO,
                BigDecimal.ZERO);

        SalesDeliveryItem deliveryItem1 = makeDeliveryItem("PT-003", new BigDecimal("2.0000"));
        SalesDeliveryItem deliveryItem2 = makeDeliveryItem("PT-004", new BigDecimal("3.0000"));
        SalesDeliveryRecord delivery = makeDelivery(new ArrayList<>(List.of(deliveryItem1, deliveryItem2)));
        SalesOrder order = makeOrder();

        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(new ArrayList<>(List.of(pricedItem, unpricedItem)));
        when(salesOrderItemRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<SalesOrder> savedOrderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        when(salesOrderRepository.save(savedOrderCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ReflectionTestUtils.invokeMethod(salesService, "updateOrderDeliveryStatus", delivery);

        // Only the priced line (2 * 50 = 100.00) contributes; unpriced line contributes 0.
        SalesOrder savedOrder = savedOrderCaptor.getValue();
        assertThat(savedOrder.getActualShippedAmount()).isEqualByComparingTo("100.00");
    }
}
