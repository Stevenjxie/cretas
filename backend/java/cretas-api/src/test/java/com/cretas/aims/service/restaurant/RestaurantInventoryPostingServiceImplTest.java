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
import com.cretas.aims.service.uom.MaterialUomConverter;
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
    @Mock MaterialUomConverter materialUomConverter;

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
    @DisplayName("invalid material stops posting and can be persisted as FAILED")
    void postSupplierDeliveryToInventory_invalidMaterialCanBeMarkedFailed() {
        SupplierDeliveryNote note = draftNote();
        note.getLines().get(0).setRawMaterialTypeId("RMT_MISSING");
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));
        when(noteRepository.saveAndFlush(any(SupplierDeliveryNote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(noteRepository.save(any(SupplierDeliveryNote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(supplierRepository.findByIdAndFactoryId("SUP1", FACTORY)).thenReturn(Optional.of(new Supplier()));
        when(warehouseResolver.resolveLogisticsId(FACTORY)).thenReturn("WH-LOG-ID");
        when(rawMaterialTypeRepository.findById("RMT_MISSING")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.postSupplierDeliveryToInventory(FACTORY, "N1", USER));

        assertEquals(400, ex.getCode());
        verifyNoInteractions(purchaseService);

        service.markSupplierDeliveryPostingFailed(FACTORY, "N1", ex.getMessage());

        assertEquals(DeliveryPostingStatus.FAILED, note.getPostingStatus());
        assertTrue(note.getPostingError().contains("RMT_MISSING"));
        verify(noteRepository).save(note);
    }

    @Test
    @DisplayName("posting failure marker persists FAILED status")
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

    // ==================== Chunk 3: 落库单位归一化守卫 ====================

    @Test
    @DisplayName("OCR 单位斤 + 物料标准单位 kg → 经 converter 换算落 kg 批次")
    void postSupplierDeliveryToInventory_jinToKg_convertsViaConverter() {
        SupplierDeliveryNote note = draftNote();
        // OCR 行: 20 斤 @ 单价 10/斤 (行金额 200)
        SupplierDeliveryNoteLine line = note.getLines().get(0);
        line.setUnit("斤");
        line.setQuantity(new BigDecimal("20"));
        line.setUnitPrice(new BigDecimal("10"));

        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));
        when(noteRepository.saveAndFlush(any(SupplierDeliveryNote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(noteRepository.save(any(SupplierDeliveryNote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(supplierRepository.findByIdAndFactoryId("SUP1", FACTORY)).thenReturn(Optional.of(new Supplier()));
        RawMaterialType material = new RawMaterialType();
        material.setId("RMT_QHJ");
        material.setFactoryId(FACTORY);
        material.setName("青花椒");
        material.setUnit("kg");  // 物料标准单位 kg
        when(rawMaterialTypeRepository.findById("RMT_QHJ")).thenReturn(Optional.of(material));
        when(warehouseResolver.resolveLogisticsId(FACTORY)).thenReturn("WH-LOG-ID");
        // converter: 20 斤 → 10 kg
        when(materialUomConverter.toComparableQuantity("RMT_QHJ", new BigDecimal("20"), "斤", "kg"))
                .thenReturn(MaterialUomConverter.ConversionResult.converted(new BigDecimal("10"), "kg"));

        PurchaseReceiveRecord draftReceive = new PurchaseReceiveRecord();
        draftReceive.setId("RCV1");
        when(purchaseService.createReceiveRecord(eq(FACTORY), any(CreateReceiveRecordRequest.class), eq(USER)))
                .thenReturn(draftReceive);
        PurchaseReceiveRecord confirmed = new PurchaseReceiveRecord();
        confirmed.setId("RCV1");
        PurchaseReceiveItem ri = new PurchaseReceiveItem();
        ri.setMaterialTypeId("RMT_QHJ");
        ri.setMaterialBatchId("BATCH1");
        confirmed.getItems().add(ri);
        when(purchaseService.confirmReceive(FACTORY, "RCV1", USER)).thenReturn(confirmed);

        service.postSupplierDeliveryToInventory(FACTORY, "N1", USER);

        ArgumentCaptor<CreateReceiveRecordRequest> captor = ArgumentCaptor.forClass(CreateReceiveRecordRequest.class);
        verify(purchaseService).createReceiveRecord(eq(FACTORY), captor.capture(), eq(USER));
        CreateReceiveRecordRequest.ReceiveItemDTO item = captor.getValue().getItems().get(0);
        // 落库量 = 10 kg (换算后), 单位 = kg (物料标准单位)
        assertEquals(0, item.getReceivedQuantity().compareTo(new BigDecimal("10")));
        assertEquals("kg", item.getUnit());
        // 行金额守恒: 20 斤 × 10/斤 = 200 = 10 kg × 20/kg
        assertEquals(0, item.getUnitPrice().compareTo(new BigDecimal("20")));
    }

    @Test
    @DisplayName("OCR 单位与物料标准单位不可换算 → fail-loud 不创建错误单位批次")
    void postSupplierDeliveryToInventory_incompatibleUnit_failsLoud() {
        SupplierDeliveryNote note = draftNote();
        SupplierDeliveryNoteLine line = note.getLines().get(0);
        line.setUnit("个");  // 离散计件
        line.setQuantity(new BigDecimal("5"));

        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));
        when(noteRepository.saveAndFlush(any(SupplierDeliveryNote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(supplierRepository.findByIdAndFactoryId("SUP1", FACTORY)).thenReturn(Optional.of(new Supplier()));
        RawMaterialType material = new RawMaterialType();
        material.setId("RMT_QHJ");
        material.setFactoryId(FACTORY);
        material.setName("青花椒");
        material.setUnit("kg");  // kg, 与 "个" 不可换算
        when(rawMaterialTypeRepository.findById("RMT_QHJ")).thenReturn(Optional.of(material));
        when(warehouseResolver.resolveLogisticsId(FACTORY)).thenReturn("WH-LOG-ID");
        when(materialUomConverter.toComparableQuantity("RMT_QHJ", new BigDecimal("5"), "个", "kg"))
                .thenReturn(MaterialUomConverter.ConversionResult.unconvertible("个↔kg 不可换算"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.postSupplierDeliveryToInventory(FACTORY, "N1", USER));

        assertEquals(400, ex.getCode());
        assertEquals("DELIVERY_UNIT_MISMATCH", ex.getErrorCode());
        assertEquals("unit", ex.getHintTarget());
        assertTrue(ex.getMessage().contains("青花椒"));
        verify(purchaseService, never()).createReceiveRecord(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("OCR 单位与物料标准单位相同 → 透传不调 converter")
    void postSupplierDeliveryToInventory_sameUnit_passThrough() {
        SupplierDeliveryNote note = draftNote();  // kg / kg
        when(noteRepository.findByIdAndFactoryId("N1", FACTORY)).thenReturn(Optional.of(note));
        when(noteRepository.saveAndFlush(any(SupplierDeliveryNote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(noteRepository.save(any(SupplierDeliveryNote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(supplierRepository.findByIdAndFactoryId("SUP1", FACTORY)).thenReturn(Optional.of(new Supplier()));
        RawMaterialType material = new RawMaterialType();
        material.setId("RMT_QHJ");
        material.setFactoryId(FACTORY);
        material.setName("青花椒");
        material.setUnit("kg");
        when(rawMaterialTypeRepository.findById("RMT_QHJ")).thenReturn(Optional.of(material));
        when(warehouseResolver.resolveLogisticsId(FACTORY)).thenReturn("WH-LOG-ID");

        PurchaseReceiveRecord draftReceive = new PurchaseReceiveRecord();
        draftReceive.setId("RCV1");
        when(purchaseService.createReceiveRecord(eq(FACTORY), any(CreateReceiveRecordRequest.class), eq(USER)))
                .thenReturn(draftReceive);
        PurchaseReceiveRecord confirmed = new PurchaseReceiveRecord();
        confirmed.setId("RCV1");
        PurchaseReceiveItem ri = new PurchaseReceiveItem();
        ri.setMaterialTypeId("RMT_QHJ");
        ri.setMaterialBatchId("BATCH1");
        confirmed.getItems().add(ri);
        when(purchaseService.confirmReceive(FACTORY, "RCV1", USER)).thenReturn(confirmed);

        service.postSupplierDeliveryToInventory(FACTORY, "N1", USER);

        // 同单位不应调 converter
        verifyNoInteractions(materialUomConverter);
        ArgumentCaptor<CreateReceiveRecordRequest> captor = ArgumentCaptor.forClass(CreateReceiveRecordRequest.class);
        verify(purchaseService).createReceiveRecord(eq(FACTORY), captor.capture(), eq(USER));
        CreateReceiveRecordRequest.ReceiveItemDTO item = captor.getValue().getItems().get(0);
        assertEquals(0, item.getReceivedQuantity().compareTo(new BigDecimal("10.0000")));
        assertEquals("kg", item.getUnit());
        assertEquals(0, item.getUnitPrice().compareTo(new BigDecimal("40.00")));
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
