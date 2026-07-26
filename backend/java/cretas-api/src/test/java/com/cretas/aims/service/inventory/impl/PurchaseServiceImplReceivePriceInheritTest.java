package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.factory.WarehouseInventoryGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-RCV (2026-06-11): 收货行单价为空时从 PO 行价继承.
 *
 * <p>根因: createReceiveRecord 直接 setUnitPrice(itemDTO.getUnitPrice()), 收货未填价 →
 * 行价 null → confirmReceive 建批次 unit_price=null → 移动加权均价算不出 → 材料成本静默丢失.
 * 防呆: 仓管收货不必懂/不必填价, 系统按采购合同 (PO 行价) 自动带价.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl createReceiveRecord PO 行价继承 (BUG-RCV)")
class PurchaseServiceImplReceivePriceInheritTest {

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
    @Mock private WarehouseInventoryGuardService warehouseInventoryGuardService;

    private PurchaseServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String PO_ID = "PO-RCV-001";
    private static final String MAT_A = "RMT-A";
    private static final String MAT_B = "RMT-B";
    private static final Long USER_ID = 7L;

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
        ReflectionTestUtils.setField(service, "warehouseInventoryGuardService", warehouseInventoryGuardService);
        // @Value 注入字段, 单测手动设 (超收上限 30%, 与 prod 默认一致)
        ReflectionTestUtils.setField(service, "overReceiveRate", new BigDecimal("0.30"));

        Supplier supplier = new Supplier();
        supplier.setId("SUP-1");
        supplier.setFactoryId(FACTORY);
        lenient().when(supplierRepository.findByIdAndFactoryId("SUP-1", FACTORY))
                .thenReturn(Optional.of(supplier));

