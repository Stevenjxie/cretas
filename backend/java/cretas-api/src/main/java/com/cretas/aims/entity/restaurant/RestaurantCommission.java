package com.cretas.aims.entity.restaurant;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.CommissionStatus;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 餐饮到访提成记录（#59 Phase 2）。
 *
 * <p>每次<b>计业绩</b>到访（{@link RestaurantVisit} visit_number &gt;= 2）经
 * {@code RestaurantVisitAttributedEvent} 触发结算一条。<b>不复用</b> {@code Commission} 表
 * （其 {@code salesOpportunityId} NOT NULL，餐饮无商机维度）。</p>
 *
 * <p>{@code tierSnapshot} / {@code rateSnapshot} / {@code cumulativeRevenueAtCalc} 是结算时刻的快照
 * （规则日后改不影响历史）。{@code commissionAmount} = {@code visitRevenue × rateSnapshot / 100}
 * （ROUND_HALF_UP scale 2）。{@code visitId} 唯一（幂等：同一次到访事件重复 fire 不重复建）。</p>
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #59 Phase 2)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "restaurant_commissions",
        indexes = {
                @Index(name = "idx_rest_comm_factory", columnList = "factory_id"),
                @Index(name = "idx_rest_comm_rep", columnList = "factory_id,rep_id"),
                @Index(name = "idx_rest_comm_status", columnList = "factory_id,status")
        }
)
@Where(clause = "deleted_at IS NULL")
public class RestaurantCommission extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** Tenant scope. */
    @Column(name = "factory_id", nullable = false, length = 100)
    private String factoryId;

    /** 来源到访（FK restaurant_visits.id）— 幂等键。 */
    @Column(name = "visit_id", nullable = false, length = 191)
    private String visitId;

    /** 营销员（FK users.id，冗余存储）。 */
    @Column(name = "rep_id", nullable = false)
    private Long repId;

    /** 使用的提成规则（FK commission_rules.id）。无规则则跳过不建记录，故 NOT NULL。 */
    @Column(name = "rule_id", nullable = false, length = 36)
    private String ruleId;

    /** 结算时所处档位 index（0-based）。NULL = flat percentage（无 tierConfig）。 */
    @Column(name = "tier_snapshot")
    private Integer tierSnapshot;

    /** 结算时套用的费率（%）— 阶梯档费率 or flat percentage。 */
    @PriceSensitive
    @Column(name = "rate_snapshot", precision = 5, scale = 2, nullable = false)
    private BigDecimal rateSnapshot;

    /** 本次到访营收。 */
    @PriceSensitive
    @Column(name = "visit_revenue", precision = 15, scale = 2, nullable = false)
    private BigDecimal visitRevenue;

    /** 本次提成金额 = visitRevenue × rateSnapshot / 100。 */
    @PriceSensitive
    @Column(name = "commission_amount", precision = 14, scale = 2, nullable = false)
    private BigDecimal commissionAmount;

    /** 结算时营销员当月累计业绩（审计/复核）。 */
    @PriceSensitive
    @Column(name = "cumulative_revenue_at_calc", precision = 15, scale = 2, nullable = false)
    private BigDecimal cumulativeRevenueAtCalc;

    /** 状态 PENDING/PAID/CANCELLED。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommissionStatus status;

    /** 发放时间。NULL = 未发放。 */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;
}
