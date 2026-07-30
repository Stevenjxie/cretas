package com.cretas.aims.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    // ==================== Phase B Canvas Sales Target Hub (2026-05-22) ====================

    /**
     * AUD-4 JPA @Version optimistic lock — Canvas Phase B (2026-05-22).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * 提成阶梯配置 (可选). JSONB array of {minAmount, maxAmount, rate}.
     *
     * <p>Example:
     * <pre>{@code
     * [{"minAmount": 0,      "maxAmount": 100000, "rate": 5.0},
     *  {"minAmount": 100000, "maxAmount": 500000, "rate": 7.0},
     *  {"minAmount": 500000, "maxAmount": null,   "rate": 10.0}]
     * }</pre>
     *
     * <p>NULL = 用 flat {@code percentage} 列 (向后兼容).
     */
    @Type(JsonBinaryType.class)
    @Column(name = "tier_config", columnDefinition = "jsonb")
    private List<Map<String, Object>> tierConfig;

    /**
     * 提成周期类型: MONTHLY (默认) / QUARTERLY.
     */
    @Column(name = "period_type", length = 20, nullable = false)
    private String periodType;

    /**
     * 排行榜公式 hint (UI 显, 不参与执行).
     * 例: "SUM(amount)" / "SUM(amount) * rate" / "COUNT(orders)".
     */
    @Column(name = "leaderboard_formula", length = 500)
    private String leaderboardFormula;

    // ==================== Relations ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User sales;
}
