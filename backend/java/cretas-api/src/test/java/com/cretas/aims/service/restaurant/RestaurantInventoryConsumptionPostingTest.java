package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.restaurant.MaterialRequisition;
import com.cretas.aims.entity.restaurant.StocktakingRecord;
import com.cretas.aims.entity.restaurant.WastageRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.restaurant.MaterialRequisitionRepository;
import com.cretas.aims.repository.restaurant.StocktakingRecordRepository;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteRepository;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.restaurant.impl.RestaurantInventoryPostingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Restaurant consumption inventory posting")
class RestaurantInventoryConsumptionPostingTest {

    private static final String FACTORY = "RES_3101_009";
    private static final String MATERIAL = "RMT_QHJ";
    private static final Long USER = 7L;

    @Mock SupplierDeliveryNoteRepository noteRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock PurchaseService purchaseService;
    @Mock WarehouseResolver warehouseResolver;
    @Mock MaterialBatchService materialBatchService;
    @Mock MaterialRequisitionRepository requisitionRepository;
    @Mock WastageRecordRepository wastageRecordRepository;
    @Mock StocktakingRecordRepository stocktakingRecordRepository;
    @Mock com.cretas.aims.service.uom.MaterialUomConverter materialUomConverter;

    RestaurantInventoryPostingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RestaurantInventoryPostingServiceImpl(
                noteRepository,
                supplierRepository,
                rawMaterialTypeRepository,
                purchaseService,
                warehouseResolver,
                materialBatchService,
                requisitionRepository,
                wastageRecordRepository,
                stocktakingRecordRepository,
                materialUomConverter);
    }

    @Test
    @DisplayName("领料审批过账按 FIFO 扣减 MaterialBatch 且重复调用不重复扣")
    void requisitionPostingConsumesFifoAndIsIdempotent() {
        when(rawMaterialTypeRepository.findById(MATERIAL)).thenReturn(Optional.of(material()));
        when(materialBatchService.getFIFOBatches(FACTORY, MATERIAL, new BigDecimal("3.0000")))
                .thenReturn(List.of(batch("B1", "5.0000", "8.00")));

        MaterialRequisition req = new MaterialRequisition();
        req.setId("REQ1");
        req.setFactoryId(FACTORY);
        req.setRequisitionNumber("REQ-001");
        req.setRawMaterialTypeId(MATERIAL);
        req.setActualQuantity(new BigDecimal("3.0000"));

        String detail = service.postMaterialRequisitionIssue(FACTORY, req, USER);
        String duplicateDetail = service.postMaterialRequisitionIssue(FACTORY, req, USER);

        assertEquals(detail, duplicateDetail);
        assertNotNull(req.getInventoryPostedAt());
        assertEquals(USER, req.getInventoryPostedBy());
        assertEquals("B1", req.getMaterialBatchId());
        assertEquals(new BigDecimal("24.00"), req.getActualCost());
        verify(materialBatchService, times(1)).useBatchQuantity(FACTORY, "B1", new BigDecimal("3.0000"));
    }

    @Test
    @DisplayName("库存不足 fail closed，不扣批次")
    void requisitionPostingRejectsInsufficientInventory() {
        when(rawMaterialTypeRepository.findById(MATERIAL)).thenReturn(Optional.of(material()));
        when(materialBatchService.getFIFOBatches(FACTORY, MATERIAL, new BigDecimal("3.0000")))
                .thenReturn(List.of(batch("B1", "1.0000", "8.00")));

        MaterialRequisition req = new MaterialRequisition();
        req.setId("REQ1");
        req.setFactoryId(FACTORY);
        req.setRawMaterialTypeId(MATERIAL);
        req.setActualQuantity(new BigDecimal("3.0000"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.postMaterialRequisitionIssue(FACTORY, req, USER));

        assertEquals(409, ex.getCode());
        assertEquals("INSUFFICIENT_INVENTORY", ex.getErrorCode());
        assertNull(req.getInventoryPostedAt());
        verify(materialBatchService, never()).useBatchQuantity(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("损耗审批过账真实扣库存并按批次单价回填估算成本")
    void wastagePostingConsumesInventoryAndCalculatesCost() {
        when(rawMaterialTypeRepository.findById(MATERIAL)).thenReturn(Optional.of(material()));
        when(materialBatchService.getFIFOBatches(FACTORY, MATERIAL, new BigDecimal("2.0000")))
                .thenReturn(List.of(batch("B1", "5.0000", "8.00")));

        WastageRecord record = new WastageRecord();
        record.setId("WST1");
        record.setFactoryId(FACTORY);
        record.setWastageNumber("WST-001");
        record.setRawMaterialTypeId(MATERIAL);
        record.setQuantity(new BigDecimal("2.0000"));
        record.setOperatorId(99L);
        record.setSectionCode("HOT_DISH");

        service.postWastageDeduction(FACTORY, record, USER);

        assertNotNull(record.getInventoryPostedAt());
        assertEquals(new BigDecimal("16.00"), record.getEstimatedCost());
        assertEquals(99L, record.getOperatorId());
        assertEquals("HOT_DISH", record.getSectionCode());
        verify(materialBatchService).useBatchQuantity(FACTORY, "B1", new BigDecimal("2.0000"));
    }

    @Test
    @DisplayName("盘点完成按差异真实调整库存")
    void stocktakingPostingConsumesShortage() {
        when(rawMaterialTypeRepository.findById(MATERIAL)).thenReturn(Optional.of(material()));
        when(materialBatchService.getFIFOBatches(FACTORY, MATERIAL, new BigDecimal("2.0000")))
                .thenReturn(List.of(batch("B1", "5.0000", "8.00")));

        StocktakingRecord record = new StocktakingRecord();
        record.setId("STK1");
        record.setFactoryId(FACTORY);
        record.setStocktakingNumber("STK-001");
        record.setRawMaterialTypeId(MATERIAL);
        record.setSystemQuantity(new BigDecimal("5.0000"));
        record.setActualQuantity(new BigDecimal("3.0000"));

        service.postStocktakingAdjustment(FACTORY, record, USER);

        assertEquals(StocktakingRecord.DifferenceType.SHORTAGE, record.getDifferenceType());
        assertEquals(new BigDecimal("-2.0000"), record.getDifferenceQuantity());
        assertEquals(new BigDecimal("16.00"), record.getDifferenceAmount());
        assertNotNull(record.getInventoryPostedAt());
        verify(materialBatchService).useBatchQuantity(FACTORY, "B1", new BigDecimal("2.0000"));
    }

    private RawMaterialType material() {
        RawMaterialType material = new RawMaterialType();
        material.setId(MATERIAL);
        material.setFactoryId(FACTORY);
        material.setName("青花椒");
        return material;
    }

    private MaterialBatchDTO batch(String id, String currentQuantity, String unitPrice) {
        MaterialBatchDTO batch = new MaterialBatchDTO();
        batch.setId(id);
        batch.setFactoryId(FACTORY);
        batch.setMaterialTypeId(MATERIAL);
        batch.setCurrentQuantity(new BigDecimal(currentQuantity));
        batch.setUnitPrice(new BigDecimal(unitPrice));
        return batch;
    }
}
