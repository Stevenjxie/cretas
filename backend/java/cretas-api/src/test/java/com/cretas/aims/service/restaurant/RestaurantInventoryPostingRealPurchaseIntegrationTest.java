package com.cretas.aims.service.restaurant;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNoteLine;
import com.cretas.aims.entity.restaurant.enums.DeliveryNoteStatus;
import com.cretas.aims.entity.restaurant.enums.DeliveryPostingStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.impl.PurchaseServiceImpl;
import com.cretas.aims.service.restaurant.impl.RestaurantInventoryPostingServiceImpl;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Restaurant supplier delivery posting with real PurchaseServiceImpl")
class RestaurantInventoryPostingRealPurchaseIntegrationTest {

    @Mock private SupplierDeliveryNoteRepository noteRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRecordRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private BomRecipeItemRepository bomItemRepository;
    @Mock private com.cretas.aims.service.finance.ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private WarehouseResolver warehouseResolver;

    private static final String FACTORY = "RES_3101_009";
    private static final String NOTE_ID = "SDN-QHJ-001";
    private static final String MATERIAL_ID = "RMT-QHJ-PORK";
    private static final Long USER_ID = 12L;

    @Test
    @DisplayName("delivery note posts through real PurchaseService and creates batch")
    void postSupplierDeliveryToInventory_realPurchaseServiceCreatesReceiveAndBatch() {
        PurchaseServiceImpl purchaseService = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                receiveRecordRepository,
                supplierRepository,
                rawMaterialTypeRepository,
                materialBatchRepository,
                bomItemRepository,
                arApService,
                applicationEventPublisher,
                materialBatchService);
        ReflectionTestUtils.setField(purchaseService, "warehouseResolver", warehouseResolver);

        RestaurantInventoryPostingServiceImpl postingService = new RestaurantInventoryPostingServiceImpl(
                noteRepository,
                supplierRepository,
                rawMaterialTypeRepository,
                purchaseService,
                warehouseResolver,
                materialBatchService,
                null,
                null,
                null,
                org.mockito.Mockito.mock(com.cretas.aims.service.uom.MaterialUomConverter.class));

        SupplierDeliveryNote note = draftNote();
        when(noteRepository.findByIdAndFactoryId(NOTE_ID, FACTORY)).thenReturn(Optional.of(note));
        when(noteRepository.saveAndFlush(any(SupplierDeliveryNote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(noteRepository.save(any(SupplierDeliveryNote.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier supplier = new Supplier();
        supplier.setId("SUP-QHJ");
        supplier.setFactoryId(FACTORY);
        when(supplierRepository.findByIdAndFactoryId("SUP-QHJ", FACTORY)).thenReturn(Optional.of(supplier));

        RawMaterialType rawMaterial = new RawMaterialType();
        rawMaterial.setId(MATERIAL_ID);
        rawMaterial.setFactoryId(FACTORY);
        rawMaterial.setName("pork");
        when(rawMaterialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(rawMaterial));

        AtomicReference<PurchaseReceiveRecord> receiveRef = new AtomicReference<>();
        when(receiveRecordRepository.save(any(PurchaseReceiveRecord.class))).thenAnswer(inv -> {
            PurchaseReceiveRecord record = inv.getArgument(0);
            if (record.getId() == null) {
                record.setId("RCV-QHJ-REAL");
            }
            receiveRef.set(record);
            return record;
        });
        when(receiveRecordRepository.findById("RCV-QHJ-REAL"))
                .thenAnswer(inv -> Optional.ofNullable(receiveRef.get()));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch batch = inv.getArgument(0);
            batch.setId("BATCH-QHJ-REAL");
            return batch;
        });

        SupplierDeliveryNote result = postingService.postSupplierDeliveryToInventory(FACTORY, NOTE_ID, USER_ID);

        assertEquals(DeliveryNoteStatus.CONFIRMED, result.getStatus());
        assertEquals(DeliveryPostingStatus.POSTED, result.getPostingStatus());
        assertEquals("RCV-QHJ-REAL", result.getReceiveRecordId());
        assertEquals("BATCH-QHJ-REAL", result.getLines().get(0).getMaterialBatchId());

        PurchaseReceiveRecord receiveRecord = receiveRef.get();
        assertNotNull(receiveRecord);
        assertEquals(PurchaseReceiveStatus.CONFIRMED, receiveRecord.getStatus());
        assertEquals("WH-COLD-01", receiveRecord.getWarehouseId());
        assertEquals("BATCH-QHJ-REAL", receiveRecord.getItems().get(0).getMaterialBatchId());

        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).save(batchCaptor.capture());
        assertEquals("WH-COLD-01", batchCaptor.getValue().getWarehouseId());
        assertEquals(MATERIAL_ID, batchCaptor.getValue().getMaterialTypeId());
        assertEquals(new BigDecimal("6.0000"), batchCaptor.getValue().getReceiptQuantity());
        verify(materialBatchService).recalculateMovingAvgPrice(
                MATERIAL_ID, new BigDecimal("6.0000"), new BigDecimal("18.50"), "BATCH-QHJ-REAL");
    }

    private SupplierDeliveryNote draftNote() {
        SupplierDeliveryNote note = new SupplierDeliveryNote();
        note.setId(NOTE_ID);
        note.setFactoryId(FACTORY);
        note.setSupplierId("SUP-QHJ");
        note.setSupplierName("QHJ Supplier");
        note.setDeliveryDate(LocalDate.of(2026, 6, 5));
        note.setNoteNumber("SDN-QHJ-20260605-001");
        note.setWarehouseId("WH-COLD-01");
        note.setStatus(DeliveryNoteStatus.DRAFT);
        note.setPostingStatus(DeliveryPostingStatus.UNPOSTED);

        SupplierDeliveryNoteLine line = new SupplierDeliveryNoteLine();
        line.setIngredientName("pork");
        line.setRawMaterialTypeId(MATERIAL_ID);
        line.setQuantity(new BigDecimal("6.0000"));
        line.setUnit("kg");
        line.setUnitPrice(new BigDecimal("18.50"));
        note.addLine(line);
        return note;
    }
}
