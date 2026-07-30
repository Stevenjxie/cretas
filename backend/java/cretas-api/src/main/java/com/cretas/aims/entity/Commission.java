package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.CommissionStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 提成实体 — Sprint 7 wave 2 Track T5 (2026-05-20).
 *
 * <p>由 {@code CommissionService.calculate(opportunityId)} 在 SalesOpportunity
 * stage 转为 CLOSED_WON 时自动触发 (event listener).
 *
 * <p>字段:
 * <ul>
 *   <li>salesOpportunityId — FK to sales_opportunities.id (业绩来源)</li>
 *   <li>ruleId — FK to commission_rules.id (使用的规则)</li>
 *   <li>salesId — 销售员 (User.id, 冗余存储防 SalesOpportunity 删后丢失)</li>
 *   <li>percentage — 当时 snapshot 的百分比 (规则可能日后改)</li>
 *   <li>amount — 提成金额 = valueAmount × percentage / 100</li>
 *   <li>status — PENDING/PAID/CANCELLED</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "opportunity", "rule", "sales"})
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "commissions",
        indexes = {
                @Index(name = "idx_comm_factory", columnList = "factory_id"),
                @Index(name = "idx_comm_opportunity", columnList = "sales_opportunity_id"),
                @Index(name = "idx_comm_sales", columnList = "sales_id"),
                @Index(name = "idx_comm_status", columnList = "status")
        })
@SQLDelete(sql = "UPDATE commissions SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class Commission extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    /** FK to sales_opportunities.id. */
    @Column(name = "sales_opportunity_id", nullable = false, length = 36)
    private String salesOpportunityId;

    /** FK to commission_rules.id. */
    @Column(name = "rule_id", nullable = false, length = 36)
    private String ruleId;

    /** 销售员 User.id (冗余便于查询). */
    @Column(name = "sales_id", nullable = false)
    private Long salesId;

    /** Snapshot 的百分比 (规则可能日后改, 此处冻结). */
    @Column(name = "percentage", precision = 5, scale = 2, nullable = false)
    private BigDecimal percentage;

    /** 提成金额. */
    @Column(name = "amount", precision = 14, scale = 2, nullable = false)
    private BigDecimal amount;

    /** 状态 PENDING/PAID/CANCELLED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommissionStatus status;

    /** 发放时间. NULL = 未发放. */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    // ==================== Relations ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_opportunity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private SalesOpportunity opportunity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", referencedColumnName = "id", insertable = false, updatable = false)
    private CommissionRule rule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User sales;
}
