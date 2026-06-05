package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNoteLine;
import com.cretas.aims.entity.restaurant.enums.DeliveryNoteStatus;
import com.cretas.aims.entity.restaurant.enums.DeliveryPostingStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.restaurant.impl.RestaurantInventoryPostingServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RestaurantInventoryPostingServiceImpl")
class RestaurantInventoryPostingServiceImplTest {

    @Mock SupplierDeliveryNoteRepository noteRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock PurchaseService purchaseService;
    @Mock WarehouseResolver warehouseResolver;

    @InjectMocks RestaurantInventoryPostingServiceImpl service;

    private static final String FACTORY = "RES_3101_009";
    private static final Long USER = 7L;

    @Test
    @DisplayName("送货单过账创建采购收货并回写批次")
    void postSupplierDeliveryToInventory_createsReceiveAndBindsBatch() {
        SupplierDeliveryNote note = draftNote();
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));
        when(noteRepository.saveAndFlush(any(SupplierDeliveryNote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(noteRepository.save(any(SupplierDeliveryNote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(supplierRepository.findByIdAndFactoryId("SUP1", FACTORY)).thenReturn(Optional.of(new Supplier()));
        RawMaterialType material = new RawMaterialType();
        material.setId("RMT_QHJ");
        material.setFactoryId(FACTORY);
        material.setName("青花椒");
        when(rawMaterialTypeRepository.findById("RMT_QHJ")).thenReturn(Optional.of(material));
        when(warehouseResolver.resolveLogisticsId(FACTORY)).thenReturn("WH-LOG-ID");

        PurchaseReceiveRecord draftReceive = new PurchaseReceiveRecord();
        draftReceive.setId("RCV1");
        when(purchaseService.createReceiveRecord(eq(FACTORY), any(CreateReceiveRecordRequest.class), eq(USER)))
                .thenReturn(draftReceive);

        PurchaseReceiveRecord confirmed = new PurchaseReceiveRecord();
        confirmed.setId("RCV1");
        PurchaseReceiveItem receiveItem = new PurchaseReceiveItem();
        receiveItem.setMaterialTypeId("RMT_QHJ");
        receiveItem.setMaterialBatchId("BATCH1");
        confirmed.getItems().add(receiveItem);
        when(purchaseService.confirmReceive(FACTORY, "RCV1", USER)).thenReturn(confirmed);

        SupplierDeliveryNote result = service.postSupplierDeliveryToInventory(FACTORY, "N1", USER);

        assertEquals(DeliveryNoteStatus.CONFIRMED, result.getStatus());
        assertEquals(DeliveryPostingStatus.POSTED, result.getPostingStatus());
        assertEquals("RCV1", result.getReceiveRecordId());
        assertEquals("BATCH1", result.getLines().get(0).getMaterialBatchId());
        assertEquals("WH-LOG-ID", result.getWarehouseId());

        ArgumentCaptor<CreateReceiveRecordRequest> captor = ArgumentCaptor.forClass(CreateReceiveRecordRequest.class);
        verify(purchaseService).createReceiveRecord(eq(FACTORY), captor.capture(), eq(USER));
        CreateReceiveRecordRequest req = captor.getValue();
        assertNull(req.getPurchaseOrderId());
        assertEquals("SUP1", req.getSupplierId());
        assertEquals(LocalDate.of(2026, 6, 5), req.getReceiveDate());
        assertEquals("WH-LOG-ID", req.getWarehouseId());
        assertEquals(1, req.getItems().size());
        assertEquals("RMT_QHJ", req.getItems().get(0).getMaterialTypeId());
        assertEquals(new BigDecimal("10.0000"), req.getItems().get(0).getReceivedQuantity());
        assertEquals(new BigDecimal("40.00"), req.getItems().get(0).getUnitPrice());
    }

    @Test
    @DisplayName("已过账送货单重复确认被拒绝")
    void postSupplierDeliveryToInventory_rejectsAlreadyPosted() {
        SupplierDeliveryNote note = draftNote();
        note.setPostingStatus(DeliveryPostingStatus.POSTED);
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.postSupplierDeliveryToInventory(FACTORY, "N1", USER));
        assertEquals(409, ex.getCode());
        verifyNoInteractions(purchaseService);
    }

    @Test
    @DisplayName("失败状态使用独立保存方法持久化")
    void markSupplierDeliveryPostingFailed_setsFailedStatus() {
        SupplierDeliveryNote note = draftNote();
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));
        when(noteRepository.save(any(SupplierDeliveryNote.class))).thenAnswer(inv -> inv.getArgument(0));

        service.markSupplierDeliveryPostingFailed(FACTORY, "N1", "raw material missing");

        assertEquals(DeliveryPostingStatus.FAILED, note.getPostingStatus());
        assertEquals("raw material missing", note.getPostingError());
        verify(noteRepository).save(note);
    }

    @Test
    @DisplayName("已处理单据失败标记不污染历史状态")
    void markSupplierDeliveryPostingFailed_doesNotTouchProcessedNote() {
        SupplierDeliveryNote note = draftNote();
        note.setStatus(DeliveryNoteStatus.CONFIRMED);
        note.setPostingStatus(DeliveryPostingStatus.POSTED);
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));

        service.markSupplierDeliveryPostingFailed(FACTORY, "N1", "duplicate submit");

        assertEquals(DeliveryPostingStatus.POSTED, note.getPostingStatus());
        assertNull(note.getPostingError());
        verify(noteRepository, never()).save(any(SupplierDeliveryNote.class));
    }

    private SupplierDeliveryNote draftNote() {
        SupplierDeliveryNote note = new SupplierDeliveryNote();
        note.setId("N1");
        note.setFactoryId(FACTORY);
        note.setSupplierId("SUP1");
        note.setSupplierName("测试供应商");
        note.setDeliveryDate(LocalDate.of(2026, 6, 5));
        note.setNoteNumber("SDN-001");
        note.setStatus(DeliveryNoteStatus.DRAFT);
        note.setPostingStatus(DeliveryPostingStatus.UNPOSTED);

        SupplierDeliveryNoteLine line = new SupplierDeliveryNoteLine();
        line.setIngredientName("青花椒");
        line.setRawMaterialTypeId("RMT_QHJ");
        line.setQuantity(new BigDecimal("10.0000"));
        line.setUnit("kg");
        line.setUnitPrice(new BigDecimal("40.00"));
        note.addLine(line);
        return note;
    }
}
