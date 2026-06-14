package com.cretas.aims.service.alerts;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.event.InventoryStockChangedEvent;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryLowStockEventPublisherTest {

    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;

    private InventoryLowStockEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new InventoryLowStockEventPublisher(
                applicationEventPublisher,
                materialBatchRepository,
                materialTypeRepository,
                Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void publishesInventoryStockChangedEventWhenCurrentStockIsBelowMinStock() {
        when(materialTypeRepository.findById("RM-1")).thenReturn(Optional.of(materialType("RM-1", "Pork", "kg", "50")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType("F006", "RM-1"))
                .thenReturn(new BigDecimal("20"));

        publisher.publishIfLowStock("F006", "RM-1", "OUT");

        ArgumentCaptor<InventoryStockChangedEvent> captor =
                ArgumentCaptor.forClass(InventoryStockChangedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getFactoryId()).isEqualTo("F006");
        assertThat(captor.getValue().getMaterialTypeId()).isEqualTo("RM-1");
        assertThat(captor.getValue().getCurrentStock()).isEqualByComparingTo("20");
        assertThat(captor.getValue().getMinStockLevel()).isEqualByComparingTo("50");
        assertThat(captor.getValue().getChangeType()).isEqualTo("OUT");
    }

    @Test
    void skipsPublishWhenCurrentStockIsNotBelowMinStock() {
        when(materialTypeRepository.findById("RM-1")).thenReturn(Optional.of(materialType("RM-1", "Pork", "kg", "50")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType("F006", "RM-1"))
                .thenReturn(new BigDecimal("50"));

        publisher.publishIfLowStock("F006", "RM-1", "OUT");

        verify(applicationEventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsPublishWhenMinStockIsMissing() {
        RawMaterialType type = materialType("RM-1", "Pork", "kg", null);
        when(materialTypeRepository.findById("RM-1")).thenReturn(Optional.of(type));

        publisher.publishIfLowStock("F006", "RM-1", "OUT");

        verify(applicationEventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
        verify(materialBatchRepository, never()).sumAvailableQuantityByMaterialType(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deduplicatesSameFactoryAndMaterialWithinWindow() {
        when(materialTypeRepository.findById("RM-1")).thenReturn(Optional.of(materialType("RM-1", "Pork", "kg", "50")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType("F006", "RM-1"))
                .thenReturn(new BigDecimal("20"));

        publisher.publishIfLowStock("F006", "RM-1", "OUT");
        publisher.publishIfLowStock("F006", "RM-1", "OUT");

        verify(applicationEventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(InventoryStockChangedEvent.class));
    }

    private RawMaterialType materialType(String id, String name, String unit, String minStock) {
        RawMaterialType type = new RawMaterialType();
        type.setId(id);
        type.setName(name);
        type.setUnit(unit);
        if (minStock != null) {
            type.setMinStock(new BigDecimal(minStock));
        }
        return type;
    }
}
