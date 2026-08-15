package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.enums.TaxTreatment;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import org.springframework.test.util.ReflectionTestUtils;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl createPurchaseOrder nullable unitPrice")
class PurchaseServiceImplCreateOrderNullablePriceTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private SupplierMaterialRepository supplierMaterialRepository;

    private PurchaseServiceImpl service;

    private static final String FACTORY = "RES_3101_009";
    private static final String SUPPLIER_ID = "SUP-001";

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                null,
                supplierRepository,
                materialTypeRepository,
                null,
                null,
                null,
                null,
                null);
        ReflectionTestUtils.setField(service, "supplierMaterialRepository", supplierMaterialRepository);
    }

    @Test
    @DisplayName("chef requisition converted before pricing: null unitPrice should create draft PO, not NPE")
    void createPurchaseOrder_nullUnitPrice_skipsAmountAggregation() {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setFactoryId(FACTORY);
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY))
                .thenReturn(Optional.of(supplier));
        when(purchaseOrderRepository.countByFactoryIdAndDate(any(), any(LocalDate.class)))
                .thenReturn(0L);
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(inv -> {
            PurchaseOrder order = inv.getArgument(0);
            if (order.getId() == null) order.setId("PO-uuid-001");
            return order;
        });
        when(purchaseOrderItemRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // 2026-08-15 补夹具: applySupplierPurchaseContract 后来加了「查物料 + 查供应关系」,
        // 这条用例此前一直红在 ResourceNotFoundException: 采购物料不存在: rm_qhj_002 ——
        // 从来没跑到下面那两条「不许伪造 0 价」的断言上。
        // ⚠️ 物料与供应关系都【不给价】, 正是本用例要守的场景: 价格未知就该保持 null。
        RawMaterialType material = new RawMaterialType();
        material.setId("rm_qhj_002");
        material.setFactoryId(FACTORY);
        material.setName("青花椒");
        material.setUnit("kg");
        material.setTaxTreatment(TaxTreatment.TAXABLE);
        material.setTaxRate(TaxRate.TAX_13);
        when(materialTypeRepository.findById("rm_qhj_002")).thenReturn(Optional.of(material));

        SupplierMaterial relation = new SupplierMaterial();
        relation.setId("REL-qhj-002");
        relation.setFactoryId(FACTORY);
        relation.setSupplierId(SUPPLIER_ID);
        relation.setMaterialTypeId("rm_qhj_002");
        relation.setPurchaseUnit("kg");
        relation.setActive(true);
        when(supplierMaterialRepository.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
                FACTORY, SUPPLIER_ID, "rm_qhj_002")).thenReturn(true);
        when(supplierMaterialRepository.findByFactoryIdAndSupplierIdAndMaterialTypeId(
                FACTORY, SUPPLIER_ID, "rm_qhj_002")).thenReturn(Optional.of(relation));

        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId("rm_qhj_002");
        item.setMaterialName("青花椒");
        item.setQuantity(new BigDecimal("0.75"));
        item.setUnit("kg");
        item.setUnitPrice(null);

        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setSupplierId(SUPPLIER_ID);
        request.setOrderDate(LocalDate.of(2026, 6, 6));
        request.setItems(List.of(item));

        PurchaseOrder order = service.createPurchaseOrder(FACTORY, request, 1610L);

        assertEquals(BigDecimal.ZERO, order.getTotalAmount());
        assertEquals(BigDecimal.ZERO, order.getTaxAmount());

        ArgumentCaptor<Iterable<PurchaseOrderItem>> itemsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(purchaseOrderItemRepository).saveAll(itemsCaptor.capture());
        PurchaseOrderItem savedItem = itemsCaptor.getValue().iterator().next();
        assertEquals("rm_qhj_002", savedItem.getMaterialTypeId());
        assertEquals(new BigDecimal("0.75"), savedItem.getQuantity());
        assertNull(savedItem.getUnitPrice(), "unknown purchase price must remain null, not fake zero");
        assertNull(savedItem.getLineAmount(), "line amount remains unknown until采购补价");
    }
}
