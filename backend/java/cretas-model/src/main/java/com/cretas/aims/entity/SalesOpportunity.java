package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.OpportunityStage;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 销售商机实体 — Sprint 7 wave 2 Track T4 (2026-05-20).
 *
 * <p>HJ Round 13 §15 finding: 大客户 b2b 商机 8 阶段 funnel
 * (Lead → Qualified → Demo → Proposal → Negotiate → Verbal → Closed-Won / Closed-Lost).
 * Cretas Sprint 5 PR #53 F shipped {@link Customer}, but lacked stage tracking.
 *
 * <p>设计要点:
 * <ul>
 *   <li>factory_id RLS 防租户串数据</li>
 *   <li>customerId @ManyToOne {@link Customer} (FK to customers.id 字符串)</li>
 *   <li>stage enum 8 状态 (per {@link OpportunityStage})</li>
 *   <li>probability 0-100 (per stage default 可手动覆盖)</li>
 *   <li>valueAmount BigDecimal 商机金额 (敏感字段, 但商机 self-managed 不加 PriceSensitive)</li>
 *   <li>expectedCloseDate 预期成交日期</li>
 *   <li>ownerId User (业务员归属)</li>
 *   <li>closedAt nullable (CLOSED_WON/LOST 时填)</li>
 * </ul>
 *
 * <p>软删除: deleted_at IS NULL via @Where + @SQLDelete (per .claude/rules/database-entity-sync.md).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "customer", "owner"})
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sales_opportunities",
        indexes = {
                @Index(name = "idx_sales_opp_factory", columnList = "factory_id"),
                @Index(name = "idx_sales_opp_customer", columnList = "customer_id"),
                @Index(name = "idx_sales_opp_stage", columnList = "stage"),
                @Index(name = "idx_sales_opp_owner", columnList = "owner_id"),
                @Index(name = "idx_sales_opp_expected_close", columnList = "expected_close_date")
        })
@SQLDelete(sql = "UPDATE sales_opportunities SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class SalesOpportunity extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** Tenant scope — 防租户串数据 (RLS). */
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    /** FK to customers.id (string UUID). */
    @Column(name = "customer_id", nullable = false, length = 191)
    private String customerId;

    /** 商机标题 (业务员手工填, R2 context 必显). */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 商机阶段 — 8 个 funnel stage 之一. */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 20)
    private OpportunityStage stage;

    /**
     * 商机金额 (预估). BigDecimal precision 14 scale 2 (上限 ¥99,999,999,999.99).
     * 不加 @PriceSensitive — 商机金额是销售自己的预估, 不是 SO/订单等敏感公开数据.
     */
    @Column(name = "value_amount", precision = 14, scale = 2)
    private BigDecimal valueAmount;

    /**
     * 转化概率 (0-100). 默认值由 stage 决定 (per OpportunityStage.defaultProbability),
     * 用户可手动覆盖 (e.g. DEMO stage 但客户已口头承诺 → 手动调到 80%).
     */
    @Column(name = "probability", nullable = false)
    private Integer probability;

    /** 预期成交日期. */
    @Column(name = "expected_close_date")
    private LocalDate expectedCloseDate;

    /** 业务员归属 (User.id). */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 商机关闭时间 — CLOSED_WON / CLOSED_LOST 时自动填. */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** 备注 / 描述 (可选, 业务员补充上下文). */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 创建人 (业务员). */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** Optimistic lock — prevents silent last-write-wins on concurrent edits. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // ==================== Relations ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User owner;
}
