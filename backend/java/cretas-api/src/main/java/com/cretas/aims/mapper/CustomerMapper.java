package com.cretas.aims.mapper;

import com.cretas.aims.dto.customer.CreateCustomerRequest;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.dto.customer.UpdateCustomerRequest;
import com.cretas.aims.entity.Customer;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
/**
 * 客户实体映射器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Component
public class CustomerMapper {
    /**
     * Entity 转 DTO
     */
    public CustomerDTO toDTO(Customer customer) {
        if (customer == null) {
            return null;
        }
        CustomerDTO dto = CustomerDTO.builder()
                .id(customer.getId())
                .factoryId(customer.getFactoryId())
                .customerCode(customer.getCustomerCode())
                .name(customer.getName())
                .type(customer.getType())
                .industry(customer.getIndustry())
                .contactPerson(customer.getContactPerson())
                .phone(customer.getPhone())
                .email(customer.getEmail())
                .shippingAddress(customer.getShippingAddress())
                .billingAddress(customer.getBillingAddress())
                .taxNumber(customer.getTaxNumber())
                .businessLicense(customer.getBusinessLicense())
                .paymentTerms(customer.getPaymentTerms())
                .creditLimit(customer.getCreditLimit())
                .currentBalance(customer.getCurrentBalance())
                .creditPeriodDays(customer.getCreditPeriodDays())
                .creditStatus(customer.getCreditStatus())
                .rating(customer.getRating())
                .ratingNotes(customer.getRatingNotes())
                .isActive(customer.getIsActive())
                .notes(customer.getNotes())
                // Sprint 4 W2 S-CRM-FULL-1
                .customerStatus(customer.getCustomerStatus())
                .importance(customer.getImportance())
                .source(customer.getSource())
                .lastContactedAt(customer.getLastContactedAt())
                // Sprint 4 W2 S-INVOICE-CLIENT-1
                .defaultTaxRate(customer.getDefaultTaxRate())
                .defaultInvoiceType(customer.getDefaultInvoiceType())
                .createdAt(customer.getCreatedAt())
                .updatedAt(customer.getUpdatedAt())
                .createdBy(customer.getCreatedBy())
                .version(customer.getVersion())
                .assignedSalesUserId(customer.getAssignedSalesUserId())
                .assignedSalesUserAssignedAt(customer.getAssignedSalesUserAssignedAt())
                .build();
        // 设置创建人姓名
        if (customer.getCreatedByUser() != null) {
            dto.setCreatedByName(customer.getCreatedByUser().getFullName());
        }
        // Sprint 4 W1 S-CUSTOMER-TAB-1: 设置当前业务员姓名 (避免前端显示 "User #100")
        if (customer.getAssignedSalesUser() != null) {
            String fullName = customer.getAssignedSalesUser().getFullName();
            String username = customer.getAssignedSalesUser().getUsername();
            dto.setAssignedSalesUserName(fullName != null && !fullName.isBlank() ? fullName : username);
        }
        // TODO: 设置统计信息（订单数量、销售总额、最后订单日期、平均订单价值）
        return dto;
    }

    /**
     * CreateRequest 转 Entity
     */
    public Customer toEntity(CreateCustomerRequest request, String factoryId, Long createdBy) {
        if (request == null) {
            return null;
        }
        Customer customer = new Customer();
        customer.setFactoryId(factoryId);
        customer.setCode(generateCustomerCode());
        customer.setCustomerCode(generateCustomerCode());
        customer.setName(request.getName());
        customer.setType(request.getType());
        customer.setIndustry(request.getIndustry());
        customer.setContactName(request.getContactPerson());
        customer.setContactPerson(request.getContactPerson());
        customer.setContactPhone(request.getPhone());
        customer.setPhone(request.getPhone());
        customer.setContactEmail(request.getEmail());
        customer.setEmail(request.getEmail());
        customer.setShippingAddress(request.getShippingAddress());
        customer.setBillingAddress(request.getBillingAddress() != null ?
                request.getBillingAddress() : request.getShippingAddress());
        customer.setTaxNumber(request.getTaxNumber());
        customer.setBusinessLicense(request.getBusinessLicense());
        customer.setPaymentTerms(request.getPaymentTerms());
        customer.setCreditLimit(request.getCreditLimit());
        customer.setCurrentBalance(BigDecimal.ZERO);
        // P1 #23 S-CREDIT-1: default 30d period, NORMAL status if not provided
        customer.setCreditPeriodDays(request.getCreditPeriodDays() != null
                ? request.getCreditPeriodDays() : 30);
        customer.setCreditStatus(request.getCreditStatus() != null
                ? request.getCreditStatus()
                : com.cretas.aims.entity.enums.CreditStatus.NORMAL);
        customer.setRating(request.getRating() != null ? request.getRating() : 3);
        customer.setRatingNotes(request.getRatingNotes());
        // Bug D fix + audit M3 validation: status must be ACTIVE/INACTIVE if provided
        String status = request.getStatus();
        if (status != null && !"ACTIVE".equalsIgnoreCase(status) && !"INACTIVE".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("status 必须是 ACTIVE 或 INACTIVE");
        }
        customer.setIsActive(status == null || "ACTIVE".equalsIgnoreCase(status));
        customer.setNotes(request.getNotes());
        // Sprint 4 W2 S-CRM-FULL-1: 默认 LEAD / NORMAL (UI 可创建后立刻 update)
        customer.setCustomerStatus(request.getCustomerStatus() != null
                ? request.getCustomerStatus()
                : com.cretas.aims.entity.enums.CustomerStatus.LEAD);
        customer.setImportance(request.getImportance() != null
                ? request.getImportance()
                : com.cretas.aims.entity.enums.CustomerImportance.NORMAL);
        customer.setSource(request.getSource()); // null OK (允许未分类)
        // Sprint 4 W2 S-INVOICE-CLIENT-1: 客户级开票默认 (allow null)
        customer.setDefaultTaxRate(request.getDefaultTaxRate());
        customer.setDefaultInvoiceType(request.getDefaultInvoiceType());
        customer.setCreatedBy(createdBy);
        customer.setCreatedAt(LocalDateTime.now());
        return customer;
    }

    /**
     * 更新实体
     */
    public void updateEntity(Customer customer, UpdateCustomerRequest request) {
        if (request.getName() != null) {
            customer.setName(request.getName());
        }
        if (request.getType() != null) {
            customer.setType(request.getType());
        }
        if (request.getIndustry() != null) {
            customer.setIndustry(request.getIndustry());
        }
        if (request.getContactPerson() != null) {
            customer.setContactPerson(request.getContactPerson());
            customer.setContactName(request.getContactPerson());
        }
        if (request.getPhone() != null) {
            customer.setPhone(request.getPhone());
            customer.setContactPhone(request.getPhone());
        }
        if (request.getEmail() != null) {
            customer.setEmail(request.getEmail());
            customer.setContactEmail(request.getEmail());
        }
        if (request.getShippingAddress() != null) {
            customer.setShippingAddress(request.getShippingAddress());
        }
        if (request.getBillingAddress() != null) {
            customer.setBillingAddress(request.getBillingAddress());
        }
        if (request.getTaxNumber() != null) {
            customer.setTaxNumber(request.getTaxNumber());
        }
        if (request.getBusinessLicense() != null) {
            customer.setBusinessLicense(request.getBusinessLicense());
        }
        if (request.getPaymentTerms() != null) {
            customer.setPaymentTerms(request.getPaymentTerms());
        }
        if (request.getCreditLimit() != null) {
            customer.setCreditLimit(request.getCreditLimit());
        }
        // P1 #23 S-CREDIT-1: 2 字段 partial update
        if (request.getCreditPeriodDays() != null) {
            customer.setCreditPeriodDays(request.getCreditPeriodDays());
        }
        if (request.getCreditStatus() != null) {
            customer.setCreditStatus(request.getCreditStatus());
        }
        if (request.getRating() != null) {
            customer.setRating(request.getRating());
        }
        if (request.getRatingNotes() != null) {
            customer.setRatingNotes(request.getRatingNotes());
        }
        // Bug D fix (qa-prompt Rule 11) + audit M3 validation: status only ACTIVE/INACTIVE
        if (request.getStatus() != null) {
            String s = request.getStatus();
            if (!"ACTIVE".equalsIgnoreCase(s) && !"INACTIVE".equalsIgnoreCase(s)) {
                throw new IllegalArgumentException("status 必须是 ACTIVE 或 INACTIVE");
            }
            customer.setIsActive("ACTIVE".equalsIgnoreCase(s));
        }
        if (request.getNotes() != null) {
            customer.setNotes(request.getNotes());
        }
        // Sprint 4 W2 S-CRM-FULL-1: 4 字段 partial update
        if (request.getCustomerStatus() != null) {
            customer.setCustomerStatus(request.getCustomerStatus());
        }
        if (request.getImportance() != null) {
            customer.setImportance(request.getImportance());
        }
        if (request.getSource() != null) {
            customer.setSource(request.getSource());
        }
        if (request.getLastContactedAt() != null) {
            customer.setLastContactedAt(request.getLastContactedAt());
        }
        // Sprint 4 W2 S-INVOICE-CLIENT-1
        if (request.getDefaultTaxRate() != null) {
            customer.setDefaultTaxRate(request.getDefaultTaxRate());
        }
        if (request.getDefaultInvoiceType() != null) {
            customer.setDefaultInvoiceType(request.getDefaultInvoiceType());
        }
        customer.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 生成客户编码
     */
    private String generateCustomerCode() {
        return "CUS-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