        PurchaseOrder po = new PurchaseOrder();
        po.setId(PO_ID);
        po.setFactoryId(FACTORY);
        po.setSupplierId("SUP-1");
        po.setStatus(PurchaseOrderStatus.FINANCE_APPROVED);
        lenient().when(purchaseOrderRepository.findByIdAndFactoryIdForUpdate(PO_ID, FACTORY)).thenReturn(Optional.of(po));
        lenient().when(receiveRecordRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, PO_ID))
                .thenReturn(List.of());

        // PO 行: A=32.00 (合同价), B=无价. validateOverReceiveCap + 价继承都读这个 repository.
        lenient().when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID))
                .thenReturn(List.of(poItem(MAT_A, new BigDecimal("32.00")), poItem(MAT_B, null)));
        lenient().when(materialTypeRepository.findById(MAT_A))
                .thenReturn(Optional.of(rawMaterial(MAT_A)));
        lenient().when(materialTypeRepository.findById(MAT_B))
                .thenReturn(Optional.of(rawMaterial(MAT_B)));

        lenient().when(receiveRecordRepository.save(any(PurchaseReceiveRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("收货行未填价 → 继承 PO 行价; 总额按继承价算")
    void receiveItemBlankPrice_inheritsPoLinePrice() {
        CreateReceiveRecordRequest req = receiveReq(MAT_A, new BigDecimal("10"), null);

        PurchaseReceiveRecord rec = service.createReceiveRecord(FACTORY, req, USER_ID);

        // 行价继承 PO 的 32.00, 总额 = 10 × 32 = 320
        assertEquals(new BigDecimal("32.00"), rec.getItems().get(0).getUnitPrice());
        assertEquals(0, new BigDecimal("320.00").compareTo(rec.getTotalAmount()));
    }

    @Test
    @DisplayName("仓储请求伪造价格 → 仍严格继承已审批 PO 行价")
    void receiveItemExplicitPrice_cannotOverridePo() {
        CreateReceiveRecordRequest req = receiveReq(MAT_A, new BigDecimal("10"), new BigDecimal("30.00"));

        PurchaseReceiveRecord rec = service.createReceiveRecord(FACTORY, req, USER_ID);

        assertEquals(new BigDecimal("32.00"), rec.getItems().get(0).getUnitPrice());
        assertEquals(0, new BigDecimal("320.00").compareTo(rec.getTotalAmount()));
    }

    @Test
    @DisplayName("收货行未填价且 PO 该物料也无价 → 诚实保持 null, 不伪造 0")
    void receiveItemBlankPrice_poAlsoBlank_staysNull() {
        CreateReceiveRecordRequest req = receiveReq(MAT_B, new BigDecimal("5"), null);

        PurchaseReceiveRecord rec = service.createReceiveRecord(FACTORY, req, USER_ID);

        assertNull(rec.getItems().get(0).getUnitPrice());
        assertEquals(0, BigDecimal.ZERO.compareTo(rec.getTotalAmount()));
    }

    @Test
    @DisplayName("createReceiveRecord maps factoryNumber and originPlace to receive item")
    void createReceive_withFactoryNumber_mapsToReceiveItem() {
        CreateReceiveRecordRequest req = receiveReq(MAT_A, new BigDecimal("10"), null);
        req.getItems().get(0).setFactoryNumber("SC-321");
        req.getItems().get(0).setOriginPlace("四川成都");

        PurchaseReceiveRecord rec = service.createReceiveRecord(FACTORY, req, USER_ID);

        assertEquals("SC-321", rec.getItems().get(0).getFactoryNumber());
        assertEquals("四川成都", rec.getItems().get(0).getOriginPlace());
    }

    @Test
    @DisplayName("显式目标仓在草稿创建前完成归属与仓型校验")
    void createReceive_withWarehouse_validatesBeforePersistingDraft() {
        CreateReceiveRecordRequest req = receiveReq(MAT_A, new BigDecimal("10"), null);

        service.createReceiveRecord(FACTORY, req, USER_ID);

        verify(warehouseInventoryGuardService).assertCanReceive("WH-RAW", FACTORY, "RAW");
    }

    @Test
    @DisplayName("收货单位与 PO 行不一致 → 保存草稿前 fail-closed")
    void receiveUnitMismatch_isRejectedBeforeDraftPersist() {
        CreateReceiveRecordRequest req = receiveReq(MAT_A, new BigDecimal("10"), null);
        req.getItems().get(0).setUnit("case");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createReceiveRecord(FACTORY, req, USER_ID));

        assertEquals("MATERIAL_PACKAGING_SPEC_REQUIRED", error.getErrorCode());
    }

    @Test
    @DisplayName("仅业务审批、尚未完成财审 → 不得创建仓储收货单")
    void businessApprovedWithoutFinance_isRejected() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(PO_ID);
        po.setFactoryId(FACTORY);
        po.setStatus(PurchaseOrderStatus.APPROVED);
        when(purchaseOrderRepository.findByIdAndFactoryIdForUpdate(PO_ID, FACTORY))
                .thenReturn(Optional.of(po));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createReceiveRecord(
                        FACTORY, receiveReq(MAT_A, BigDecimal.ONE, null), USER_ID));

        assertEquals("PURCHASE_RECEIPT_FINANCE_APPROVAL_REQUIRED", error.getErrorCode());
    }

    private PurchaseOrderItem poItem(String materialTypeId, BigDecimal unitPrice) {
        PurchaseOrderItem it = new PurchaseOrderItem();
        it.setId(MAT_A.equals(materialTypeId) ? 1L : 2L);
        it.setPurchaseOrderId(PO_ID);
        it.setMaterialTypeId(materialTypeId);
        it.setUnitPrice(unitPrice);
        it.setUnit("kg");
        it.setPriceUnit("kg");
        it.setQuantity(new BigDecimal("100"));
        it.setReceivedQuantity(BigDecimal.ZERO);
        return it;
    }

    private RawMaterialType rawMaterial(String materialTypeId) {
        RawMaterialType material = new RawMaterialType();
        material.setId(materialTypeId);
        material.setFactoryId(FACTORY);
        material.setName(materialTypeId);
        material.setUnit("kg");
        return material;
    }

    private CreateReceiveRecordRequest receiveReq(String materialTypeId, BigDecimal qty, BigDecimal price) {
        CreateReceiveRecordRequest req = new CreateReceiveRecordRequest();
        req.setPurchaseOrderId(PO_ID);
        req.setSupplierId("SUP-1");
        req.setReceiveDate(LocalDate.of(2026, 6, 11));
        req.setWarehouseId("WH-RAW");
        CreateReceiveRecordRequest.ReceiveItemDTO item = new CreateReceiveRecordRequest.ReceiveItemDTO();
        item.setMaterialTypeId(materialTypeId);
        item.setMaterialName(materialTypeId);
        item.setReceivedQuantity(qty);
        item.setUnit("kg");
        item.setUnitPrice(price);
        req.setItems(List.of(item));
        return req;
    }
}
