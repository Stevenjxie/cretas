package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateDeliveryShipmentRequest;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("M10 parent delivery, child shipment and transport audit contract")
class SalesDeliveryShipmentContractTest {

    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesDeliveryRecordRepository deliveryRecordRepository;
    private SalesServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SalesServiceImpl(salesOrderRepository, null, deliveryRecordRepository,
                null, null, null, null, null);
        lenient().when(deliveryRecordRepository.findByIdAndFactoryIdForUpdate(anyString(), anyString()))
                .thenAnswer(invocation -> deliveryRecordRepository.findById(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("logistics shipping fails closed without date/company/tracking while explicit pickup is allowed")
    void trackingGateIsFailClosedWithExplicitExemption() {
        SalesDeliveryRecord logistics = delivery("S1", SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        logistics.setDeliveryMethod("LOGISTICS");
        logistics.setDeliveryDate(LocalDate.of(2026, 7, 21));
        logistics.setLogisticsCompany("顺丰");
        when(deliveryRecordRepository.findById("S1")).thenReturn(Optional.of(logistics));

        assertThatThrownBy(() -> service.shipDelivery("F006", "S1", 1309L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("SHIPMENT_TRACKING_REQUIRED"));
        verify(deliveryRecordRepository, never()).save(any());

        SalesDeliveryRecord pickup = delivery("S2", SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        pickup.setDeliveryMethod("SELF_PICKUP");
        pickup.setDeliveryDate(LocalDate.of(2026, 7, 21));
        when(deliveryRecordRepository.findById("S2")).thenReturn(Optional.of(pickup));
        when(deliveryRecordRepository.save(pickup)).thenReturn(pickup);

        SalesDeliveryRecord result = service.shipDelivery("F006", "S2", 1309L);
        assertThat(result.getStatus()).isEqualTo(SalesDeliveryStatus.SHIPPED);
    }

    @Test
    @DisplayName("order row lock serializes child capacity and rejects quantity above the mother remainder")
    void childShipmentCannotExceedMotherRemaining() {
        SalesDeliveryRecord parent = delivery("M1", SalesDeliveryStatus.PARTIALLY_SCHEDULED);
        parent.setRecordRole("MASTER");
        parent.setSalesOrderId("SO-1");
        parent.setDeliveryAddress("客户地址");
        SalesDeliveryItem parentItem = item(100L, 726L, "5");
        parentItem.setDeliveryRecordId("M1");
        parent.setItems(new ArrayList<>(List.of(parentItem)));

        SalesDeliveryRecord existing = delivery("S0", SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        existing.setRecordRole("SHIPMENT");
        existing.setParentDeliveryId("M1");
        existing.setSalesOrderId("SO-1");
        SalesDeliveryItem existingItem = item(101L, 726L, "2");
        existing.setItems(new ArrayList<>(List.of(existingItem)));

        when(deliveryRecordRepository.findById("M1")).thenReturn(Optional.of(parent));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("SO-1", "F006"))
                .thenReturn(Optional.of(new SalesOrder()));
        when(deliveryRecordRepository.findBySalesOrderId("SO-1")).thenReturn(List.of(parent, existing));
        when(deliveryRecordRepository.save(any())).thenAnswer(invocation -> {
            SalesDeliveryRecord row = invocation.getArgument(0);
            if (row.getId() == null) row.setId("S1");
            return row;
        });

        CreateDeliveryShipmentRequest request = shipmentRequest("request-1", new BigDecimal("4"));
        assertThatThrownBy(() -> service.createDeliveryShipment("F006", "M1", request, 1309L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("SHIPMENT_QUANTITY_EXCEEDS_PARENT_REMAINING"));
        verify(salesOrderRepository).findByIdAndFactoryIdForUpdate("SO-1", "F006");
    }

    @Test
    @DisplayName("same shipment idempotency key replays the existing child without creating another row")
    void shipmentIdempotencyReplayReturnsExistingChild() {
        SalesDeliveryRecord parent = delivery("M1", SalesDeliveryStatus.PENDING_SPLIT);
        parent.setRecordRole("MASTER");
        parent.setSalesOrderId("SO-1");
        SalesDeliveryRecord existing = delivery("S1", SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM);
        existing.setRecordRole("SHIPMENT");
        existing.setParentDeliveryId("M1");
        existing.setSalesOrderId("SO-1");
        existing.setIdempotencyKey("same-key");
        when(deliveryRecordRepository.findById("M1")).thenReturn(Optional.of(parent));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("SO-1", "F006"))
                .thenReturn(Optional.of(new SalesOrder()));
        when(deliveryRecordRepository.findBySalesOrderId("SO-1")).thenReturn(List.of(parent, existing));

        assertThat(service.createDeliveryShipment("F006", "M1", shipmentRequest("same-key", BigDecimal.ONE), 1309L))
                .isSameAs(existing);
        verify(deliveryRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("all effective leaf shipments signed advances the order transport aggregate to RECEIVED")
    void signedShipmentsAdvanceOrderTransportAggregate() {
        SalesDeliveryRecord child = delivery("S1", SalesDeliveryStatus.SHIPPED);
        child.setRecordRole("SHIPMENT");
        child.setSalesOrderId("SO-1");
        SalesOrder order = new SalesOrder();
        order.setId("SO-1");
        order.setFactoryId("F006");
        when(deliveryRecordRepository.findById("S1")).thenReturn(Optional.of(child));
        when(deliveryRecordRepository.save(child)).thenReturn(child);
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("SO-1", "F006")).thenReturn(Optional.of(order));
        when(deliveryRecordRepository.findBySalesOrderId("SO-1")).thenReturn(List.of(child));

        service.confirmDelivered("F006", "S1");

        assertThat(child.getStatus()).isEqualTo(SalesDeliveryStatus.DELIVERED);
        assertThat(child.getSignedAt()).isNotNull();
        assertThat(order.getTransportPlanStatus()).isEqualTo("RECEIVED");
        verify(salesOrderRepository).save(order);
    }

    @Test
    @DisplayName("duplicate parent delivery lines are rejected before child persistence")
    void duplicateParentDeliveryItemsAreRejected() {
        SalesDeliveryRecord parent = delivery("M1", SalesDeliveryStatus.PENDING_SPLIT);
        parent.setRecordRole("MASTER");
        parent.setSalesOrderId("SO-1");
        parent.setDeliveryAddress("客户地址");
        parent.setItems(new ArrayList<>(List.of(item(100L, 726L, "5"))));
        when(deliveryRecordRepository.findById("M1")).thenReturn(Optional.of(parent));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("SO-1", "F006"))
                .thenReturn(Optional.of(new SalesOrder()));
        when(deliveryRecordRepository.findBySalesOrderId("SO-1")).thenReturn(List.of(parent));

        CreateDeliveryShipmentRequest request = shipmentRequest("duplicate-lines", BigDecimal.ONE);
        CreateDeliveryShipmentRequest.Item duplicate = new CreateDeliveryShipmentRequest.Item();
        duplicate.setParentDeliveryItemId(100L);
        duplicate.setQuantity(BigDecimal.ONE);
        request.setItems(List.of(request.getItems().get(0), duplicate));

        assertThatThrownBy(() -> service.createDeliveryShipment("F006", "M1", request, 1309L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("DUPLICATE_PARENT_DELIVERY_ITEM"));
        verify(deliveryRecordRepository, never()).save(any());
    }

    private SalesDeliveryRecord delivery(String id, SalesDeliveryStatus status) {
        SalesDeliveryRecord row = new SalesDeliveryRecord();
        row.setId(id);
        row.setFactoryId("F006");
        row.setStatus(status);
        row.setRecordRole("SHIPMENT");
        row.setItems(new ArrayList<>());
        return row;
    }

    private SalesDeliveryItem item(Long id, Long orderItemId, String quantity) {
        SalesDeliveryItem row = new SalesDeliveryItem();
        row.setId(id);
        row.setSalesOrderItemId(orderItemId);
        row.setProductTypeId("SKU-1");
        row.setProductName("成品");
        row.setDeliveredQuantity(new BigDecimal(quantity));
        row.setUnit("box");
        row.setUnitPrice(new BigDecimal("20"));
        return row;
    }

    private CreateDeliveryShipmentRequest shipmentRequest(String key, BigDecimal quantity) {
        CreateDeliveryShipmentRequest request = new CreateDeliveryShipmentRequest();
        request.setIdempotencyKey(key);
        request.setPlannedShipmentDate(LocalDate.of(2026, 7, 21));
        request.setDeliveryMethod("SELF_PICKUP");
        request.setDeliveryAddress("客户地址");
        CreateDeliveryShipmentRequest.Item item = new CreateDeliveryShipmentRequest.Item();
        item.setParentDeliveryItemId(100L);
        item.setQuantity(quantity);
        request.setItems(List.of(item));
        return request;
    }
}
