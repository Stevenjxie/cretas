package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateCustomerMaterialArrivalNoticeRequest;
import com.cretas.aims.dto.inventory.CustomerMaterialArrivalReceiptRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.CustomerMaterialArrivalStatus;
import com.cretas.aims.entity.enums.InboundType;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.UnorderedInboundReason;
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
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(UnorderedInboundReason.CUSTOMER_MATERIAL, notice.getReason());
        assertEquals(CustomerMaterialArrivalStatus.PENDING_APPROVAL, notice.getStatus());
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

    @Test
    @DisplayName("客户来料未选择客户时在创建申请阶段拒绝且零库存写")
    void customerMaterialRequiresCustomerBeforeNoticeCreation() {
        CreateCustomerMaterialArrivalNoticeRequest request =
                new CreateCustomerMaterialArrivalNoticeRequest();
        request.setReason(UnorderedInboundReason.CUSTOMER_MATERIAL);

        var error = assertThrows(
                com.cretas.aims.exception.BusinessException.class,
                () -> service.create("F006", request, 7L));

        assertEquals("UNORDERED_INBOUND_CUSTOMER_REQUIRED", error.getErrorCode());
        verify(noticeRepository, never()).save(any());
        verify(materialBatchRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("赠予申请不要求客户且创建阶段不写库存")
    void giftRequestAllowsNoCustomerAndDoesNotCreateInventory() {
        when(noticeRepository.save(any(CustomerMaterialArrivalNotice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        CreateCustomerMaterialArrivalNoticeRequest request =
                new CreateCustomerMaterialArrivalNoticeRequest();
        request.setReason(UnorderedInboundReason.GIFT);
        request.setRemark("供应商赠送试用原料");

        CustomerMaterialArrivalNotice notice = service.create("F006", request, 7L);

        assertEquals(UnorderedInboundReason.GIFT, notice.getReason());
        assertNull(notice.getCustomerId());
        verify(customerRepository, never()).findByIdAndFactoryId(any(), any());
        verify(materialBatchRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("待审批申请通过后才转为仓储待收货任务")
    void approvalHandsRequestToWarehouseTaskQueue() {
        CustomerMaterialArrivalNotice notice = new CustomerMaterialArrivalNotice();
        notice.setId("NOTICE-PENDING");
        notice.setFactoryId("F006");
        notice.setStatus(CustomerMaterialArrivalStatus.PENDING_APPROVAL);
        when(noticeRepository.findByIdAndFactoryIdForUpdate("NOTICE-PENDING", "F006"))
                .thenReturn(Optional.of(notice));
        when(noticeRepository.save(any(CustomerMaterialArrivalNotice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CustomerMaterialArrivalNotice approved = service.approve(
                "F006", "NOTICE-PENDING", 88L, "实物来源清楚");

        assertEquals(CustomerMaterialArrivalStatus.OPEN, approved.getStatus());
        assertEquals(88L, approved.getReviewedBy());
        assertEquals("实物来源清楚", approved.getReviewRemark());
        assertNotNull(approved.getReviewedAt());
        verify(materialBatchRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("审批驳回只改申请状态且不生成仓储任务或库存")
    void rejectionStaysInApplicationDomain() {
        CustomerMaterialArrivalNotice notice = new CustomerMaterialArrivalNotice();
        notice.setId("NOTICE-PENDING");
        notice.setFactoryId("F006");
        notice.setStatus(CustomerMaterialArrivalStatus.PENDING_APPROVAL);
        when(noticeRepository.findByIdAndFactoryIdForUpdate("NOTICE-PENDING", "F006"))
                .thenReturn(Optional.of(notice));
        when(noticeRepository.save(any(CustomerMaterialArrivalNotice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CustomerMaterialArrivalNotice rejected = service.reject(
                "F006", "NOTICE-PENDING", 89L, "来源信息不完整");

        assertEquals(CustomerMaterialArrivalStatus.REJECTED, rejected.getStatus());
        assertEquals(89L, rejected.getReviewedBy());
        verify(materialBatchRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("驳回必须有可追溯原因")
    void rejectionRequiresReasonBeforeLoadingOrWritingRequest() {
        var error = assertThrows(com.cretas.aims.exception.BusinessException.class,
                () -> service.reject("F006", "NOTICE-PENDING", 89L, "  "));

        assertEquals(400, error.getCode());
        verify(noticeRepository, never()).findByIdAndFactoryIdForUpdate(any(), any());
        verify(noticeRepository, never()).save(any());
    }

    @Test
    @DisplayName("待审批申请不能绕过审批直接收货")
    void pendingApprovalCannotReceive() {
        CustomerMaterialArrivalNotice notice = new CustomerMaterialArrivalNotice();
        notice.setId("NOTICE-PENDING");
        notice.setFactoryId("F006");
        notice.setStatus(CustomerMaterialArrivalStatus.PENDING_APPROVAL);
        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F006", CustomerMaterialArrivalNoticeService.SOURCE_TYPE, "pending-key"))
                .thenReturn(Optional.empty());
        when(noticeRepository.findByIdAndFactoryIdForUpdate("NOTICE-PENDING", "F006"))
                .thenReturn(Optional.of(notice));
        CustomerMaterialArrivalReceiptRequest request = new CustomerMaterialArrivalReceiptRequest();
        request.setIdempotencyKey("pending-key");

        var error = assertThrows(com.cretas.aims.exception.BusinessException.class,
                () -> service.receive("F006", "NOTICE-PENDING", request, 17L));

        assertEquals("UNORDERED_INBOUND_NOT_RECEIVABLE", error.getErrorCode());
        verify(materialBatchRepository, never()).saveAndFlush(any());
        verify(rawMaterialTypeRepository, never()).findById(any());
    }

    @Test
    @DisplayName("仓储待收货列表只包含已审批通过的未完成任务")
    void receivingQueueExcludesApplicationOnlyStatuses() {
        when(noticeRepository.findByFactoryIdAndStatusInOrderByExpectedArrivalAtAscCreatedAtAsc(
                "F006", EnumSet.of(CustomerMaterialArrivalStatus.OPEN,
                        CustomerMaterialArrivalStatus.PARTIALLY_RECEIVED)))
                .thenReturn(List.of());

        service.list("F006", true);

        verify(noticeRepository).findByFactoryIdAndStatusInOrderByExpectedArrivalAtAscCreatedAtAsc(
                "F006", EnumSet.of(CustomerMaterialArrivalStatus.OPEN,
                        CustomerMaterialArrivalStatus.PARTIALLY_RECEIVED));
    }

    @Test
    @DisplayName("已审批交接的任务不能再从申请域撤回")
    void approvedTaskCannotBeWithdrawnFromApplicationDomain() {
        CustomerMaterialArrivalNotice notice = new CustomerMaterialArrivalNotice();
        notice.setId("NOTICE-OPEN");
        notice.setFactoryId("F006");
        notice.setStatus(CustomerMaterialArrivalStatus.OPEN);
        when(noticeRepository.findByIdAndFactoryIdForUpdate("NOTICE-OPEN", "F006"))
                .thenReturn(Optional.of(notice));

        var error = assertThrows(com.cretas.aims.exception.BusinessException.class,
                () -> service.cancel("F006", "NOTICE-OPEN"));

        assertEquals("UNORDERED_INBOUND_WITHDRAW_NOT_ALLOWED", error.getErrorCode());
        verify(noticeRepository, never()).save(any());
    }

    @Test
    @DisplayName("赠予实际收货生成公司所有库存且不挂客户")
    void giftReceiptCreatesCompanyOwnedInventory() {
        CustomerMaterialArrivalNotice notice = new CustomerMaterialArrivalNotice();
        notice.setId("NOTICE-GIFT");
        notice.setFactoryId("F006");
        notice.setReason(UnorderedInboundReason.GIFT);
        notice.setCustomerId(null);
        notice.setStatus(CustomerMaterialArrivalStatus.OPEN);
        notice.setReceiptCount(0);
        when(noticeRepository.findByIdAndFactoryIdForUpdate("NOTICE-GIFT", "F006"))
                .thenReturn(Optional.of(notice));
        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F006", CustomerMaterialArrivalNoticeService.SOURCE_TYPE, "gift-key-1"))
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
        request.setIdempotencyKey("gift-key-1");
        request.setMaterialTypeId("RM-1");
        request.setWarehouseId("WH-RAW");
        request.setReceivedQuantity(new BigDecimal("8"));
        request.setUnit("kg");
        request.setCompleteNotice(true);

        assertSame(mapped, service.receive("F006", "NOTICE-GIFT", request, 17L));

        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).saveAndFlush(batchCaptor.capture());
        MaterialBatch saved = batchCaptor.getValue();
        assertEquals(InventoryOwnership.COMPANY_OWNED, saved.getOwnership());
        assertEquals(InboundType.OTHER, saved.getInboundType());
        assertNull(saved.getOwnerCustomerId());
        assertEquals("NOTICE-GIFT", saved.getSourceDocId());
    }
}
