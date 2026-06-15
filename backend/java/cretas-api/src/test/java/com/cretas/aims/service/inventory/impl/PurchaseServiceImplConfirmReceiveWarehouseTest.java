package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
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
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl confirmReceive warehouse posting")
class PurchaseServiceImplConfirmReceiveWarehouseTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRecordRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private BomItemRepository bomItemRepository;
    @Mock private com.cretas.aims.service.finance.ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private WarehouseResolver warehouseResolver;

    private PurchaseServiceImpl service;

    private static final String FACTORY = "RES_3101_009";
    private static final String RECEIVE_ID = "RCV-QHJ-001";
    private static final String MATERIAL_ID = "RMT-QHJ-PEPPER";
    private static final Long USER_ID = 9L;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                receiveRecordRepository,
                supplierRepository,
                materialTypeRepository,
                materialBatchRepository,
                bomItemRepository,
                arApService,
                applicationEventPublisher,
                materialBatchService);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
    }

    @Test
    @DisplayName("confirmReceive creates MaterialBatch in receive record warehouse and writes materialBatchId")
    void confirmReceive_usesRecordWarehouseAndWritesMaterialBatchId() {
        PurchaseReceiveRecord record = draftRecord("WH-COLD-01");
        when(receiveRecordRepository.findById(RECEIVE_ID)).thenReturn(Optional.of(record));
        when(materialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(rawMaterial()));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch batch = inv.getArgument(0);
            batch.setId("BATCH-QHJ-001");
            return batch;
        });
        when(receiveRecordRepository.save(any(PurchaseReceiveRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        PurchaseReceiveRecord result = service.confirmReceive(FACTORY, RECEIVE_ID, USER_ID);

        assertEquals(PurchaseReceiveStatus.CONFIRMED, result.getStatus());
        assertEquals("BATCH-QHJ-001", result.getItems().get(0).getMaterialBatchId());

        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).save(batchCaptor.capture());
        MaterialBatch savedBatch = batchCaptor.getValue();
        assertEquals("WH-COLD-01", savedBatch.getWarehouseId());
        assertEquals(MATERIAL_ID, savedBatch.getMaterialTypeId());
        assertEquals(new BigDecimal("12.5000"), savedBatch.getReceiptQuantity());
        assertEquals("kg", savedBatch.getQuantityUnit());
        assertEquals(new BigDecimal("8.20"), savedBatch.getUnitPrice());

        verify(materialBatchService).recalculateMovingAvgPrice(
                MATERIAL_ID, new BigDecimal("12.5000"), new BigDecimal("8.20"), "BATCH-QHJ-001");
        verify(warehouseResolver, never()).resolveLogisticsId(FACTORY);
    }

    @Test
    @DisplayName("confirmReceive copies receive item factoryNumber and originPlace onto MaterialBatch")
    void confirmReceive_withFactoryNumber_setsOnBatch() {
        PurchaseReceiveRecord record = draftRecord("WH-COLD-01");
        record.getItems().get(0).setFactoryNumber("SC-321");
        record.getItems().get(0).setOriginPlace("四川成都");
        when(receiveRecordRepository.findById(RECEIVE_ID)).thenReturn(Optional.of(record));
        when(materialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(rawMaterial()));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch batch = inv.getArgument(0);
            batch.setId("BATCH-QHJ-FACTORY");
            return batch;
        });
        when(receiveRecordRepository.save(any(PurchaseReceiveRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirmReceive(FACTORY, RECEIVE_ID, USER_ID);

        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).save(batchCaptor.capture());
        MaterialBatch savedBatch = batchCaptor.getValue();
        assertEquals("SC-321", savedBatch.getFactoryNumber());
        assertEquals("四川成都", savedBatch.getOriginPlace());
    }

    @Test
    @DisplayName("confirmReceive without factoryNumber keeps MaterialBatch factoryNumber null")
    void confirmReceive_noFactoryNumber_batchNull() {
        PurchaseReceiveRecord record = draftRecord("WH-COLD-01");
        when(receiveRecordRepository.findById(RECEIVE_ID)).thenReturn(Optional.of(record));
        when(materialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(rawMaterial()));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch batch = inv.getArgument(0);
            batch.setId("BATCH-QHJ-NULL");
            return batch;
        });
        when(receiveRecordRepository.save(any(PurchaseReceiveRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirmReceive(FACTORY, RECEIVE_ID, USER_ID);

        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).save(batchCaptor.capture());
        assertNull(batchCaptor.getValue().getFactoryNumber());
    }

    @Test
    @DisplayName("confirmReceive falls back to WH-LOG only when receive record has no warehouse")
    void confirmReceive_blankWarehouseFallsBackToLogisticsWarehouse() {
        PurchaseReceiveRecord record = draftRecord(" ");
        when(receiveRecordRepository.findById(RECEIVE_ID)).thenReturn(Optional.of(record));
        when(materialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(rawMaterial()));
        when(warehouseResolver.resolveLogisticsId(FACTORY)).thenReturn("WH-LOG-ID");
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch batch = inv.getArgument(0);
            batch.setId("BATCH-QHJ-002");
            return batch;
        });
        when(receiveRecordRepository.save(any(PurchaseReceiveRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirmReceive(FACTORY, RECEIVE_ID, USER_ID);

        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).save(batchCaptor.capture());
        assertEquals("WH-LOG-ID", batchCaptor.getValue().getWarehouseId());
    }

    @Test
    @DisplayName("duplicate confirm is rejected before creating another MaterialBatch")
    void confirmReceive_duplicateConfirmedRecordRejectsBeforeBatchCreate() {
        PurchaseReceiveRecord record = draftRecord("WH-COLD-01");
        record.setStatus(PurchaseReceiveStatus.CONFIRMED);
        when(receiveRecordRepository.findById(RECEIVE_ID)).thenReturn(Optional.of(record));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmReceive(FACTORY, RECEIVE_ID, USER_ID));

        assertEquals(409, ex.getCode());
        verify(materialBatchRepository, never()).save(any(MaterialBatch.class));
    }

    private PurchaseReceiveRecord draftRecord(String warehouseId) {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setId(RECEIVE_ID);
        record.setFactoryId(FACTORY);
        record.setReceiveNumber("RCV-QHJ-20260605-001");
        record.setSupplierId("SUP-QHJ");
        record.setReceiveDate(LocalDate.of(2026, 6, 5));
        record.setWarehouseId(warehouseId);
        record.setStatus(PurchaseReceiveStatus.DRAFT);
        record.setReceivedBy(USER_ID);

        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setMaterialTypeId(MATERIAL_ID);
        item.setMaterialName("green pepper");
        item.setReceivedQuantity(new BigDecimal("12.5000"));
        item.setUnit("kg");
        item.setUnitPrice(new BigDecimal("8.20"));
        record.getItems().add(item);
        return record;
    }

    private RawMaterialType rawMaterial() {
        RawMaterialType material = new RawMaterialType();
        material.setId(MATERIAL_ID);
        material.setFactoryId(FACTORY);
        material.setName("green pepper");
        return material;
    }
}
