package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Where;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
/**
 * 客户实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "createdBy", "shipmentRecords"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "customers",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"factory_id", "code"})
       },
       indexes = {
           @Index(name = "idx_customer_factory", columnList = "factory_id"),
           @Index(name = "idx_customer_is_active", columnList = "is_active")
       }
)
@Where(clause = "deleted_at IS NULL")
public class Customer extends BaseEntity {
    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
    @Column(name = "factory_id", nullable = false)
    private String factoryId;
    @Column(name = "code", nullable = false, length = 50)
    private String code;
    @Column(name = "customer_code", nullable = false, length = 50)
    private String customerCode;
    @Column(name = "name", nullable = false)
    private String name;
    @Column(name = "type", length = 50)
    private String type;

    // 前端兼容字段 - businessType 和 customerType
    @Column(name = "business_type", length = 50)
    private String businessType;

    @Column(name = "customer_type", length = 50)
    private String customerType;

    @Column(name = "industry", length = 100)
    private String industry;
    @Column(name = "contact_name", length = 100)
    private String contactName;
    @Column(name = "contact_person", length = 100)
    private String contactPerson;
    @Column(name = "contact_phone", length = 20)
    private String contactPhone;
    @Column(name = "phone", length = 20)
    private String phone;
    @Column(name = "contact_email", length = 100)
    private String contactEmail;
    @Column(name = "email", length = 100)
    private String email;
    @Column(name = "shipping_address")
    private String shippingAddress;
    @Column(name = "billing_address")
    private String billingAddress;
    @Column(name = "tax_number", length = 50)
    private String taxNumber;
    @Column(name = "business_license", length = 100)
    private String businessLicense;
    @Column(name = "payment_terms", length = 200)
    private String paymentTerms;

    /**
     * Sprint 4 W1 S-PAYMENT-DATE-1: 客户级对账日期 (每月几号对账). 1-31, nullable.
     * 业务日期, 由 DB CHECK 约束 (settlement_date BETWEEN 1 AND 31) 防越界.
     * 月底特殊值 31 在 2/4/6/9/11 月由调用方降级为月末.
     */
    @jakarta.validation.constraints.Min(value = 1, message = "对账日须在 1-31 之间")
    @jakarta.validation.constraints.Max(value = 31, message = "对账日须在 1-31 之间")
    @Column(name = "settlement_date")
    private Integer settlementDate;

    @PriceSensitive
    @Column(name = "credit_limit", precision = 12, scale = 2)
    private java.math.BigDecimal creditLimit;
    @PriceSensitive
    @Column(name = "current_balance", precision = 12, scale = 2)
    private java.math.BigDecimal currentBalance = java.math.BigDecimal.ZERO;

    /** P1 #23 S-CREDIT-1: 信用账期天数. NULL = 无账期, default 30. */
    @Column(name = "credit_period_days")
    private Integer creditPeriodDays = 30;

    /** P1 #23 S-CREDIT-1: 信用状态 (NORMAL / WARNING / SUSPENDED). SUSPENDED 时创建 SO 硬阻塞. */
    @Enumerated(EnumType.STRING)
    @Column(name = "credit_status", length = 20)
    private com.cretas.aims.entity.enums.CreditStatus creditStatus = com.cretas.aims.entity.enums.CreditStatus.NORMAL;

    @Column(name = "rating")
    private Integer rating;
    @Column(name = "rating_notes", columnDefinition = "TEXT")
    private String ratingNotes;

    /** 开户行 */
    @Column(name = "bank_name", length = 200)
    private String bankName;

    /** 银行账号 */
    @Column(name = "bank_account", length = 100)
    private String bankAccount;

    // ==================== Sprint 4 W2 S-CRM-FULL-1: 客户生命周期 / 战略价值 / 渠道归因 ====================

    /** 客户生命周期状态 (11 阶段: LEAD → ... → RECURRING / INACTIVE / LOST / BLACKLIST / RECOVERED) */
    @Enumerated(EnumType.STRING)
    @Column(name = "customer_status", length = 30)
    private com.cretas.aims.entity.enums.CustomerStatus customerStatus;

    /** 客户重要程度 (VIP / IMPORTANT / NORMAL / LOW) — 销售人工标记的战略价值, 与 rating 数字评分互补 */
    @Enumerated(EnumType.STRING)
    @Column(name = "importance", length = 20)
    private com.cretas.aims.entity.enums.CustomerImportance importance;

    /** 客户来源渠道 (11 渠道: EXHIBITION / REFERRAL / WEBSITE / ... / OTHER) — 销售漏斗归因 */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 30)
    private com.cretas.aims.entity.enums.CustomerSource source;

    /** 最近一次接洽时间 — 用于沉睡客户识别 + 销售跟进提醒 */
    @Column(name = "last_contacted_at")
    private java.time.LocalDateTime lastContactedAt;

    // ==================== Sprint 4 W2 S-INVOICE-CLIENT-1: 客户级开票默认 (Option 3 三层 default 链第 1 层) ====================

    /** 客户级默认税率 (%) — SalesOrder 创建时 prefill 到 SO.defaultTaxRate, 再下放到 Item.taxRate */
    @Column(name = "default_tax_rate", precision = 5, scale = 2)
    private java.math.BigDecimal defaultTaxRate;

    /** 客户级默认开票类型 — 同上 3 层 default 链, 最终落到 InvoiceRecord.invoiceType */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_invoice_type", length = 20)
    private com.cretas.aims.entity.enums.InvoiceType defaultInvoiceType;

    // ==================== Sprint 4 W1 S-CUSTOMER-TAB-1: 业务员归属 (tab 20 业务员变更 history) ====================

    /** Sprint 4 W1 S-CUSTOMER-TAB-1: 当前业务员 (tab 20 业务员变更 history 跟踪起点) */
    @Column(name = "assigned_sales_user_id")
    private Long assignedSalesUserId;

    @Column(name = "assigned_sales_user_assigned_at")
    private LocalDateTime assignedSalesUserAssignedAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** Optimistic lock version — prevents silent last-write-wins on concurrent edits */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User createdByUser;

    /** Sprint 4 W1 S-CUSTOMER-TAB-1: tab 20 当前业务员 User join (for header / list display). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_sales_user_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User assignedSalesUser;
    @com.fasterxml.jackson.annotation.JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ShipmentRecord> shipmentRecords = new ArrayList<>();

    @com.fasterxml.jackson.annotation.JsonIgnore
    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", referencedColumnName = "id", insertable = false, updatable = false)
    private List<CustomerTrackingRecord> trackingRecords = new ArrayList<>();
}
