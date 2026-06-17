package com.cretas.aims.dto.customer;

import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 客户数据传输对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CustomerDTO {
    private String id;
    private String factoryId;
    private String customerCode;
    private String name;
    private String type;
    private String industry;
    private String contactPerson;
    private String phone;
    private String email;
    private String shippingAddress;
    private String billingAddress;
    private String taxNumber;
    private String businessLicense;
    // 业务信息
    private String paymentTerms;
    private BigDecimal creditLimit;
    @PriceSensitive
    private BigDecimal currentBalance;

    /** P1 #23 S-CREDIT-1: 信用账期天数 */
    private Integer creditPeriodDays;

    /** P1 #23 S-CREDIT-1: 信用状态 (NORMAL/WARNING/SUSPENDED) */
    private com.cretas.aims.entity.enums.CreditStatus creditStatus;
    // 评级信息
    private Integer rating;
    private String ratingNotes;
    // 状态信息
    private Boolean isActive;
    private String notes;
    // 统计信息
    private Integer totalOrders;
    private BigDecimal totalSales;
    private LocalDateTime lastOrderDate;
    private BigDecimal averageOrderValue;
    // ==================== Sprint 4 W2 S-CRM-FULL-1 ====================

    /** 客户生命周期状态 (LEAD/INITIAL_CONTACT/.../RECOVERED) */
    private com.cretas.aims.entity.enums.CustomerStatus customerStatus;

    /** 客户重要程度 (VIP/IMPORTANT/NORMAL/LOW) */
    private com.cretas.aims.entity.enums.CustomerImportance importance;

    /** 客户来源渠道 */
    private com.cretas.aims.entity.enums.CustomerSource source;

    /** 最近接洽时间 — null = 从未接洽 */
    private LocalDateTime lastContactedAt;

    // ==================== Sprint 4 W2 S-INVOICE-CLIENT-1: 客户级开票默认 ====================

    /** 客户级默认税率 (%) — SO 创建 prefill 第 1 层 */
    private BigDecimal defaultTaxRate;

    /** 客户级默认开票类型 — SO 创建 prefill 第 1 层 */
    private com.cretas.aims.entity.enums.InvoiceType defaultInvoiceType;

    // 审计信息
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private String createdByName;

    /** Optimistic lock version — FE must send back on PUT to detect concurrent edits (409 Conflict on mismatch) */
    private Long version;

    // Sprint 4 W1 S-CUSTOMER-TAB-1 (tab 20): current assigned sales user
    private Long assignedSalesUserId;
    private LocalDateTime assignedSalesUserAssignedAt;
    /** Resolved via @ManyToOne join in Mapper — display "张三" instead of "User #100" in UI. */
    private String assignedSalesUserName;

    // ==================== 前端字段别名 ====================

    /**
     * customerType 别名（兼容前端）
     * 前端使用 customerType，后端使用 type
     */
    @JsonProperty("customerType")
    @Schema(description = "客户类型(前端别名)")
    public String getCustomerType() {
        return type;
    }
}
