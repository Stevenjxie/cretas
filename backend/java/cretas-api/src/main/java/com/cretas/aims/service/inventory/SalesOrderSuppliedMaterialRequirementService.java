package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.inventory.CustomerSuppliedMaterialReceivingTaskResponse;
import com.cretas.aims.dto.inventory.CustomerSuppliedMaterialReceiptRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.InboundType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderSuppliedMaterialRequirementStatus;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesOrderSuppliedMaterialRequirement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderSuppliedMaterialRequirementRepository;
import com.cretas.aims.service.factory.WarehouseInventoryGuardService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitUsageScope;
import com.cretas.aims.mapper.MaterialBatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persistence, warehouse-task projection and constrained receipt boundary for
 * sales-order customer-supplied materials. The requirement row is the task identity;
 * no parallel receiving-task table or sales-side inventory write is introduced.
 */
@Service
@RequiredArgsConstructor
public class SalesOrderSuppliedMaterialRequirementService {

    private static final EnumSet<SalesOrderStatus> APPROVAL_COMPLETED_STATUSES = EnumSet.of(
            SalesOrderStatus.FINANCE_APPROVED,
            SalesOrderStatus.PROCESSING,
            SalesOrderStatus.PARTIAL_DELIVERED,
            SalesOrderStatus.COMPLETED);

    private static final EnumSet<SalesOrderSuppliedMaterialRequirementStatus> OPEN_STATUSES =
            EnumSet.of(
                    SalesOrderSuppliedMaterialRequirementStatus.PENDING,
                    SalesOrderSuppliedMaterialRequirementStatus.PARTIALLY_RECEIVED);

    private final SalesOrderSuppliedMaterialRequirementRepository requirementRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final FactoryWarehouseRepository factoryWarehouseRepository;
    private final WarehouseInventoryGuardService warehouseInventoryGuardService;
    private final UnitContractService unitContractService;
    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialBatchMapper materialBatchMapper;
    private final AttachmentRepository attachmentRepository;

    @Transactional
    public List<SalesOrderSuppliedMaterialRequirement> createForOrder(
            SalesOrder order,
            List<CreateSalesOrderRequest.SuppliedMaterialRequirementDTO> requests) {
        assertOrderIdentity(order);
        assertPayloadContract(order, requests, false);
        return persistNewRequirements(order, requests);
    }

    @Transactional
    public List<SalesOrderSuppliedMaterialRequirement> updateForOrder(
            SalesOrder order,
            List<CreateSalesOrderRequest.SuppliedMaterialRequirementDTO> requests,
            boolean replacingSalesOrderItems) {
        assertOrderIdentity(order);
        List<SalesOrderSuppliedMaterialRequirement> existing =
                requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(order.getId());

        assertPayloadContract(order, requests, true);
        if (!isCustomerSuppliedTollOrder(order)) {
            softDelete(existing);
            return List.of();
        }

        if (requests == null) {
            if (existing.isEmpty()) {
                throw missingRequirements();
            }
            return existing;
        }

        if (replacingSalesOrderItems && requests.stream()
                .anyMatch(request -> request.getSalesOrderItemId() != null)) {
            throw new BusinessException(400,
                    "替换销售订单行时不能继续引用旧的 salesOrderItemId")
                    .withCode("CUSTOMER_SUPPLIED_ORDER_ITEM_REPLACED")
                    .withHint("请清空客供物料的销售订单行映射后再保存，或先保存订单行再单独更新客供物料")
                    .withHintTarget("suppliedMaterials");
        }

        softDelete(existing);
        return persistNewRequirements(order, requests);
    }

