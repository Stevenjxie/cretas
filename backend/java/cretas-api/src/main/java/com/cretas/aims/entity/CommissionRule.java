package com.cretas.aims.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 提成规则实体 — Sprint 7 wave 2 Track T5 (2026-05-20).
 *
 * <p>HJ Round 13 §15 业绩 6 项: 提成 commission. F006 销售 + 大客户 sales team comp 必上.
 *
 * <p>规则适用性 (most-specific wins, per {@code CommissionService.findApplicableRule}):
 * <ol>
 *   <li>salesId 匹配 + customerType 匹配 (最具体)</li>
 *   <li>salesId 匹配, customerType=NULL (sales-specific)</li>
 *   <li>salesId=NULL, customerType 匹配 (type-specific)</li>
 *   <li>salesId=NULL + customerType=NULL (兜底默认)</li>
 * </ol>
 *
 * <p>effectiveTo NULL = 永久有效. 查规则时 effectiveFrom &lt;= date &lt;= effectiveTo (或 NULL).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "sales"})
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "commission_rules",
        indexes = {
                @Index(name = "idx_comm_rule_factory", columnList = "factory_id"),
                @Index(name = "idx_comm_rule_sales", columnList = "sales_id"),
                @Index(name = "idx_comm_rule_customer_type", columnList = "customer_type"),
                @Index(name = "idx_comm_rule_active", columnList = "active")
        })
@SQLDelete(sql = "UPDATE commission_rules SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class CommissionRule extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** Tenant scope. */
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    /**
     * 销售员 ID (User.id). NULL = 适用所有销售员 (通用规则).
     */
    @Column(name = "sales_id")
    private Long salesId;

    /**
     * 客户类型 (e.g. "大客户" / "VIP" / "普通"). NULL = 适用所有客户类型.
     * 字符串而非 enum, 灵活配置.
     */
    @Column(name = "customer_type", length = 50)
    private String customerType;

    /** 提成百分比 (0-100). e.g. 5.50 = 5.5%. */
    @Column(name = "percentage", precision = 5, scale = 2, nullable = false)
    private BigDecimal percentage;

    /** 生效起始日 (含). */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** 生效结束日 (含). NULL = 永久生效. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** 启用标志. 关闭 = 不参与匹配 (软关闭, 不软删). */
    @Column(name = "active", nullable = false)
    private Boolean active;

    /** 创建人. */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    // ==================== Relations ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User sales;
}
