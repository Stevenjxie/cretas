package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.SettlementType;
import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Where;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
/**
 * 供应商实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "createdBy", "materialBatches"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "suppliers",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"factory_id", "code"})
       },
       indexes = {
           @Index(name = "idx_supplier_factory", columnList = "factory_id"),
           @Index(name = "idx_supplier_is_active", columnList = "is_active")
       }
)
@Where(clause = "deleted_at IS NULL")
public class Supplier extends BaseEntity {
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
    @Column(name = "supplier_code", nullable = false, length = 50)
    private String supplierCode;
    @Column(name = "name", nullable = false)
    private String name;
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
    @Column(name = "address")
    private String address;
    @Column(name = "business_license", length = 100)
    private String businessLicense;
    @Column(name = "tax_number", length = 50)
    private String taxNumber;
    @Column(name = "bank_name", length = 100)
    private String bankName;
    @Column(name = "bank_account", length = 50)
    private String bankAccount;
    @Column(name = "supplied_materials", columnDefinition = "TEXT")
    private String suppliedMaterials;
    @Column(name = "payment_terms", length = 200)
    private String paymentTerms;
    @Column(name = "delivery_days")
    private Integer deliveryDays;

    // ==================== SP6: 结算条件 ====================

    /**
     * SP6 — 供应商结算类型（预付/月结/账期等）。
     * 与 PurchaseOrder.settlementType 搭配使用，单据级可覆盖供应商默认。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_terms_type", length = 32)
    private SettlementType paymentTermsType;

    /**
     * SP6 — 账期天数（CREDIT_PERIOD 结算时的默认天数）。null = 未配置。
     */
    @Column(name = "credit_days")
    private Integer creditDays;

    // 前端兼容字段
    @Column(name = "business_type", length = 50)
    private String businessType;

    @Column(name = "credit_level", length = 20)
    private String creditLevel;

    @Column(name = "delivery_area", length = 200)
    private String deliveryArea;

    @PriceSensitive
    @Column(name = "credit_limit", precision = 12, scale = 2)
    private BigDecimal creditLimit;
    @PriceSensitive
    @Column(name = "current_balance", precision = 12, scale = 2)
    private BigDecimal currentBalance = BigDecimal.ZERO;
    @Column(name = "rating")
    private Integer rating;
    @Column(name = "rating_notes", columnDefinition = "TEXT")
    private String ratingNotes;
    @Column(name = "quality_certificates", columnDefinition = "TEXT")
    private String qualityCertificates;
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

    /**
     * Canvas-P3: 准入审核状态 PENDING/APPROVED/REJECTED/SUSPENDED.
     * 默认 APPROVED 兼容历史数据 (per V20260824_04).
     */
    @Column(name = "admission_status", length = 20)
    private String admissionStatus = "APPROVED";

    /** Canvas-P3: 准入审核时间. */
    @Column(name = "admission_reviewed_at")
    private java.time.LocalDateTime admissionReviewedAt;

    /** Canvas-P3: 准入审核人 user_id. */
    @Column(name = "admission_reviewer_id")
    private Long admissionReviewerId;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User createdByUser;
    @com.fasterxml.jackson.annotation.JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "supplier", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MaterialBatch> materialBatches = new ArrayList<>();
}