    @Transactional
    public List<SalesOrderSuppliedMaterialRequirement> copyForOrder(
            SalesOrder source,
            SalesOrder target,
            Map<Long, Long> copiedSalesOrderItemIds) {
        assertOrderIdentity(source);
        assertOrderIdentity(target);

        List<SalesOrderSuppliedMaterialRequirement> sourceRequirements =
                requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(source.getId());
        if (isCustomerSuppliedTollOrder(source) && sourceRequirements.isEmpty()) {
            throw new BusinessException(409, "源销售订单缺少客供物料需求，不能复制")
                    .withCode("CUSTOMER_SUPPLIED_REQUIREMENTS_MISSING_ON_COPY")
                    .withHint("请先编辑源草稿并补全客供物料需求");
        }
        if (!isCustomerSuppliedTollOrder(source) && !sourceRequirements.isEmpty()) {
            throw new BusinessException(409, "源销售订单存在与供料模式冲突的客供物料需求")
                    .withCode("CUSTOMER_SUPPLIED_REQUIREMENTS_MODE_CONFLICT");
        }

        List<SalesOrderSuppliedMaterialRequirement> copies = new ArrayList<>();
        for (SalesOrderSuppliedMaterialRequirement sourceRequirement : sourceRequirements) {
            Long copiedSalesOrderItemId = null;
            if (sourceRequirement.getSalesOrderItemId() != null) {
                copiedSalesOrderItemId = copiedSalesOrderItemIds.get(
                        sourceRequirement.getSalesOrderItemId());
                if (copiedSalesOrderItemId == null) {
                    throw new BusinessException(409,
                            "源客供物料引用的销售订单行无法映射到复制订单")
                            .withCode("CUSTOMER_SUPPLIED_ORDER_ITEM_COPY_MAPPING_MISSING");
                }
            }
            SalesOrderSuppliedMaterialRequirement copy = new SalesOrderSuppliedMaterialRequirement();
            copy.setFactoryId(target.getFactoryId());
            copy.setCustomerId(target.getCustomerId());
            copy.setSalesOrderId(target.getId());
            copy.setSalesOrderItemId(copiedSalesOrderItemId);
            copy.setMaterialTypeId(sourceRequirement.getMaterialTypeId());
            copy.setMaterialName(sourceRequirement.getMaterialName());
            copy.setExpectedQuantity(sourceRequirement.getExpectedQuantity());
            copy.setReceivedQuantity(BigDecimal.ZERO);
            copy.setUnit(sourceRequirement.getUnit());
            copy.setExpectedArrivalAt(sourceRequirement.getExpectedArrivalAt());
            copy.setTargetWarehouseId(sourceRequirement.getTargetWarehouseId());
            copy.setStatus(SalesOrderSuppliedMaterialRequirementStatus.PENDING);
            copies.add(copy);
        }
        return requirementRepository.saveAll(copies);
    }

