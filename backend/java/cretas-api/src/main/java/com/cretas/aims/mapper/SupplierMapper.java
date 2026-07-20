package com.cretas.aims.mapper;

import com.cretas.aims.dto.supplier.CreateSupplierRequest;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.dto.supplier.UpdateSupplierRequest;
import com.cretas.aims.entity.Supplier;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
/**
 * 供应商实体映射器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Component
public class SupplierMapper {
    /**
     * Entity 转 DTO
     */
    public SupplierDTO toDTO(Supplier supplier) {
        if (supplier == null) {
            return null;
        }
        SupplierDTO dto = SupplierDTO.builder()
                .id(supplier.getId())
                .factoryId(supplier.getFactoryId())
                .supplierCode(supplier.getSupplierCode())
                .name(supplier.getName())
                .contactPerson(supplier.getContactPerson())
                .phone(supplier.getPhone())
                .email(supplier.getEmail())
                .address(supplier.getAddress())
                .businessLicense(supplier.getBusinessLicense())
                .taxNumber(supplier.getTaxNumber())
                .bankName(supplier.getBankName())
                .bankAccount(supplier.getBankAccount())
                .suppliedMaterials(supplier.getSuppliedMaterials())
                .paymentTerms(supplier.getPaymentTerms())
                .deliveryDays(supplier.getDeliveryDays())
                .creditLimit(supplier.getCreditLimit())
                .currentBalance(supplier.getCurrentBalance())
                .rating(supplier.getRating())
                .ratingNotes(supplier.getRatingNotes())
                .qualityCertificates(supplier.getQualityCertificates())
                .isActive(supplier.getIsActive())
                .status(Boolean.TRUE.equals(supplier.getIsActive()) ? "ACTIVE" : "INACTIVE")
                .profileComplete(com.cretas.aims.service.supplier.SupplierProfileValidator.isComplete(
                        supplier.getName(), supplier.getContactPerson(), supplier.getPhone(), supplier.getAddress()))
                .notes(supplier.getNotes())
                .createdAt(supplier.getCreatedAt())
                .updatedAt(supplier.getUpdatedAt())
                .createdBy(supplier.getCreatedBy())
                .version(supplier.getVersion())
                .build();
        // 设置创建人姓名
        if (supplier.getCreatedByUser() != null) {
            dto.setCreatedByName(supplier.getCreatedByUser().getFullName());
        }
        // TODO: 设置统计信息（订单数量、总金额、最后订单日期）
        return dto;
    }

    /**
     * CreateRequest 转 Entity
     */
    public Supplier toEntity(CreateSupplierRequest request, String factoryId, Long createdBy) {
        if (request == null) {
            return null;
        }
        Supplier supplier = new Supplier();
        supplier.setFactoryId(factoryId);
        String supplierCode = request.getSupplierCode() != null
                ? request.getSupplierCode() : generateSupplierCode();
        supplier.setSupplierCode(supplierCode);
        supplier.setCode(supplierCode);  // 设置code字段，使用相同的supplierCode
        supplier.setName(request.getName());
        supplier.setContactPerson(request.getContactPerson());
        supplier.setPhone(request.getPhone());
        supplier.setEmail(request.getEmail());
        supplier.setAddress(request.getAddress());
        supplier.setBusinessLicense(request.getBusinessLicense());
        supplier.setTaxNumber(request.getTaxNumber());
        supplier.setBankName(request.getBankName());
        supplier.setBankAccount(request.getBankAccount());
        supplier.setSuppliedMaterials(request.getSuppliedMaterials());
        supplier.setPaymentTerms(request.getPaymentTerms());
        supplier.setDeliveryDays(request.getDeliveryDays());
        supplier.setCreditLimit(request.getCreditLimit());
        supplier.setCurrentBalance(BigDecimal.ZERO);
        supplier.setRating(request.getRating() != null ? request.getRating() : 3);
        supplier.setRatingNotes(request.getRatingNotes());
        supplier.setQualityCertificates(request.getQualityCertificates());
        supplier.setIsActive(true);
        supplier.setNotes(request.getNotes());
        supplier.setCreatedBy(createdBy);
        supplier.setCreatedAt(LocalDateTime.now());
        return supplier;
    }

    /**
     * 更新实体
     */
    public void updateEntity(Supplier supplier, UpdateSupplierRequest request) {
        if (request.getName() != null) {
            supplier.setName(request.getName());
        }
        if (request.getContactPerson() != null) {
            supplier.setContactPerson(request.getContactPerson());
        }
        if (request.getPhone() != null) {
            supplier.setPhone(request.getPhone());
        }
        if (request.getEmail() != null) {
            supplier.setEmail(request.getEmail());
        }
        if (request.getAddress() != null) {
            supplier.setAddress(request.getAddress());
        }
        if (request.getBusinessLicense() != null) {
            supplier.setBusinessLicense(request.getBusinessLicense());
        }
        if (request.getTaxNumber() != null) {
            supplier.setTaxNumber(request.getTaxNumber());
        }
        if (request.getBankName() != null) {
            supplier.setBankName(request.getBankName());
        }
        if (request.getBankAccount() != null) {
            supplier.setBankAccount(request.getBankAccount());
        }
        if (request.getSuppliedMaterials() != null) {
            supplier.setSuppliedMaterials(request.getSuppliedMaterials());
        }
        if (request.getPaymentTerms() != null) {
            supplier.setPaymentTerms(request.getPaymentTerms());
        }
        if (request.getDeliveryDays() != null) {
            supplier.setDeliveryDays(request.getDeliveryDays());
        }
        if (request.getCreditLimit() != null) {
            supplier.setCreditLimit(request.getCreditLimit());
        }
        if (request.getRating() != null) {
            supplier.setRating(request.getRating());
        }
        if (request.getRatingNotes() != null) {
            supplier.setRatingNotes(request.getRatingNotes());
        }
        if (request.getQualityCertificates() != null) {
            supplier.setQualityCertificates(request.getQualityCertificates());
        }
        if (request.getNotes() != null) {
            supplier.setNotes(request.getNotes());
        }
        supplier.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 生成供应商编码
     */
    private String generateSupplierCode() {
        return "SUP-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
