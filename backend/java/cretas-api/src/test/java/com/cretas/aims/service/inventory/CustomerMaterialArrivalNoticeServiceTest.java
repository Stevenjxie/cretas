package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateCustomerMaterialArrivalNoticeRequest;
import com.cretas.aims.dto.inventory.CustomerMaterialArrivalReceiptRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.CustomerMaterialArrivalStatus;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.CustomerMaterialArrivalNotice;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.CustomerMaterialArrivalNoticeRepository;
import com.cretas.aims.service.factory.WarehouseInventoryGuardService;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.unit.UnitUsageScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Set;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("运营客户来料预告")
class CustomerMaterialArrivalNoticeServiceTest {

    @Mock private CustomerMaterialArrivalNoticeRepository noticeRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private FactoryWarehouseRepository warehouseRepository;
    @Mock private WarehouseInventoryGuardService warehouseInventoryGuardService;
    @Mock private UnitContractService unitContractService;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchMapper materialBatchMapper;

    private CustomerMaterialArrivalNoticeService service;

    @BeforeEach
    void setUp() {
        service = new CustomerMaterialArrivalNoticeService(
                noticeRepository,
                customerRepository,
                rawMaterialTypeRepository,
                warehouseRepository,
                warehouseInventoryGuardService,
                unitContractService,
                materialBatchRepository,
                materialBatchMapper);
    }

    @Test
    @DisplayName("创建预告只冻结客户，不创建原料批次或伪造物料数量")
    void createNoticeDoesNotCreateInventory() {
        Customer customer = new Customer();
        customer.setId("CUSTOMER-1");
        customer.setFactoryId("F006");
        customer.setIsActive(true);
        when(customerRepository.findByIdAndFactoryId("CUSTOMER-1", "F006"))
                .thenReturn(Optional.of(customer));
        when(noticeRepository.save(any(CustomerMaterialArrivalNotice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreateCustomerMaterialArrivalNoticeRequest request =
                new CreateCustomerMaterialArrivalNoticeRequest();
        request.setCustomerId("CUSTOMER-1");
        request.setRemark("客户今天可能送货");

        CustomerMaterialArrivalNotice notice = service.create("F006", request, 7L);

        assertEquals("CUSTOMER-1", notice.getCustomerId());
        assertEquals(CustomerMaterialArrivalStatus.OPEN, notice.getStatus());
        assertEquals(0, notice.getReceiptCount());
        assertNull(notice.getExpectedArrivalAt());
        verify(materialBatchRepository, never()).saveAndFlush(any());
        verify(rawMaterialTypeRepository, never()).findById(any());
    }

    @Test
    @DisplayName("仓储实收才生成同客户、未绑定销售订单的原料库存")
    void warehouseReceiptCreatesUnassignedCustomerOwnedInventory() {
        CustomerMaterialArrivalNotice notice = new CustomerMaterialArrivalNotice();
        notice.setId("NOTICE-1");
        notice.setFactoryId("F006");
        notice.setCustomerId("CUSTOMER-1");
        notice.setStatus(CustomerMaterialArrivalStatus.OPEN);
        notice.setReceiptCount(0);
        when(noticeRepository.findByIdAndFactoryIdForUpdate("NOTICE-1", "F006"))
                .thenReturn(Optional.of(notice));
        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F006", CustomerMaterialArrivalNoticeService.SOURCE_TYPE, "arrival-key-1"))
                .thenReturn(Optional.empty());

        RawMaterialType material = new RawMaterialType();
        material.setId("RM-1");
        material.setFactoryId("F006");
        material.setIsActive(true);
        material.setUnit("kg");
        when(rawMaterialTypeRepository.findById("RM-1")).thenReturn(Optional.of(material));

        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setId("WH-RAW");
        warehouse.setFactoryId("F006");
        warehouse.setIsActive(true);
        when(warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull("WH-RAW", "F006"))
                .thenReturn(Optional.of(warehouse));

        CanonicalUnit kg = new CanonicalUnit(
                "kg", UnitDimension.MASS, "kg", BigDecimal.ONE, "千克", 4,
                Set.of(UnitUsageScope.INVENTORY_QUANTITY), "kg", true);
        when(unitContractService.normalize("F006", "kg"))
                .thenReturn(new UnitNormalizationResult("kg", "kg", kg));
        when(unitContractService.supportsUsage(
                "F006", "kg", UnitUsageScope.INVENTORY_QUANTITY)).thenReturn(true);
        when(materialBatchRepository.saveAndFlush(any(MaterialBatch.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(noticeRepository.save(any(CustomerMaterialArrivalNotice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MaterialBatchDTO mapped = new MaterialBatchDTO();
        when(materialBatchMapper.toDTO(any(MaterialBatch.class))).thenReturn(mapped);

        CustomerMaterialArrivalReceiptRequest request = new CustomerMaterialArrivalReceiptRequest();
        request.setIdempotencyKey("arrival-key-1");
        request.setMaterialTypeId("RM-1");
        request.setWarehouseId("WH-RAW");
        request.setReceivedQuantity(new BigDecimal("1250.50"));
        request.setUnit("kg");
        request.setCompleteNotice(true);

        assertSame(mapped, service.receive("F006", "NOTICE-1", request, 17L));

        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).saveAndFlush(batchCaptor.capture());
        MaterialBatch saved = batchCaptor.getValue();
        assertEquals(InventoryOwnership.CUSTOMER_OWNED, saved.getOwnership());
        assertEquals("CUSTOMER-1", saved.getOwnerCustomerId());
        assertNull(saved.getSourceSalesOrderId());
        assertEquals("NOTICE-1", saved.getSourceDocId());
        assertEquals(new BigDecimal("1250.50"), saved.getReceiptQuantity());
        assertEquals(CustomerMaterialArrivalStatus.RECEIVED, notice.getStatus());
        assertEquals(1, notice.getReceiptCount());
    }
}
