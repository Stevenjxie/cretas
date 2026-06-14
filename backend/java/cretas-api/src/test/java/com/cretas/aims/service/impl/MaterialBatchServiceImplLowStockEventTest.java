package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.event.InventoryStockChangedEvent;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialBatchServiceImplLowStockEventTest {

    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchMapper materialBatchMapper;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private FuturePlanMatchingService futurePlanMatchingService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private MaterialBatchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MaterialBatchServiceImpl(
                materialBatchRepository,
                materialBatchAdjustmentRepository,
                materialTypeRepository,
                materialBatchMapper,
                materialConsumptionRepository,
                productionPlanBatchUsageRepository,
                excelUtil,
                futurePlanMatchingService);
        InventoryLowStockEventPublisher lowStockEventPublisher = new InventoryLowStockEventPublisher(
                applicationEventPublisher,
                materialBatchRepository,
                materialTypeRepository,
                Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", lowStockEventPublisher);
    }

    @Test
    void useBatchQuantityPublishesLowStockEventWhenDeductionDropsBelowMinStock() {
        MaterialBatch batch = batch("MB-1", "RM-1", "100", "20", "0");
        when(materialBatchRepository.findById("MB-1")).thenReturn(Optional.of(batch));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialTypeRepository.findById("RM-1")).thenReturn(Optional.of(materialType("RM-1", "Pork", "kg", "50")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType("F006", "RM-1"))
                .thenReturn(new BigDecimal("40"));

        service.useBatchQuantity("F006", "MB-1", new BigDecimal("40"));

        ArgumentCaptor<InventoryStockChangedEvent> captor =
                ArgumentCaptor.forClass(InventoryStockChangedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getMaterialTypeId()).isEqualTo("RM-1");
        assertThat(captor.getValue().getCurrentStock()).isEqualByComparingTo("40");
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("60");
    }

    @Test
    void consumeBatchMaterialPublishesLowStockEventWhenReservedConsumptionDropsBelowMinStock() {
        MaterialBatch batch = batch("MB-1", "RM-1", "100", "30", "50");
        when(materialBatchRepository.findById("MB-1")).thenReturn(Optional.of(batch));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialTypeRepository.findById("RM-1")).thenReturn(Optional.of(materialType("RM-1", "Pork", "kg", "50")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType("F006", "RM-1"))
                .thenReturn(new BigDecimal("40"));

        service.consumeBatchMaterial("F006", "MB-1", new BigDecimal("10"), "PP-1");

        ArgumentCaptor<InventoryStockChangedEvent> captor =
                ArgumentCaptor.forClass(InventoryStockChangedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo("OUT");
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo("40");
        assertThat(batch.getReservedQuantity()).isEqualByComparingTo("40");
    }

    private MaterialBatch batch(String id, String materialTypeId, String receipt, String used, String reserved) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId("F006");
        batch.setBatchNumber("B-" + id);
        batch.setMaterialTypeId(materialTypeId);
        batch.setReceiptQuantity(new BigDecimal(receipt));
        batch.setUsedQuantity(new BigDecimal(used));
        batch.setReservedQuantity(new BigDecimal(reserved));
        batch.setQuantityUnit("kg");
        batch.setWarehouseId("WH-RAW");
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        return batch;
    }

    private RawMaterialType materialType(String id, String name, String unit, String minStock) {
        RawMaterialType type = new RawMaterialType();
        type.setId(id);
        type.setName(name);
        type.setUnit(unit);
        type.setMinStock(new BigDecimal(minStock));
        return type;
    }
}
