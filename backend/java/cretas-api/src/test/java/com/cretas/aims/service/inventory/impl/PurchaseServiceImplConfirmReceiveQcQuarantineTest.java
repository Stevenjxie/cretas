package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * 🔒🔒 QC 入库门 (食品安全): 收货行质检结果决定物料批次入库状态。
 *
 * <p>非 PASS (DAMAGED/PARTIAL_LOST/OTHER) → DEFECTIVE (隔离, FEFO/领料/销售自动排除);
 * PASS / null → AVAILABLE (正常流不受影响)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl confirmReceive QC-fail auto-quarantine")
class PurchaseServiceImplConfirmReceiveQcQuarantineTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRecordRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private BomRecipeItemRepository bomItemRepository;
    @Mock private com.cretas.aims.service.finance.ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private WarehouseResolver warehouseResolver;

    private PurchaseServiceImpl service;

    private static final String FACTORY = "RES_3101_009";
    private static final String RECEIVE_ID = "RCV-QC-001";
    private static final String MATERIAL_ID = "RMT-QC-PORK";
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

    private MaterialBatch confirmWithQcResult(String qcResult) {
        PurchaseReceiveRecord record = draftRecord(qcResult);
        when(receiveRecordRepository.findById(RECEIVE_ID)).thenReturn(Optional.of(record));
        when(materialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(rawMaterial()));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch batch = inv.getArgument(0);
            batch.setId("BATCH-QC");
            return batch;
        });
        when(receiveRecordRepository.save(any(PurchaseReceiveRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirmReceive(FACTORY, RECEIVE_ID, USER_ID);

        ArgumentCaptor<MaterialBatch> captor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("qcResult=DAMAGED → batch DEFECTIVE (quarantined, excluded from FEFO/领料/销售)")
    void damaged_quarantined() {
        assertEquals(MaterialBatchStatus.DEFECTIVE, confirmWithQcResult("DAMAGED").getStatus());
    }

    @Test
    @DisplayName("qcResult=PARTIAL_LOST → batch DEFECTIVE (whole line, over-safe; no qty-split modeled)")
    void partialLost_quarantined() {
        assertEquals(MaterialBatchStatus.DEFECTIVE, confirmWithQcResult("PARTIAL_LOST").getStatus());
    }

    @Test
    @DisplayName("qcResult=OTHER → batch DEFECTIVE (ambiguous → over-safe quarantine)")
    void other_quarantined() {
        assertEquals(MaterialBatchStatus.DEFECTIVE, confirmWithQcResult("OTHER").getStatus());
    }

    @Test
    @DisplayName("qcResult=PASS → batch AVAILABLE (normal flow unaffected)")
    void pass_available() {
        assertEquals(MaterialBatchStatus.AVAILABLE, confirmWithQcResult("PASS").getStatus());
    }

    @Test
    @DisplayName("qcResult=null (QC not recorded) → batch AVAILABLE (safe default, not quarantined)")
    void nullQc_available() {
        assertEquals(MaterialBatchStatus.AVAILABLE, confirmWithQcResult(null).getStatus());
    }

    private PurchaseReceiveRecord draftRecord(String qcResult) {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setId(RECEIVE_ID);
        record.setFactoryId(FACTORY);
        record.setReceiveNumber("RCV-QC-20260704-001");
        record.setSupplierId("SUP-QC");
        record.setReceiveDate(LocalDate.of(2026, 7, 4));
        record.setWarehouseId("WH-RAW-01");
        record.setStatus(PurchaseReceiveStatus.DRAFT);
        record.setReceivedBy(USER_ID);

        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setMaterialTypeId(MATERIAL_ID);
        item.setMaterialName("pork trotter");
        item.setReceivedQuantity(new BigDecimal("50.0000"));
        item.setUnit("kg");
        item.setUnitPrice(new BigDecimal("18.00"));
        item.setQcResult(qcResult);
        record.getItems().add(item);
        return record;
    }

    private RawMaterialType rawMaterial() {
        RawMaterialType material = new RawMaterialType();
        material.setId(MATERIAL_ID);
        material.setFactoryId(FACTORY);
        material.setName("pork trotter");
        return material;
    }
}
