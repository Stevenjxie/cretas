package com.cretas.aims.entity.restaurant;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 餐饮营销员月度累计提成汇总（#59 Phase 2）。
 *
 * <p>邓总锁定方案：计提周期 = <b>月度累计</b>。营销员当月每发生一次<b>计业绩</b>到访
 * （{@link RestaurantVisit} visit_number &gt;= 2），{@code cumulativeRevenue} 累加本次营收；
 * 当累计额跨过 {@code CommissionRule.tierConfig} 配置的档位（邓总 UI 自填 15万/30万/50万 → 三档费率）时，
 * {@code currentTier}（0-based 档位 index）上移。</p>
 *
 * <p>{@code (factoryId, repId, periodKey)} 唯一一行；{@code periodKey} = 到访月份 {@code 'YYYY-MM'}。
 * {@link Version @Version} 乐观锁防并发到访事件重复结算撞车。</p>
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
@Table(name = "restaurant_rep_commission_summaries",
        indexes = {
                @Index(name = "idx_rest_rcs_factory", columnList = "factory_id"),
                @Index(name = "idx_rest_rcs_rep", columnList = "factory_id,rep_id"),
                @Index(name = "idx_rest_rcs_period", columnList = "factory_id,period_key")
        }
)
@Where(clause = "deleted_at IS NULL")
public class RestaurantRepCommissionSummary extends BaseEntity {

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

    /** 营销员（FK users.id）。 */
    @Column(name = "rep_id", nullable = false)
    private Long repId;

    /** 计提周期 'YYYY-MM'（月度累计）。 */
    @Column(name = "period_key", nullable = false, length = 7)
    private String periodKey;

    /** 当月累计复购业绩（跨档位依据）。 */
    @PriceSensitive
    @Column(name = "cumulative_revenue", precision = 15, scale = 2, nullable = false)
    private BigDecimal cumulativeRevenue;

    /** 当前所处档位 index（0-based，从 tierConfig 重算）。NULL = 无 tierConfig / flat。 */
    @Column(name = "current_tier")
    private Integer currentTier;

    /** 当月计业绩到访次数（展示用）。 */
    @Column(name = "attributed_visit_count", nullable = false)
    private Integer attributedVisitCount;

    /** 乐观锁。 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
