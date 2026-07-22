package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CustomerSuppliedMaterialReceiptRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.InboundType;
import com.cretas.aims.entity.enums.SalesOrderSuppliedMaterialRequirementStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesOrderSuppliedMaterialRequirement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderSuppliedMaterialRequirementRepository;
import com.cretas.aims.service.factory.WarehouseInventoryGuardService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.unit.UnitUsageScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SalesOrderSuppliedMaterialRequirementReceiptTest {

    @Mock private SalesOrderSuppliedMaterialRequirementRepository requirementRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private FactoryWarehouseRepository factoryWarehouseRepository;
    @Mock private WarehouseInventoryGuardService warehouseInventoryGuardService;
    @Mock private UnitContractService unitContractService;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchMapper materialBatchMapper;
    @Mock private AttachmentRepository attachmentRepository;

    private SalesOrderSuppliedMaterialRequirementService service;

    @BeforeEach
    void setUp() {
        service = new SalesOrderSuppliedMaterialRequirementService(
                requirementRepository,
                salesOrderItemRepository,
                salesOrderRepository,
                rawMaterialTypeRepository,
                factoryWarehouseRepository,
                warehouseInventoryGuardService,
                unitContractService,
                materialBatchRepository,
                materialBatchMapper,
                attachmentRepository);
        CanonicalUnit kg = new CanonicalUnit(
                "kg", UnitDimension.MASS, "kg", BigDecimal.ONE, "千克", 4,
                Set.of(UnitUsageScope.INVENTORY_QUANTITY), "kg", true);
        lenient().when(unitContractService.normalize("F006", "kg"))
                .thenReturn(new UnitNormalizationResult("kg", "kg", kg));
        lenient().when(unitContractService.supportsUsage(
                "F006", "kg", UnitUsageScope.INVENTORY_QUANTITY)).thenReturn(true);
    }

    @Test
    void confirmsRemainingQuantityAsCustomerOwnedInventoryAndCompletesTask() {
        CustomerSuppliedMaterialReceiptRequest request = request("receipt-key-1", "6");
        SalesOrderSuppliedMaterialRequirement requirement = requirement("4");
        RawMaterialType material = new RawMaterialType();
        material.setFactoryId("F006");
        material.setId("material-1");
        material.setUnit("kg");
        material.setIsActive(true);
        material.setShelfLifeDays(7);
        MaterialBatchDTO response = new MaterialBatchDTO();

        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F006", "SALES_ORDER_CUSTOMER_SUPPLIED", "receipt-key-1"))
                .thenReturn(Optional.empty());
        when(requirementRepository.findByIdAndFactoryIdForUpdate("task-1", "F006"))
                .thenReturn(Optional.of(requirement));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("order-1", "F006"))
                .thenReturn(Optional.of(approvedOrder()));
        when(salesOrderItemRepository.findById(726L))
                .thenReturn(Optional.of(approvedOrderItem()));
        when(attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                "F006", Attachment.EntityType.CUSTOMER_SUPPLIED_RECEIPT, "task-1"))
                .thenReturn(1L);
        when(rawMaterialTypeRepository.findById("material-1")).thenReturn(Optional.of(material));
        when(materialBatchRepository.saveAndFlush(any(MaterialBatch.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(materialBatchMapper.toDTO(any(MaterialBatch.class))).thenReturn(response);

        assertThat(service.receive("F006", "task-1", request, 1309L)).isSameAs(response);

        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).saveAndFlush(batchCaptor.capture());
        MaterialBatch batch = batchCaptor.getValue();
        assertThat(batch.getReceiptQuantity()).isEqualByComparingTo("6");
        assertThat(batch.getQuantityUnit()).isEqualTo("kg");
        assertThat(batch.getOwnership()).isEqualTo(InventoryOwnership.CUSTOMER_OWNED);
        assertThat(batch.getInboundType()).isEqualTo(InboundType.CUSTOMER_SUPPLIED);
        assertThat(batch.getOwnerCustomerId()).isEqualTo("customer-1");
        assertThat(batch.getSourceSalesOrderId()).isEqualTo("order-1");
        assertThat(batch.getSourceSalesOrderItemId()).isEqualTo("726");
        assertThat(batch.getSourceDocId()).isEqualTo("task-1");
        assertThat(batch.getSourceEventKey()).isEqualTo("receipt-key-1");
        assertThat(batch.getSupplierId()).isNull();
        assertThat(batch.getCreatedBy()).isEqualTo(1309L);
        assertThat(requirement.getReceivedQuantity()).isEqualByComparingTo("10");
        assertThat(requirement.getStatus())
                .isEqualTo(SalesOrderSuppliedMaterialRequirementStatus.COMPLETED);
        verify(warehouseInventoryGuardService).assertCanReceive("warehouse-raw", "F006", "RAW");
    }

    @Test
    void replaysSameIdempotencyKeyWithoutSecondTaskOrInventoryMutation() {
        CustomerSuppliedMaterialReceiptRequest request = request("receipt-key-replay", "2");
        MaterialBatch existing = new MaterialBatch();
        existing.setSourceDocId("task-1");
        MaterialBatchDTO response = new MaterialBatchDTO();
        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F006", "SALES_ORDER_CUSTOMER_SUPPLIED", "receipt-key-replay"))
                .thenReturn(Optional.of(existing));
        when(materialBatchMapper.toDTO(existing)).thenReturn(response);

        assertThat(service.receive("F006", "task-1", request, 1309L)).isSameAs(response);
        verify(requirementRepository, never()).findByIdAndFactoryIdForUpdate(any(), any());
        verify(materialBatchRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsSameIdempotencyKeyWhenItBelongsToAnotherTask() {
        CustomerSuppliedMaterialReceiptRequest request = request("receipt-key-cross-task", "2");
        MaterialBatch existing = new MaterialBatch();
        existing.setSourceDocId("task-other");
        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F006", "SALES_ORDER_CUSTOMER_SUPPLIED", "receipt-key-cross-task"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.receive("F006", "task-1", request, 1309L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo("CUSTOMER_SUPPLIED_IDEMPOTENCY_SCOPE_CONFLICT");
        verify(requirementRepository, never()).findByIdAndFactoryIdForUpdate(any(), any());
        verify(materialBatchRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsReceiptWhenSourceOrderIsNoLongerApproved() {
        CustomerSuppliedMaterialReceiptRequest request = request("receipt-key-cancelled", "2");
        SalesOrderSuppliedMaterialRequirement requirement = requirement("0");
        SalesOrder cancelled = approvedOrder();
        cancelled.setStatus(SalesOrderStatus.CANCELLED);
        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F006", "SALES_ORDER_CUSTOMER_SUPPLIED", "receipt-key-cancelled"))
                .thenReturn(Optional.empty());
        when(requirementRepository.findByIdAndFactoryIdForUpdate("task-1", "F006"))
                .thenReturn(Optional.of(requirement));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("order-1", "F006"))
                .thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.receive("F006", "task-1", request, 1309L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo("CUSTOMER_SUPPLIED_SOURCE_ORDER_NOT_APPROVED");
        verify(materialBatchRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsMissingReceiptEvidenceWithoutPartialInventoryOrTaskWrite() {
        CustomerSuppliedMaterialReceiptRequest request = request("receipt-key-no-proof", "2");
        SalesOrderSuppliedMaterialRequirement requirement = requirement("0");
        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F006", "SALES_ORDER_CUSTOMER_SUPPLIED", "receipt-key-no-proof"))
                .thenReturn(Optional.empty());
        when(requirementRepository.findByIdAndFactoryIdForUpdate("task-1", "F006"))
                .thenReturn(Optional.of(requirement));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("order-1", "F006"))
                .thenReturn(Optional.of(approvedOrder()));
        when(salesOrderItemRepository.findById(726L))
                .thenReturn(Optional.of(approvedOrderItem()));
        when(attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                "F006", Attachment.EntityType.CUSTOMER_SUPPLIED_RECEIPT, "task-1"))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.receive("F006", "task-1", request, 1309L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo("CUSTOMER_SUPPLIED_RECEIPT_ATTACHMENT_REQUIRED");
        verify(materialBatchRepository, never()).saveAndFlush(any());
        verify(requirementRepository, never()).save(any());
    }

    @Test
    void rejectsReceiptQuantityBeyondMaterialBatchPrecisionBeforeAnyWrite() {
        CustomerSuppliedMaterialReceiptRequest request = request("receipt-key-precision", "1.001");

        assertThatThrownBy(() -> service.receive("F006", "task-1", request, 1309L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo("CUSTOMER_SUPPLIED_QUANTITY_PRECISION_INVALID");
        verify(requirementRepository, never()).findByIdAndFactoryIdForUpdate(any(), any());
        verify(materialBatchRepository, never()).saveAndFlush(any());
    }

    @Test
    void translatesSourceEventUniqueRaceToConflictWithoutCumulativeReceiptWrite() {
        CustomerSuppliedMaterialReceiptRequest request = request("receipt-key-race", "2");
        SalesOrderSuppliedMaterialRequirement requirement = requirement("0");
        RawMaterialType material = new RawMaterialType();
        material.setFactoryId("F006");
        material.setId("material-1");
        material.setUnit("kg");
        material.setIsActive(true);

        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F006", "SALES_ORDER_CUSTOMER_SUPPLIED", "receipt-key-race"))
                .thenReturn(Optional.empty());
        when(requirementRepository.findByIdAndFactoryIdForUpdate("task-1", "F006"))
                .thenReturn(Optional.of(requirement));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("order-1", "F006"))
                .thenReturn(Optional.of(approvedOrder()));
        when(salesOrderItemRepository.findById(726L))
                .thenReturn(Optional.of(approvedOrderItem()));
        when(attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                "F006", Attachment.EntityType.CUSTOMER_SUPPLIED_RECEIPT, "task-1"))
                .thenReturn(1L);
        when(rawMaterialTypeRepository.findById("material-1")).thenReturn(Optional.of(material));
        when(materialBatchRepository.saveAndFlush(any(MaterialBatch.class)))
                .thenThrow(new DataIntegrityViolationException("uq_material_batch_source_event"));

        assertThatThrownBy(() -> service.receive("F006", "task-1", request, 1309L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo("CUSTOMER_SUPPLIED_IDEMPOTENCY_SCOPE_CONFLICT");
        assertThat(requirement.getReceivedQuantity()).isEqualByComparingTo("0");
        verify(requirementRepository, never()).save(any());
    }

    @Test
    void rejectsQuantityAboveLockedRemainingCapacity() {
        CustomerSuppliedMaterialReceiptRequest request = request("receipt-key-over", "7");
        SalesOrderSuppliedMaterialRequirement requirement = requirement("4");
        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F006", "SALES_ORDER_CUSTOMER_SUPPLIED", "receipt-key-over"))
                .thenReturn(Optional.empty());
        when(requirementRepository.findByIdAndFactoryIdForUpdate("task-1", "F006"))
                .thenReturn(Optional.of(requirement));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("order-1", "F006"))
                .thenReturn(Optional.of(approvedOrder()));
        when(salesOrderItemRepository.findById(726L))
                .thenReturn(Optional.of(approvedOrderItem()));

        assertThatThrownBy(() -> service.receive("F006", "task-1", request, 1309L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo("CUSTOMER_SUPPLIED_RECEIPT_EXCEEDS_REMAINING");
        verify(materialBatchRepository, never()).saveAndFlush(any());
        verify(requirementRepository, never()).save(any());
    }

    private static CustomerSuppliedMaterialReceiptRequest request(String key, String quantity) {
        CustomerSuppliedMaterialReceiptRequest request = new CustomerSuppliedMaterialReceiptRequest();
        request.setIdempotencyKey(key);
        request.setReceivedQuantity(new BigDecimal(quantity));
        return request;
    }

    private static SalesOrderSuppliedMaterialRequirement requirement(String received) {
        SalesOrderSuppliedMaterialRequirement requirement = new SalesOrderSuppliedMaterialRequirement();
        requirement.setId("task-1");
        requirement.setFactoryId("F006");
        requirement.setCustomerId("customer-1");
        requirement.setSalesOrderId("order-1");
        requirement.setSalesOrderItemId(726L);
        requirement.setMaterialTypeId("material-1");
        requirement.setMaterialName("客户原料A");
        requirement.setExpectedQuantity(new BigDecimal("10"));
        requirement.setReceivedQuantity(new BigDecimal(received));
        requirement.setUnit("kg");
        requirement.setTargetWarehouseId("warehouse-raw");
        requirement.setStatus(SalesOrderSuppliedMaterialRequirementStatus.PENDING);
        return requirement;
    }

    private static SalesOrder approvedOrder() {
        SalesOrder order = new SalesOrder();
        order.setId("order-1");
        order.setFactoryId("F006");
        order.setCustomerId("customer-1");
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        return order;
    }

    private static SalesOrderItem approvedOrderItem() {
        SalesOrderItem item = new SalesOrderItem();
        item.setId(726L);
        item.setSalesOrderId("order-1");
        return item;
    }
}
