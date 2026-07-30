package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.supplier.CreateSupplierRequest;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.dto.supplier.UpdateSupplierRequest;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.mapper.SupplierMapper;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.service.SupplierService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
/**
 * 供应商服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
public class SupplierServiceImpl implements SupplierService {
    private static final Logger log = LoggerFactory.getLogger(SupplierServiceImpl.class);

    private final SupplierRepository supplierRepository;
    private final SupplierMapper supplierMapper;
    private final com.cretas.aims.utils.ExcelUtil excelUtil;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.inventory.PurchaseOrderRepository purchaseOrderRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.inventory.PurchaseOrderItemRepository purchaseOrderItemRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.datacenter.OperationLogService operationLogService;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    // Manual constructor (Lombok @RequiredArgsConstructor not working)
    public SupplierServiceImpl(SupplierRepository supplierRepository, SupplierMapper supplierMapper,
                              com.cretas.aims.utils.ExcelUtil excelUtil) {
        this.supplierRepository = supplierRepository;
        this.supplierMapper = supplierMapper;
        this.excelUtil = excelUtil;
    }

    private void runConfiguredValidation(String factoryId, String operation, java.util.Map<String, Object> context) {
        if (validationRuleEvaluator == null) return;
        try {
            validationRuleEvaluator.validate(factoryId, "supplier", operation, context);
        } catch (com.cretas.aims.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Canvas validation non-blocking error: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public SupplierDTO createSupplier(String factoryId, CreateSupplierRequest request, Long userId) {
        normalizeCreateRequest(request);
        com.cretas.aims.service.supplier.SupplierProfileValidator.validateOrThrow(
                request.getName(), request.getContactPerson(), request.getPhone(), request.getAddress());
        runConfiguredValidation(factoryId, "CREATE", java.util.Map.of(
            "supplierName", request.getName() != null ? request.getName() : "",
            "phoneNumber", request.getPhone() != null ? request.getPhone() : ""));
        log.info("创建供应商: factoryId={}, name={}", factoryId, request.getName());
        ensureNoDuplicateProfile(factoryId, request.getName(), request.getTaxNumber(),
                request.getShortName(), null);
        if (request.getSupplierCode() != null
                && supplierRepository.existsByFactoryIdAndSupplierCode(factoryId, request.getSupplierCode())) {
            throw new BusinessException(409, "供应商编码已存在")
                    .withHint("请留空由系统生成或使用其他编码").withHintTarget("supplierCode");
        }
        // 创建供应商实体
        Supplier supplier = supplierMapper.toEntity(request, factoryId, userId);
        // 生成UUID作为ID
        //supplier.setId(java.util.UUID.randomUUID().toString());
        // 确保供应商代码唯一
        String baseCode = "SUP";//supplier.getSupplierCode();
        int counter = 0;
        while (request.getSupplierCode() == null
                && supplierRepository.existsBySupplierCode(supplier.getSupplierCode())) {
            counter++;
            supplier.setSupplierCode(baseCode + "-" + counter);
        }
        // 保存供应商
        supplier = supplierRepository.save(supplier);
        log.info("供应商创建成功: id={}, code={}", supplier.getId(), supplier.getSupplierCode());
        return supplierMapper.toDTO(supplier);
    }
    @Override
    @Transactional
    public SupplierDTO updateSupplier(String factoryId, String supplierId, UpdateSupplierRequest request) {
        runConfiguredValidation(factoryId, "UPDATE", java.util.Map.of(
            "supplierId", supplierId,
            "supplierName", request.getName() != null ? request.getName() : "",
            "phoneNumber", request.getPhone() != null ? request.getPhone() : ""));
        log.info("更新供应商: factoryId={}, supplierId={}", factoryId, supplierId);
        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Supplier", supplierId));
        normalizeUpdateRequest(request);
        String mergedName = request.getName() != null ? request.getName() : supplier.getName();
        String mergedContact = request.getContactPerson() != null
                ? request.getContactPerson() : supplier.getContactPerson();
        String mergedPhone = request.getPhone() != null ? request.getPhone() : supplier.getPhone();
        String mergedAddress = request.getAddress() != null ? request.getAddress() : supplier.getAddress();
        com.cretas.aims.service.supplier.SupplierProfileValidator.validateOrThrow(
                mergedName, mergedContact, mergedPhone, mergedAddress);
        // Optimistic lock: explicit version compare (see CustomerServiceImpl — setVersion()
        // on managed entity is silently ignored by Hibernate, must compare manually).
        if (request.getVersion() != null && !request.getVersion().equals(supplier.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                Supplier.class, supplierId);
        }
        String mergedShortName = request.isShortNamePresent()
                ? request.getShortName() : supplier.getShortName();
        ensureNoDuplicateProfile(factoryId, mergedName,
                request.getTaxNumber() != null ? request.getTaxNumber() : supplier.getTaxNumber(),
                mergedShortName, supplierId);
        // 更新供应商信息
        supplierMapper.updateEntity(supplier, request);
        supplier = supplierRepository.save(supplier);
        log.info("供应商更新成功: id={}", supplier.getId());
        return supplierMapper.toDTO(supplier);
    }
    @Override
    @Transactional
    public void deleteSupplier(String factoryId, String supplierId) {
        log.info("删除供应商: factoryId={}, supplierId={}", factoryId, supplierId);
        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Supplier", supplierId));
        // Any historical business association makes physical deletion unsafe.
        if (supplierRepository.hasRelatedMaterialBatches(supplierId)) {
            throw new BusinessException(409, "供应商有关联的原材料批次，无法删除")
                    .withHint("请先归档或转移该供应商的原材料批次后再删除");
        }
        if (purchaseOrderRepository != null
                && !purchaseOrderRepository.findByFactoryIdAndSupplierId(factoryId, supplierId).isEmpty()) {
            throw new BusinessException(409, "供应商已有采购历史，不能物理删除")
                    .withHint("请改为暂停合作，历史采购、入库、应付与追溯将继续保留")
                    .withHintTarget("supplierStatus");
        }
        supplierRepository.delete(supplier);
        log.info("供应商删除成功: id={}", supplierId);
    }
    @Override
    @Transactional(readOnly = true)
    public SupplierDTO getSupplierById(String factoryId, String supplierId) {
        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Supplier", supplierId));
        return supplierMapper.toDTO(supplier);
    }
    @Override
    @Transactional(readOnly = true)
    public PageResponse<SupplierDTO> getSupplierList(String factoryId, PageRequest pageRequest) {
        // 创建分页请求
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        // 查询供应商
        Page<Supplier> supplierPage = supplierRepository.findByFactoryId(factoryId, pageable);
        // 转换为DTO
        List<SupplierDTO> supplierDTOs = supplierPage.getContent().stream()
                .map(supplierMapper::toDTO)
                .collect(Collectors.toList());
        // 构建分页响应
        PageResponse<SupplierDTO> response = new PageResponse<>();
        response.setContent(supplierDTOs);
        response.setPage(pageRequest.getPage());
        response.setSize(pageRequest.getSize());
        response.setTotalElements(supplierPage.getTotalElements());
        response.setTotalPages(supplierPage.getTotalPages());
        response.setFirst(supplierPage.isFirst());
        response.setLast(supplierPage.isLast());
        return response;
    }
    @Override
    @Transactional(readOnly = true)
    public List<SupplierDTO> getActiveSuppliers(String factoryId) {
        List<Supplier> suppliers = supplierRepository.findByFactoryIdAndIsActive(factoryId, true);
        return suppliers.stream()
                .map(supplierMapper::toDTO)
                .collect(Collectors.toList());
    }
    @Override
    @Transactional(readOnly = true)
    public List<SupplierDTO> searchSuppliersByName(String factoryId, String keyword) {
        String safeKeyword = com.cretas.aims.util.SqlLikeEscaper.escape(keyword);
        List<Supplier> suppliers = supplierRepository.searchByName(factoryId, safeKeyword);
        return suppliers.stream()
                .map(supplierMapper::toDTO)
                .collect(Collectors.toList());
    }
    @Override
    @Transactional(readOnly = true)
    public List<SupplierDTO> getSuppliersByMaterialType(String factoryId, String materialType) {
        List<Supplier> suppliers = supplierRepository.findByFactoryIdAndSuppliedMaterialsContaining(
                factoryId, materialType);
        return suppliers.stream()
                .map(supplierMapper::toDTO)
                .collect(Collectors.toList());
    }
    @Override
    @Transactional(readOnly = true)
    public List<SupplierDTO> getSuppliersByMaterialTypeId(String factoryId, String materialTypeId) {
        // Issue #788 follow-up: reverse direction by material_type_id, history-based M:N.
        // Differs from getSuppliersByMaterialType (which uses declared supplied_materials by name).
        List<Supplier> suppliers = supplierRepository.findDistinctSuppliersByMaterialTypeId(
                factoryId, materialTypeId);
        return suppliers.stream()
                .map(supplierMapper::toDTO)
                .collect(Collectors.toList());
    }
    @Override
    @Transactional
    public SupplierDTO toggleSupplierStatus(String factoryId, String supplierId, Boolean isActive) {
        throw new BusinessException(409, "旧状态接口已停用，状态变更必须填写原因并携带版本")
                .withCode("SUPPLIER_LIFECYCLE_REASON_REQUIRED")
                .withHint("请改用 PUT /suppliers/{supplierId}/lifecycle")
                .withHintTarget("reason");
    }

    @Override
    @Transactional
    public SupplierDTO changeSupplierStatus(String factoryId, String supplierId, Boolean isActive,
                                            String reason, Long expectedVersion) {
        log.info("切换供应商状态: factoryId={}, supplierId={}, isActive={}",
                factoryId, supplierId, isActive);
        if (isActive == null) {
            throw new BusinessException(400, "isActive 是必需的").withHintTarget("isActive");
        }
        String normalizedReason = com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(reason);
        if (normalizedReason == null) {
            throw new BusinessException(400, "状态变更原因不能为空").withHintTarget("reason");
        }
        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Supplier", supplierId));
        if (expectedVersion != null && !expectedVersion.equals(supplier.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Supplier.class, supplierId);
        }
        if (Objects.equals(supplier.getIsActive(), isActive)) {
            return supplierMapper.toDTO(supplier);
        }
        Boolean previous = supplier.getIsActive();
        supplier.setIsActive(isActive);
        supplier.setAdmissionStatus(Boolean.TRUE.equals(isActive) ? "APPROVED" : "SUSPENDED");
        supplier.setUpdatedAt(LocalDateTime.now());
        supplier = supplierRepository.save(supplier);
        if (operationLogService != null) {
            operationLogService.recordDiff(factoryId, "SUPPLIER", "Supplier", supplierId,
                    Map.of("isActive", previous, "status", Boolean.TRUE.equals(previous) ? "ACTIVE" : "INACTIVE"),
                    Map.of("isActive", isActive, "status", Boolean.TRUE.equals(isActive) ? "ACTIVE" : "INACTIVE"),
                    (Boolean.TRUE.equals(isActive) ? "恢复合作：" : "暂停合作：") + normalizedReason);
        }
        log.info("供应商状态更新成功: id={}, isActive={}", supplier.getId(), isActive);
        return supplierMapper.toDTO(supplier);
    }

    private static void normalizeCreateRequest(CreateSupplierRequest request) {
        request.setSupplierCode(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getSupplierCode()));
        request.setName(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getName()));
        request.setShortName(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getShortName()));
        request.setContactPerson(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getContactPerson()));
        request.setPhone(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getPhone()));
        request.setAddress(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getAddress()));
        request.setEmail(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getEmail()));
        request.setTaxNumber(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getTaxNumber()));
        request.setBankName(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getBankName()));
        request.setBankAccount(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getBankAccount()));
        request.setNotes(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getNotes()));
    }

    private static void normalizeUpdateRequest(UpdateSupplierRequest request) {
        if (request.getName() != null) request.setName(request.getName().trim());
        // 只在字段真的出现在请求体里时归一化 —— 无条件调 setShortName 会把
        // shortNamePresent 标记打成 true, 让"没传简称"被误判成"要清空简称"。
        if (request.isShortNamePresent()) {
            request.setShortName(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getShortName()));
        }
        if (request.getContactPerson() != null) request.setContactPerson(request.getContactPerson().trim());
        if (request.getPhone() != null) request.setPhone(request.getPhone().trim());
        if (request.getAddress() != null) request.setAddress(request.getAddress().trim());
        if (request.getEmail() != null) request.setEmail(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getEmail()));
        if (request.getTaxNumber() != null) request.setTaxNumber(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getTaxNumber()));
        if (request.getBankName() != null) request.setBankName(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getBankName()));
        if (request.getBankAccount() != null) request.setBankAccount(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getBankAccount()));
        if (request.getNotes() != null) request.setNotes(com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(request.getNotes()));
    }

    private void ensureNoDuplicateProfile(String factoryId, String name, String taxNumber,
                                          String shortName, String excludedId) {
        String normalizedName = normalizeIdentity(name);
        String normalizedTax = normalizeIdentity(taxNumber);
        String normalizedShortName = normalizeIdentity(shortName);
        for (Supplier existing : supplierRepository.findByFactoryId(factoryId)) {
            if (Objects.equals(existing.getId(), excludedId)) continue;
            if (normalizedName != null && normalizedName.equals(normalizeIdentity(existing.getName()))) {
                throw new BusinessException(409, "供应商名称已存在")
                        .withHint("请核对名称中的空格与大小写，避免重复供应商")
                        .withHintTarget("name");
            }
            if (normalizedTax != null && normalizedTax.equals(normalizeIdentity(existing.getTaxNumber()))) {
                throw new BusinessException(409, "供应商税号已存在")
                        .withHint("同一工厂内非空税号必须唯一")
                        .withHintTarget("taxNumber");
            }
            // 简称重复 = 下拉里两条一模一样, 正好是客户要简称想解决的问题。
            // 这里给可读的 409; DB 侧 uq_suppliers_short_name 部分唯一索引兜底。
            if (normalizedShortName != null
                    && normalizedShortName.equals(normalizeIdentity(existing.getShortName()))) {
                throw new BusinessException(409,
                        "简称「" + shortName + "」已被供应商「" + existing.getName() + "」占用")
                        .withHint("请换一个简称，两家简称一样下拉里还是分不出来")
                        .withHintTarget("shortName");
            }
        }
    }

    private static String normalizeIdentity(String value) {
        String trimmed = com.cretas.aims.service.supplier.SupplierProfileValidator.trimToNull(value);
        return trimmed == null ? null : trimmed.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
    @Override
    @Transactional
    public SupplierDTO updateSupplierRating(String factoryId, String supplierId,
                                           Integer rating, String notes) {
        log.info("更新供应商评级: factoryId={}, supplierId={}, rating={}",
                factoryId, supplierId, rating);
        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Supplier", supplierId));
        if (rating < 1 || rating > 5) {
            throw new BusinessException(400, "评级必须在1-5之间")
                    .withHint("请输入 1 到 5 的整数").withHintTarget("rating");
        }
        supplier.setRating(rating);
        supplier.setRatingNotes(notes);
        supplier = supplierRepository.save(supplier);
        log.info("供应商评级更新成功: id={}, rating={}", supplier.getId(), rating);
        return supplierMapper.toDTO(supplier);
    }
    @Override
    @Transactional
    public SupplierDTO updateCreditLimit(String factoryId, String supplierId, BigDecimal creditLimit) {
        log.info("更新供应商信用额度: factoryId={}, supplierId={}, creditLimit={}",
                factoryId, supplierId, creditLimit);
        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Supplier", supplierId));
        if (creditLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(400, "信用额度不能为负数")
                    .withHint("请输入大于等于 0 的金额").withHintTarget("creditLimit");
        }
        supplier.setCreditLimit(creditLimit);
        supplier = supplierRepository.save(supplier);
        log.info("供应商信用额度更新成功: id={}, creditLimit={}", supplier.getId(), creditLimit);
        return supplierMapper.toDTO(supplier);
    }
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getSupplierStatistics(String factoryId, String supplierId) {
        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Supplier", supplierId));
        Map<String, Object> statistics = new HashMap<>();
        statistics.put("supplierId", supplier.getId());
        statistics.put("supplierName", supplier.getName());
        statistics.put("rating", supplier.getRating());
        statistics.put("creditLimit", supplier.getCreditLimit());
        statistics.put("currentBalance", supplier.getCurrentBalance());
        statistics.put("isActive", supplier.getIsActive());
        // TODO: 添加订单统计、供货统计等信息
        statistics.put("totalOrders", 0);
        statistics.put("totalAmount", BigDecimal.ZERO);
        statistics.put("averageDeliveryDays", 0);
        statistics.put("onTimeDeliveryRate", 0.0);
        return statistics;
    }
    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSupplierHistory(String factoryId, String supplierId) {
        supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Supplier", supplierId));
        if (purchaseOrderRepository == null || purchaseOrderItemRepository == null) {
            throw new BusinessException(503, "采购历史服务不可用");
        }

        List<com.cretas.aims.entity.inventory.PurchaseOrder> orders = purchaseOrderRepository
                .findByFactoryIdAndSupplierId(factoryId, supplierId);
        if (orders.isEmpty()) {
            return List.of();
        }

        Map<String, com.cretas.aims.entity.inventory.PurchaseOrder> orderById = orders.stream()
                .collect(Collectors.toMap(com.cretas.aims.entity.inventory.PurchaseOrder::getId,
                        java.util.function.Function.identity()));
        List<com.cretas.aims.entity.inventory.PurchaseOrderItem> items = purchaseOrderItemRepository
                .findByPurchaseOrderIdIn(new ArrayList<>(orderById.keySet()));

        Map<String, SupplierHistoryAccumulator> grouped = new LinkedHashMap<>();
        for (com.cretas.aims.entity.inventory.PurchaseOrderItem item : items) {
            com.cretas.aims.entity.inventory.PurchaseOrder order = orderById.get(item.getPurchaseOrderId());
            if (order == null || item.getReceivedQuantity() == null
                    || item.getReceivedQuantity().signum() <= 0) continue;
            String unit = item.getUnit() != null ? item.getUnit() : "";
            String priceUnit = item.getPriceUnit() != null ? item.getPriceUnit() : unit;
            String key = item.getMaterialTypeId() + "\u0000" + unit + "\u0000" + priceUnit;
            SupplierHistoryAccumulator acc = grouped.computeIfAbsent(key,
                    ignored -> new SupplierHistoryAccumulator(item.getMaterialTypeId(),
                            item.getMaterialName(), unit, priceUnit));
            acc.orderIds.add(order.getId());
            acc.orderedQuantity = acc.orderedQuantity.add(nullToZero(item.getQuantity()));
            acc.receivedQuantity = acc.receivedQuantity.add(nullToZero(item.getReceivedQuantity()));
            if (item.getUnitPrice() != null) {
                BigDecimal factor = item.getQuantityToPriceFactor() != null
                        ? item.getQuantityToPriceFactor() : BigDecimal.ONE;
                BigDecimal receivedInPriceUnit = item.getReceivedQuantity().multiply(factor);
                acc.totalReceivedAmount = acc.totalReceivedAmount.add(
                        receivedInPriceUnit.multiply(item.getUnitPrice()));
                acc.pricedQuantity = acc.pricedQuantity.add(
                        receivedInPriceUnit);
            }
            if (order.getOrderDate() != null
                    && (acc.lastPurchaseDate == null || order.getOrderDate().isAfter(acc.lastPurchaseDate))) {
                acc.lastPurchaseDate = order.getOrderDate();
                acc.lastUnitPrice = item.getUnitPrice();
            }
        }

        return grouped.values().stream()
                .sorted(Comparator.comparing((SupplierHistoryAccumulator acc) -> acc.lastPurchaseDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(SupplierHistoryAccumulator::toMap)
                .toList();
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static final class SupplierHistoryAccumulator {
        private final String materialTypeId;
        private final String materialName;
        private final String quantityUnit;
        private final Set<String> orderIds = new LinkedHashSet<>();
        private BigDecimal orderedQuantity = BigDecimal.ZERO;
        private BigDecimal receivedQuantity = BigDecimal.ZERO;
        private BigDecimal totalReceivedAmount = BigDecimal.ZERO;
        private BigDecimal pricedQuantity = BigDecimal.ZERO;
        private java.time.LocalDate lastPurchaseDate;
        private BigDecimal lastUnitPrice;
        private final String priceUnit;

        private SupplierHistoryAccumulator(String materialTypeId, String materialName,
                String quantityUnit, String priceUnit) {
            this.materialTypeId = materialTypeId;
            this.materialName = materialName;
            this.quantityUnit = quantityUnit;
            this.priceUnit = priceUnit;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialTypeId", materialTypeId);
            row.put("materialName", materialName);
            row.put("orderCount", orderIds.size());
            row.put("orderedQuantity", orderedQuantity);
            row.put("actuallyReceivedQuantity", receivedQuantity);
            row.put("quantityUnit", quantityUnit);
            row.put("totalReceivedAmount", totalReceivedAmount);
            row.put("averageUnitPrice", pricedQuantity.signum() > 0
                    ? totalReceivedAmount.divide(pricedQuantity, 4, java.math.RoundingMode.HALF_UP) : null);
            row.put("priceUnit", priceUnit);
            row.put("lastUnitPrice", lastUnitPrice);
            row.put("lastPurchaseDate", lastPurchaseDate);
            return row;
        }
    }
    @Override
    @Transactional(readOnly = true)
    public boolean checkSupplierCodeExists(String factoryId, String supplierCode) {
        return supplierRepository.existsByFactoryIdAndSupplierCode(factoryId, supplierCode);
    }
    @Override
    public byte[] exportSupplierList(String factoryId, boolean maskPrice) {
        log.info("导出供应商列表: factoryId={}, maskPrice={}", factoryId, maskPrice);

        // 查询所有供应商
        List<Supplier> suppliers = supplierRepository.findByFactoryId(factoryId);

        // 转换为DTO
        List<SupplierDTO> supplierDTOs = suppliers.stream()
                .map(supplierMapper::toDTO)
                .collect(Collectors.toList());

        // RBAC defense-in-depth (P0-C sweep, 2026-05-12): no procurement:price:view →
        // 信用额度 col masked to "—" via SupplierMaskedExportDTO.
        byte[] excelBytes;
        if (maskPrice) {
            List<com.cretas.aims.dto.supplier.SupplierMaskedExportDTO> maskedDTOs = supplierDTOs.stream()
                    .map(com.cretas.aims.dto.supplier.SupplierMaskedExportDTO::fromSupplierDTO)
                    .collect(Collectors.toList());
            excelBytes = excelUtil.exportToExcel(
                    maskedDTOs,
                    com.cretas.aims.dto.supplier.SupplierMaskedExportDTO.class,
                    "供应商列表"
            );
        } else {
            List<com.cretas.aims.dto.supplier.SupplierExportDTO> exportDTOs = supplierDTOs.stream()
                    .map(com.cretas.aims.dto.supplier.SupplierExportDTO::fromSupplierDTO)
                    .collect(Collectors.toList());
            excelBytes = excelUtil.exportToExcel(
                    exportDTOs,
                    com.cretas.aims.dto.supplier.SupplierExportDTO.class,
                    "供应商列表"
            );
        }

        log.info("供应商列表导出成功: factoryId={}, count={}, maskPrice={}",
                factoryId, suppliers.size(), maskPrice);
        return excelBytes;
    }

    @Override
    public byte[] generateImportTemplate() {
        log.info("生成供应商导入模板");

        // 使用ExcelUtil生成空模板
        byte[] templateBytes = excelUtil.generateTemplate(
                com.cretas.aims.dto.supplier.SupplierExportDTO.class,
                "供应商导入模板"
        );

        log.info("供应商导入模板生成成功");
        return templateBytes;
    }

    @Override
    // 不使用@Transactional，让每个save操作独立进行，避免单行失败导致整体回滚
    public com.cretas.aims.dto.common.ImportResult<SupplierDTO> importSuppliersFromExcel(
            String factoryId,
            java.io.InputStream inputStream) {
        log.info("开始从Excel批量导入供应商: factoryId={}", factoryId);

        // 1. 解析Excel文件
        List<com.cretas.aims.dto.supplier.SupplierExportDTO> excelData;
        try {
            excelData = excelUtil.importFromExcel(inputStream,
                    com.cretas.aims.dto.supplier.SupplierExportDTO.class);
        } catch (Exception e) {
            log.error("Excel文件解析失败: factoryId={}", factoryId, e);
            throw new RuntimeException("Excel文件格式错误或无法解析: " + e.getMessage());
        }

        com.cretas.aims.dto.common.ImportResult<SupplierDTO> result =
                com.cretas.aims.dto.common.ImportResult.create(excelData.size());

        // 2. 逐行验证并导入
        for (int i = 0; i < excelData.size(); i++) {
            com.cretas.aims.dto.supplier.SupplierExportDTO exportDTO = excelData.get(i);
            int rowNumber = i + 2; // Excel行号（从2开始，1是表头）

            try {
                // 2.1 验证必填字段
                if (exportDTO.getName() == null || exportDTO.getName().trim().isEmpty()) {
                    result.addFailure(rowNumber, "供应商名称不能为空", toJsonString(exportDTO));
                    continue;
                }

                // 2.2 验证编码唯一性（如果提供了编码）
                if (exportDTO.getSupplierCode() != null && !exportDTO.getSupplierCode().trim().isEmpty()) {
                    if (supplierRepository.existsByFactoryIdAndSupplierCode(factoryId, exportDTO.getSupplierCode())) {
                        result.addFailure(rowNumber, "供应商编码已存在: " + exportDTO.getSupplierCode(),
                                toJsonString(exportDTO));
                        continue;
                    }
                }

                // 2.3 验证名称唯一性
                if (supplierRepository.existsByFactoryIdAndName(factoryId, exportDTO.getName())) {
                    result.addFailure(rowNumber, "供应商名称已存在: " + exportDTO.getName(),
                            toJsonString(exportDTO));
                    continue;
                }

                // 2.4 转换为Entity
                Supplier supplier = convertFromExportDTO(exportDTO, factoryId);

                // 2.5 保存
                Supplier saved = supplierRepository.save(supplier);

                // 2.6 转换为DTO并记录成功
                SupplierDTO dto = supplierMapper.toDTO(saved);
                result.addSuccess(dto);

                log.debug("成功导入供应商: row={}, name={}", rowNumber, exportDTO.getName());

            } catch (Exception e) {
                log.error("导入供应商失败: factoryId={}, row={}, data={}", factoryId, rowNumber, exportDTO, e);
                result.addFailure(rowNumber, "保存失败: " + e.getMessage(), toJsonString(exportDTO));
            }
        }

        log.info("供应商批量导入完成: factoryId={}, total={}, success={}, failure={}",
                factoryId, result.getTotalCount(), result.getSuccessCount(), result.getFailureCount());
        return result;
    }

    /**
     * 从SupplierExportDTO转换为Supplier实体
     */
    private Supplier convertFromExportDTO(com.cretas.aims.dto.supplier.SupplierExportDTO dto, String factoryId) {
        Supplier supplier = new Supplier();
        supplier.setId(java.util.UUID.randomUUID().toString());
        supplier.setFactoryId(factoryId);
        supplier.setSupplierCode(dto.getSupplierCode());
        supplier.setCode(dto.getSupplierCode()); // code字段使用supplierCode
        supplier.setName(dto.getName());
        supplier.setContactPerson(dto.getContactPerson());
        supplier.setPhone(dto.getPhone());
        supplier.setEmail(dto.getEmail());
        supplier.setAddress(dto.getAddress());
        supplier.setSuppliedMaterials(dto.getSuppliedMaterials());
        supplier.setPaymentTerms(dto.getPaymentTerms());
        supplier.setDeliveryDays(dto.getDeliveryDays());
        supplier.setCreditLimit(dto.getCreditLimit() != null ? dto.getCreditLimit() : BigDecimal.ZERO);
        supplier.setCurrentBalance(BigDecimal.ZERO);
        supplier.setRating(dto.getRating());
        supplier.setIsActive("启用".equals(dto.getStatus()));
        supplier.setCreatedBy(1L); // 系统导入，使用默认用户ID
        return supplier;
    }

    /**
     * 将对象转换为JSON字符串
     */
    private String toJsonString(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    @Override
    @Transactional
    public List<SupplierDTO> importSuppliers(String factoryId, List<CreateSupplierRequest> requests,
                                            Long userId) {
        log.info("批量导入供应商: factoryId={}, count={}", factoryId, requests.size());
        List<SupplierDTO> importedSuppliers = new ArrayList<>();
        for (CreateSupplierRequest request : requests) {
            try {
                SupplierDTO supplier = createSupplier(factoryId, request, userId);
                importedSuppliers.add(supplier);
            } catch (Exception e) {
                log.error("导入供应商失败: name={}, error={}", request.getName(), e.getMessage());
            }
        }
        log.info("批量导入完成，成功导入 {} 个供应商", importedSuppliers.size());
        return importedSuppliers;
    }
    @Override
    @Transactional(readOnly = true)
    public Map<Integer, Long> getSupplierRatingDistribution(String factoryId) {
        List<Object[]> distribution = supplierRepository.getSupplierRatingDistribution(factoryId);
        Map<Integer, Long> result = new HashMap<>();
        for (Object[] row : distribution) {
            Integer rating = (Integer) row[0];
            Long count = (Long) row[1];
            // 修复: 过滤null rating，避免JSON序列化失败
            if (rating != null) {
                result.put(rating, count);
            } else {
                // 将null rating归类为"未评级"（rating=0）
                log.warn("发现未评级的供应商，数量: {}", count);
                result.put(0, result.getOrDefault(0, 0L) + count);
            }
        }
        // 确保所有评级都有值（0-5分，0表示未评级）
        for (int i = 0; i <= 5; i++) {
            result.putIfAbsent(i, 0L);
        }
        return result;
    }
    @Override
    @Transactional(readOnly = true)
    public List<SupplierDTO> getSuppliersWithOutstandingBalance(String factoryId) {
        List<Supplier> suppliers = supplierRepository.findSuppliersWithOutstandingBalance(factoryId);
        return suppliers.stream()
                .map(supplierMapper::toDTO)
                .collect(Collectors.toList());
    }
}
