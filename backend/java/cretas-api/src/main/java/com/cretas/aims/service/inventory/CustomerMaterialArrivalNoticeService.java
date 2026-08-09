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
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.UnorderedInboundReason;
import com.cretas.aims.entity.inventory.CustomerMaterialArrivalNotice;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.CustomerMaterialArrivalNoticeRepository;
import com.cretas.aims.service.factory.WarehouseInventoryGuardService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitUsageScope;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Operations request creation and warehouse-only inventory execution boundary. */
@Service
@RequiredArgsConstructor
public class CustomerMaterialArrivalNoticeService {

    public static final String SOURCE_TYPE = "CUSTOMER_MATERIAL_ARRIVAL";
    private static final EnumSet<CustomerMaterialArrivalStatus> OPEN_STATUSES = EnumSet.of(
            CustomerMaterialArrivalStatus.OPEN,
            CustomerMaterialArrivalStatus.PARTIALLY_RECEIVED);

    private final CustomerMaterialArrivalNoticeRepository noticeRepository;
    private final CustomerRepository customerRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final FactoryWarehouseRepository warehouseRepository;
    private final WarehouseInventoryGuardService warehouseInventoryGuardService;
    private final UnitContractService unitContractService;
    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialBatchMapper materialBatchMapper;

    @Transactional
    public CustomerMaterialArrivalNotice create(String factoryId,
                                                 CreateCustomerMaterialArrivalNoticeRequest request,
                                                 Long userId) {
        if (request == null) {
            throw invalid("无订单入库申请不能为空", "notice");
        }
        UnorderedInboundReason reason = request.getReason() == null
                ? UnorderedInboundReason.CUSTOMER_MATERIAL
                : request.getReason();
        String requestedCustomerId = trimToNull(request.getCustomerId());
        if (reason == UnorderedInboundReason.CUSTOMER_MATERIAL && requestedCustomerId == null) {
            throw new BusinessException(400, "客户来料必须选择归属客户")
                    .withCode("UNORDERED_INBOUND_CUSTOMER_REQUIRED")
                    .withHint("请选择库存最终归属的客户；创建申请不会直接增加库存")
                    .withHintTarget("customerId");
        }
        Customer customer = requestedCustomerId == null
                ? null
                : customerRepository.findByIdAndFactoryId(requestedCustomerId, factoryId)
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()))
                .orElseThrow(() -> new BusinessException(400, "所选客户不存在、已停用或不属于当前工厂")
                        .withCode("UNORDERED_INBOUND_CUSTOMER_INVALID")
                        .withHintTarget("customerId"));

        CustomerMaterialArrivalNotice notice = new CustomerMaterialArrivalNotice();
        notice.setId(UUID.randomUUID().toString());
        notice.setFactoryId(factoryId);
        notice.setReason(reason);
        notice.setNoticeNumber(noticePrefix(reason) + "-" + LocalDate.now().toString().replace("-", "") + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        notice.setCustomerId(customer == null ? null : customer.getId());
        notice.setExpectedArrivalAt(request.getExpectedArrivalAt());
        notice.setContactName(trimToNull(request.getContactName()));
        notice.setContactPhone(trimToNull(request.getContactPhone()));
        notice.setRemark(trimToNull(request.getRemark()));
        notice.setStatus(CustomerMaterialArrivalStatus.OPEN);
        notice.setReceiptCount(0);
        notice.setCreatedBy(userId);
        return noticeRepository.save(notice);
    }

    @Transactional(readOnly = true)
    public List<CustomerMaterialArrivalNotice> list(String factoryId, boolean openOnly) {
        return openOnly
                ? noticeRepository.findByFactoryIdAndStatusInOrderByExpectedArrivalAtAscCreatedAtAsc(
                        factoryId, OPEN_STATUSES)
                : noticeRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId);
    }

    @Transactional
    public CustomerMaterialArrivalNotice cancel(String factoryId, String noticeId) {
        CustomerMaterialArrivalNotice notice = requireForUpdate(factoryId, noticeId);
        if (notice.getStatus() == CustomerMaterialArrivalStatus.CANCELLED) {
            return notice;
        }
        if (notice.getReceiptCount() != null && notice.getReceiptCount() > 0) {
            throw new BusinessException(409, "已有实际收货记录的无订单入库申请不能取消")
                    .withCode("CUSTOMER_MATERIAL_ARRIVAL_ALREADY_RECEIVED")
                    .withHint("请保留来源单据以维持库存追溯；如不再到货，可由仓储完成预告");
        }
        if (notice.getStatus() == CustomerMaterialArrivalStatus.RECEIVED) {
            throw new BusinessException(409, "已完成的无订单入库申请不能取消")
                    .withCode("CUSTOMER_MATERIAL_ARRIVAL_ALREADY_CLOSED");
        }
        notice.setStatus(CustomerMaterialArrivalStatus.CANCELLED);
        return noticeRepository.save(notice);
    }

    @Transactional
    public MaterialBatchDTO receive(String factoryId,
                                    String noticeId,
                                    CustomerMaterialArrivalReceiptRequest request,
                                    Long userId) {
        if (request == null) throw invalid("无订单入库收货信息不能为空", "receipt");
        String idempotencyKey = request.getIdempotencyKey();
        MaterialBatch replay = materialBatchRepository
                .findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                        factoryId, SOURCE_TYPE, idempotencyKey)
                .orElse(null);
        if (replay != null) {
            assertReplayNotice(replay, noticeId);
            return materialBatchMapper.toDTO(replay);
        }

        CustomerMaterialArrivalNotice notice = requireForUpdate(factoryId, noticeId);
        if (!OPEN_STATUSES.contains(notice.getStatus())) {
            throw new BusinessException(409, "该无订单入库申请已完成或已取消")
                    .withCode("CUSTOMER_MATERIAL_ARRIVAL_CLOSED")
                    .withHint("请刷新仓储待入库任务；系统没有增加库存");
        }
        replay = materialBatchRepository
                .findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                        factoryId, SOURCE_TYPE, idempotencyKey)
                .orElse(null);
        if (replay != null) {
            assertReplayNotice(replay, noticeId);
            return materialBatchMapper.toDTO(replay);
        }

        RawMaterialType material = rawMaterialTypeRepository.findById(request.getMaterialTypeId())
                .filter(candidate -> Objects.equals(factoryId, candidate.getFactoryId()))
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()))
                .orElseThrow(() -> new BusinessException(400, "实际原料不存在、已停用或不属于当前工厂")
                        .withCode("CUSTOMER_MATERIAL_ARRIVAL_MATERIAL_INVALID")
                        .withHintTarget("materialTypeId"));
        warehouseInventoryGuardService.assertCanReceive(request.getWarehouseId(), factoryId, "RAW");
        warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(request.getWarehouseId(), factoryId)
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()))
                .orElseThrow(() -> new BusinessException(400, "实际入库仓库不存在、已停用或不属于当前工厂")
                        .withCode("CUSTOMER_MATERIAL_ARRIVAL_WAREHOUSE_INVALID")
                        .withHintTarget("warehouseId"));

        String materialUnit = canonicalInventoryUnit(factoryId, material.getUnit());
        if (request.getUnit() != null && !request.getUnit().isBlank()) {
            String requestUnit = canonicalInventoryUnit(factoryId, request.getUnit());
            if (!Objects.equals(materialUnit, requestUnit)) {
                throw new BusinessException(400, "实收单位必须与原料库存基本单位一致")
                        .withCode("CUSTOMER_MATERIAL_ARRIVAL_UNIT_MISMATCH")
                        .withHint("该原料库存单位为 " + materialUnit)
                        .withHintTarget("unit");
            }
        }
        if (request.getExpireDate() != null && request.getProductionDate() != null
                && request.getExpireDate().isBefore(request.getProductionDate())) {
            throw invalid("到期日期不能早于生产日期", "expireDate");
        }

        LocalDate receiptDate = LocalDate.now();
        LocalDate expireDate = request.getExpireDate();
        if (expireDate == null && material.getShelfLifeDays() != null) {
            expireDate = receiptDate.plusDays(material.getShelfLifeDays());
        }
        String deterministicSuffix = UUID.nameUUIDFromBytes(
                        (factoryId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 12).toUpperCase();

        MaterialBatch batch = new MaterialBatch();
        boolean customerOwned = notice.getReason() == null
                || notice.getReason() == UnorderedInboundReason.CUSTOMER_MATERIAL;
        if (customerOwned && trimToNull(notice.getCustomerId()) == null) {
            throw new BusinessException(409, "客户来料申请缺少归属客户，不能入库")
                    .withCode("UNORDERED_INBOUND_OWNER_MISSING")
                    .withHint("请取消该申请并重新选择客户；本次未增加库存");
        }
        batch.setId(UUID.randomUUID().toString());
        batch.setFactoryId(factoryId);
        batch.setBatchNumber((customerOwned ? "CMA" : "UIN") + "-"
                + receiptDate.toString().replace("-", "") + "-" + deterministicSuffix);
        batch.setMaterialTypeId(material.getId());
        batch.setReceiptDate(receiptDate);
        batch.setProductionDate(request.getProductionDate());
        batch.setExpireDate(expireDate);
        batch.setWarehouseId(request.getWarehouseId());
        batch.setReceiptQuantity(request.getReceivedQuantity());
        batch.setQuantityUnit(materialUnit);
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setInboundType(customerOwned ? InboundType.CUSTOMER_SUPPLIED : InboundType.OTHER);
        batch.setSourceDocType(SOURCE_TYPE);
        batch.setSourceDocId(noticeId);
        batch.setSourceEventKey(idempotencyKey);
        batch.setOwnership(customerOwned
                ? InventoryOwnership.CUSTOMER_OWNED
                : InventoryOwnership.COMPANY_OWNED);
        batch.setOwnerCustomerId(customerOwned ? notice.getCustomerId() : null);
        batch.setSourceSalesOrderId(null);
        batch.setSourceSalesOrderItemId(null);
        batch.setSupplierBatchNumber(trimToNull(request.getExternalBatchNumber()));
        batch.setFactoryNumber(trimToNull(request.getFactoryNumber()));
        batch.setContractNumber(trimToNull(request.getContractNumber()));
        batch.setBoxCount(request.getBoxCount());
        batch.setOriginPlace(trimToNull(request.getOriginPlace()));
        batch.setNotes(trimToNull(request.getNotes()));
        batch.setCreatedBy(userId);

        MaterialBatch saved;
        try {
            saved = materialBatchRepository.saveAndFlush(batch);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(409, "该幂等键已被另一笔无订单入库收货使用")
                    .withCode("CUSTOMER_MATERIAL_ARRIVAL_IDEMPOTENCY_CONFLICT")
                    .withHint("请刷新任务并使用新的幂等键；本次未增加库存");
        }

        notice.setReceiptCount((notice.getReceiptCount() == null ? 0 : notice.getReceiptCount()) + 1);
        notice.setLastReceivedAt(LocalDateTime.now());
        notice.setStatus(Boolean.TRUE.equals(request.getCompleteNotice())
                ? CustomerMaterialArrivalStatus.RECEIVED
                : CustomerMaterialArrivalStatus.PARTIALLY_RECEIVED);
        noticeRepository.save(notice);
        return materialBatchMapper.toDTO(saved);
    }

    private CustomerMaterialArrivalNotice requireForUpdate(String factoryId, String noticeId) {
        return noticeRepository.findByIdAndFactoryIdForUpdate(noticeId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "无订单入库申请不存在或不属于当前工厂")
                        .withCode("CUSTOMER_MATERIAL_ARRIVAL_NOT_FOUND"));
    }

    private void assertReplayNotice(MaterialBatch batch, String noticeId) {
        if (!Objects.equals(noticeId, batch.getSourceDocId())) {
            throw new BusinessException(409, "幂等键已用于其他无订单入库申请，不能跨申请重放")
                    .withCode("CUSTOMER_MATERIAL_ARRIVAL_IDEMPOTENCY_SCOPE_CONFLICT");
        }
    }

    private String canonicalInventoryUnit(String factoryId, String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank()) throw invalid("原料库存单位未配置", "unit");
        var normalized = unitContractService.normalize(factoryId, rawUnit);
        if (!normalized.recognized()
                || !unitContractService.supportsUsage(
                        factoryId, normalized.code(), UnitUsageScope.INVENTORY_QUANTITY)) {
            throw new BusinessException(400, "原料库存单位未登记或不允许用于库存数量: " + rawUnit)
                    .withCode("CUSTOMER_MATERIAL_ARRIVAL_UNIT_INVALID")
                    .withHintTarget("unit");
        }
        return normalized.code();
    }

    private BusinessException invalid(String message, String target) {
        return new BusinessException(400, message).withHintTarget(target);
    }

    private String noticePrefix(UnorderedInboundReason reason) {
        return switch (reason) {
            case CUSTOMER_MATERIAL -> "CMA";
            case GIFT -> "GFT";
            case OTHER -> "OIN";
        };
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }
}
