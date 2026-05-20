package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.TargetPeriod;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 销售业绩目标实体 — Sprint 7 wave 2 Track T5 (2026-05-20).
 *
 * <p>HJ Round 13 §15 业绩 6 项: 月/季/年 target 设定. Cretas 缺.
 * F006 销售 + 大客户 sales team comp 必上.
 *
 * <p>设计要点:
 * <ul>
 *   <li>factory_id RLS 防租户串数据</li>
 *   <li>ownerId User (销售员 id)</li>
 *   <li>period enum MONTH/QUARTER/YEAR</li>
 *   <li>year + periodNum 唯一区分 (e.g. 2026-Q3 / 2026-M11)</li>
 *   <li>targetAmount BigDecimal 目标金额</li>
 *   <li>UNIQUE(factory_id, owner_id, period, year, period_num) 防重复</li>
 * </ul>
 *
 * <p>实际完成额 (actualAmount) 不存表 — 由 service 层 lazy 计算
 * (sum SalesOpportunity.valueAmount WHERE stage=CLOSED_WON AND closedAt in period).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "owner"})
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sales_targets",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_sales_target_unique",
                        columnNames = {"factory_id", "owner_id", "period", "year", "period_num"})
        },
        indexes = {
                @Index(name = "idx_sales_target_factory", columnList = "factory_id"),
                @Index(name = "idx_sales_target_owner", columnList = "owner_id"),
                @Index(name = "idx_sales_target_period", columnList = "period, year, period_num")
        })
@SQLDelete(sql = "UPDATE sales_targets SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class SalesTarget extends BaseEntity {

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

    /** 销售员归属 (User.id). */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 周期类型: MONTH / QUARTER / YEAR. */
    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false, length = 10)
    private TargetPeriod period;

    /** 年份 e.g. 2026. */
    @Column(name = "year", nullable = false)
    private Integer year;

    /**
     * 周期序号:
     * - MONTH: 1..12
     * - QUARTER: 1..4
     * - YEAR: 1 (单值, 占位)
     */
    @Column(name = "period_num", nullable = false)
    private Integer periodNum;

    /** 目标金额 (上限 ¥10M per Rule 1 防呆约束 — 业务层校验). */
    @Column(name = "target_amount", precision = 14, scale = 2, nullable = false)
    private BigDecimal targetAmount;

    /** 创建人 (User.id). */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    // ==================== Relations ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User owner;
}