    @Transactional(readOnly = true)
    public List<CustomerSuppliedMaterialReceivingTaskResponse> getPendingReceivingTasks(
            String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "工厂ID不能为空")
                    .withCode("FACTORY_ID_REQUIRED");
        }
        return requirementRepository.findPendingReceivingTasks(
                        factoryId, APPROVAL_COMPLETED_STATUSES, OPEN_STATUSES).stream()
                .map(this::toTaskResponse)
                .toList();
    }

    /**
     * Confirm one partial receipt from the warehouse task. The requirement row
     * is the task and concurrency lock; no parallel task table is introduced.
     */
    @Transactional
    public MaterialBatchDTO receive(
            String factoryId,
            String taskId,
            CustomerSuppliedMaterialReceiptRequest request,
            Long userId) {
        final String sourceType = CustomerSuppliedMaterialReceivingTaskResponse.SOURCE;
        MaterialBatch replay = materialBatchRepository
                .findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                        factoryId, sourceType, request.getIdempotencyKey())
                .orElse(null);
        if (replay != null) {
            assertReplayBelongsToTask(replay, taskId);
            return materialBatchMapper.toDTO(replay);
        }

        SalesOrderSuppliedMaterialRequirement requirement = requirementRepository
                .findByIdAndFactoryIdForUpdate(taskId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "客供物料待收货任务不存在或不属于当前工厂")
                        .withCode("CUSTOMER_SUPPLIED_RECEIVING_TASK_NOT_FOUND"));

        replay = materialBatchRepository
                .findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                        factoryId, sourceType, request.getIdempotencyKey())
                .orElse(null);
        if (replay != null) {
            assertReplayBelongsToTask(replay, taskId);
            return materialBatchMapper.toDTO(replay);
        }
        SalesOrder sourceOrder = salesOrderRepository
                .findByIdAndFactoryIdForUpdate(requirement.getSalesOrderId(), factoryId)
                .orElseThrow(() -> new BusinessException(409, "客供物料来源销售订单不存在或不属于当前工厂")
                        .withCode("CUSTOMER_SUPPLIED_SOURCE_ORDER_INVALID"));
        if (!APPROVAL_COMPLETED_STATUSES.contains(sourceOrder.getStatus())) {
            throw new BusinessException(409, "来源销售订单当前未完成审批，不能继续客户来料收货")
                    .withCode("CUSTOMER_SUPPLIED_SOURCE_ORDER_NOT_APPROVED")
                    .withHint("请返回销售订单核对审批状态；系统没有写入库存");
        }
        if (!OPEN_STATUSES.contains(requirement.getStatus())) {
            throw new BusinessException(409, "该客供物料任务已完成或已关闭")
                    .withCode("CUSTOMER_SUPPLIED_RECEIVING_TASK_CLOSED");
        }
        if (request.getReceivedQuantity().compareTo(requirement.getRemainingQuantity()) > 0) {
            throw new BusinessException(409, "本次实收数量超过任务剩余待收数量")
                    .withCode("CUSTOMER_SUPPLIED_RECEIPT_EXCEEDS_REMAINING")
                    .withHint("剩余待收 " + requirement.getRemainingQuantity() + requirement.getUnit());
        }
        if (request.getProductionDate() != null && request.getExpireDate() != null
                && request.getExpireDate().isBefore(request.getProductionDate())) {
            throw new BusinessException(400, "到期日期不能早于生产日期")
                    .withCode("CUSTOMER_SUPPLIED_RECEIPT_DATE_INVALID");
        }
        if (attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                factoryId, Attachment.EntityType.CUSTOMER_SUPPLIED_RECEIPT, taskId) <= 0) {
            throw new BusinessException(409, "确认客供料收货前必须上传客户送货单或收货凭证")
                    .withCode("CUSTOMER_SUPPLIED_RECEIPT_ATTACHMENT_REQUIRED")
                    .withHint("请在仓储待收货任务中拍照或上传凭证后再确认");
        }

        LocalDate receiptDate = LocalDate.now();
        RawMaterialType material = rawMaterialTypeRepository.findById(requirement.getMaterialTypeId())
                .filter(candidate -> Objects.equals(factoryId, candidate.getFactoryId()))
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()))
                .orElseThrow(() -> new BusinessException(409, "客供物料档案已停用、不存在或不属于当前工厂，不能收货")
                        .withCode("CUSTOMER_SUPPLIED_MATERIAL_INACTIVE"));
        warehouseInventoryGuardService.assertCanReceive(
                requirement.getTargetWarehouseId(), factoryId, "RAW");
        LocalDate expireDate = request.getExpireDate();
        if (expireDate == null && material.getShelfLifeDays() != null) {
            expireDate = receiptDate.plusDays(material.getShelfLifeDays());
        }

        String deterministicSuffix = UUID.nameUUIDFromBytes(
                        (factoryId + ":" + request.getIdempotencyKey())
                                .getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 12).toUpperCase();
        MaterialBatch batch = new MaterialBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setFactoryId(factoryId);
        batch.setBatchNumber("CMR-" + receiptDate.toString().replace("-", "") + "-" + deterministicSuffix);
        batch.setMaterialTypeId(requirement.getMaterialTypeId());
        batch.setSupplierId(null);
        batch.setReceiptDate(receiptDate);
        batch.setProductionDate(request.getProductionDate());
        batch.setExpireDate(expireDate);
        batch.setWarehouseId(requirement.getTargetWarehouseId());
        batch.setReceiptQuantity(request.getReceivedQuantity());
        batch.setQuantityUnit(requirement.getUnit());
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setInboundType(InboundType.OTHER);
        batch.setSourceDocType(sourceType);
        batch.setSourceDocId(taskId);
        batch.setSourceEventKey(request.getIdempotencyKey());
        batch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        batch.setOwnerCustomerId(requirement.getCustomerId());
        batch.setSourceSalesOrderId(requirement.getSalesOrderId());
        batch.setSourceSalesOrderItemId(requirement.getSalesOrderItemId() != null
                ? String.valueOf(requirement.getSalesOrderItemId()) : null);
        batch.setFactoryNumber(request.getExternalBatchNumber());
        batch.setOriginPlace(request.getOriginPlace());
        batch.setNotes(request.getNotes());
        batch.setCreatedBy(userId);
        MaterialBatch savedBatch = materialBatchRepository.save(batch);

        BigDecimal received = requirement.getReceivedQuantity().add(request.getReceivedQuantity());
        requirement.setReceivedQuantity(received);
        requirement.setStatus(received.compareTo(requirement.getExpectedQuantity()) >= 0
                ? SalesOrderSuppliedMaterialRequirementStatus.COMPLETED
                : SalesOrderSuppliedMaterialRequirementStatus.PARTIALLY_RECEIVED);
        requirementRepository.save(requirement);
        return materialBatchMapper.toDTO(savedBatch);
    }

    private void assertReplayBelongsToTask(MaterialBatch replay, String taskId) {
        if (!Objects.equals(taskId, replay.getSourceDocId())) {
            throw new BusinessException(409, "幂等键已用于其他客户来料任务，不能跨任务重放")
                    .withCode("CUSTOMER_SUPPLIED_IDEMPOTENCY_SCOPE_CONFLICT")
                    .withHint("请刷新当前任务并重新发起收货确认");
        }
    }

    private List<SalesOrderSuppliedMaterialRequirement> persistNewRequirements(
            SalesOrder order,
            List<CreateSalesOrderRequest.SuppliedMaterialRequirementDTO> requests) {
        List<SalesOrderSuppliedMaterialRequirement> requirements = new ArrayList<>();
        for (CreateSalesOrderRequest.SuppliedMaterialRequirementDTO request : requests) {
            requirements.add(toValidatedEntity(order, request));
        }
        return requirementRepository.saveAll(requirements);
    }

    private SalesOrderSuppliedMaterialRequirement toValidatedEntity(
            SalesOrder order,
            CreateSalesOrderRequest.SuppliedMaterialRequirementDTO request) {
        if (request == null) {
            throw invalidField("客供物料需求不能为空", "suppliedMaterials");
        }
        if (request.getMaterialTypeId() == null || request.getMaterialTypeId().isBlank()) {
            throw invalidField("客供物料ID不能为空", "materialTypeId");
        }
        if (request.getExpectedQuantity() == null || request.getExpectedQuantity().signum() <= 0) {
            throw invalidField("客供物料预计数量必须大于0", "expectedQuantity");
        }
        if (request.getExpectedArrivalAt() == null) {
            throw invalidField("客供物料预计到货时间不能为空", "expectedArrivalAt");
        }
        if (request.getTargetWarehouseId() == null || request.getTargetWarehouseId().isBlank()) {
            throw invalidField("客供物料目标仓库不能为空", "targetWarehouseId");
        }

        RawMaterialType material = rawMaterialTypeRepository.findById(request.getMaterialTypeId())
                .filter(candidate -> Objects.equals(order.getFactoryId(), candidate.getFactoryId()))
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()))
                .orElseThrow(() -> new BusinessException(400,
                        "客供物料不存在、已停用或不属于当前工厂")
                        .withCode("CUSTOMER_SUPPLIED_MATERIAL_INVALID")
                        .withHintTarget("materialTypeId"));

        String canonicalUnit = canonicalInventoryUnit(order.getFactoryId(), request.getUnit());

        warehouseInventoryGuardService.assertCanReceive(
                request.getTargetWarehouseId(), order.getFactoryId(), "RAW");
        FactoryWarehouse warehouse = factoryWarehouseRepository
                .findByIdAndFactoryIdAndDeletedAtIsNull(
                        request.getTargetWarehouseId(), order.getFactoryId())
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()))
                .orElseThrow(() -> new BusinessException(400,
                        "目标仓库不存在、已停用或不属于当前工厂")
                        .withCode("CUSTOMER_SUPPLIED_WAREHOUSE_INVALID")
                        .withHintTarget("targetWarehouseId"));

        Long salesOrderItemId = request.getSalesOrderItemId();
        if (salesOrderItemId != null) {
            SalesOrderItem item = salesOrderItemRepository.findById(salesOrderItemId)
                    .orElseThrow(() -> invalidOrderItem(salesOrderItemId));
            if (!Objects.equals(item.getSalesOrderId(), order.getId())) {
                throw invalidOrderItem(salesOrderItemId);
            }
        }

        SalesOrderSuppliedMaterialRequirement requirement =
                new SalesOrderSuppliedMaterialRequirement();
        requirement.setFactoryId(order.getFactoryId());
        requirement.setCustomerId(order.getCustomerId());
        requirement.setSalesOrderId(order.getId());
        requirement.setSalesOrderItemId(salesOrderItemId);
        requirement.setMaterialTypeId(material.getId());
        requirement.setMaterialName(material.getName());
        requirement.setExpectedQuantity(request.getExpectedQuantity());
        requirement.setReceivedQuantity(BigDecimal.ZERO);
        requirement.setUnit(canonicalUnit);
        requirement.setExpectedArrivalAt(request.getExpectedArrivalAt());
        requirement.setTargetWarehouseId(warehouse.getId());
        requirement.setStatus(SalesOrderSuppliedMaterialRequirementStatus.PENDING);
        return requirement;
    }

    private String canonicalInventoryUnit(String factoryId, String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank()) {
            throw invalidField("客供物料单位不能为空", "unit");
        }
        var normalized = unitContractService.normalize(factoryId, rawUnit);
        if (!normalized.recognized()) {
            throw new BusinessException(400, "未登记的客供物料计量单位: " + rawUnit)
                    .withCode("CUSTOMER_SUPPLIED_UNIT_UNKNOWN")
                    .withHintTarget("unit");
        }
        if (!unitContractService.supportsUsage(
                factoryId, normalized.code(), UnitUsageScope.INVENTORY_QUANTITY)) {
            throw new BusinessException(400,
                    "该计量单位不允许用于库存数量: " + normalized.code())
                    .withCode("CUSTOMER_SUPPLIED_UNIT_SCOPE_INVALID")
                    .withHintTarget("unit");
        }
        return normalized.code();
    }

    private void assertPayloadContract(
            SalesOrder order,
            List<CreateSalesOrderRequest.SuppliedMaterialRequirementDTO> requests,
            boolean nullMeansPreserve) {
        boolean customerSupplied = isCustomerSuppliedTollOrder(order);
        boolean hasPayload = requests != null && !requests.isEmpty();
        if (customerSupplied && (!nullMeansPreserve || requests != null) && !hasPayload) {
            throw missingRequirements();
        }
        if (!customerSupplied && hasPayload) {
            throw new BusinessException(400,
                    "只有代加工且客户自带原料的销售订单可以携带 suppliedMaterials")
                    .withCode("CUSTOMER_SUPPLIED_REQUIREMENTS_MODE_INVALID")
                    .withHintTarget("suppliedMaterials");
        }
    }

    private boolean isCustomerSuppliedTollOrder(SalesOrder order) {
        return order.getProcessingMode() == SalesProcessingMode.TOLL_PROCESSING
                && order.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED;
    }

    private void softDelete(List<SalesOrderSuppliedMaterialRequirement> existing) {
        if (existing.isEmpty()) {
            return;
        }
        existing.forEach(SalesOrderSuppliedMaterialRequirement::softDelete);
        requirementRepository.saveAll(existing);
    }

    private CustomerSuppliedMaterialReceivingTaskResponse toTaskResponse(
            SalesOrderSuppliedMaterialRequirement requirement) {
        SalesOrder order = requirement.getSalesOrder();
        SalesOrderItem item = requirement.getSalesOrderItem();
        FactoryWarehouse warehouse = requirement.getTargetWarehouse();
        BigDecimal received = requirement.getReceivedQuantity() != null
                ? requirement.getReceivedQuantity()
                : BigDecimal.ZERO;
        return CustomerSuppliedMaterialReceivingTaskResponse.builder()
                .taskId(requirement.getId())
                .source(CustomerSuppliedMaterialReceivingTaskResponse.SOURCE)
                .status(requirement.getStatus())
                .factoryId(requirement.getFactoryId())
                .customerId(requirement.getCustomerId())
                .customerName(order.getCustomerName())
                .salesOrderId(requirement.getSalesOrderId())
                .salesOrderNumber(order.getOrderNumber())
                .salesOrderStatus(order.getStatus())
                .salesOrderItemId(requirement.getSalesOrderItemId())
                .salesOrderItemProductTypeId(item != null ? item.getProductTypeId() : null)
                .salesOrderItemProductName(item != null ? item.getProductName() : null)
                .materialTypeId(requirement.getMaterialTypeId())
                .materialName(requirement.getMaterialName())
                .expectedQuantity(requirement.getExpectedQuantity())
                .receivedQuantity(received)
                .remainingQuantity(requirement.getRemainingQuantity())
                .unit(requirement.getUnit())
                .expectedArrivalAt(requirement.getExpectedArrivalAt())
                .targetWarehouseId(requirement.getTargetWarehouseId())
                .targetWarehouseCode(warehouse.getCode())
                .targetWarehouseName(warehouse.getName())
                .build();
    }

    private void assertOrderIdentity(SalesOrder order) {
        if (order == null || order.getId() == null || order.getFactoryId() == null
                || order.getCustomerId() == null) {
            throw new IllegalArgumentException(
                    "Persisted sales order identity is required for supplied-material requirements");
        }
    }

    private BusinessException missingRequirements() {
        return new BusinessException(400, "代加工且客户自带原料时必须填写客供物料需求")
                .withCode("CUSTOMER_SUPPLIED_REQUIREMENTS_REQUIRED")
                .withHintTarget("suppliedMaterials");
    }

    private BusinessException invalidField(String message, String hintTarget) {
        return new BusinessException(400, message)
                .withCode("CUSTOMER_SUPPLIED_REQUIREMENT_INVALID")
                .withHintTarget(hintTarget);
    }

    private BusinessException invalidOrderItem(Long salesOrderItemId) {
        return new BusinessException(400,
                "salesOrderItemId 不属于当前销售订单: " + salesOrderItemId)
                .withCode("CUSTOMER_SUPPLIED_ORDER_ITEM_INVALID")
                .withHintTarget("salesOrderItemId");
    }
}
