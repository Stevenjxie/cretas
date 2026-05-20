package com.cretas.aims.entity.pricing;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 价格策略 — Canvas-Pricing Phase 4b.
 *
 * <p>5 种策略类型见 {@link PricingStrategyType}.
 * 业务逻辑全部由 {@code PricingEngineImpl} 实现 (sister chat 填).
 *
 * <p>Hot-path 查询:
 * {@code findByFactoryIdAndEnabledTrueAndValidFromBeforeAndValidToAfterOrderByPriorityAsc(...)}
 * — partial index {@code idx_pricing_strategies_factory_active} on
 * {@code (factory_id, enabled, valid_from, valid_to, priority) WHERE deleted_at IS NULL}.
 *
 * <p>{@code rulesJson} shape per type (per spec §1):
 * <ul>
 *   <li>TIERED: {@code {"tiers": [{"minQty": N, "maxQty": M, "discountPct": P}, ...]}}</li>
 *   <li>PROMOTION: {@code {"thresholdAmount": A, "discountAmount": D, ...}}</li>
 *   <li>MEMBER: {@code {"membershipTier": "VIP", "discountPct": P, ...}}</li>
 *   <li>BUNDLE: {@code {"items": [{"productId": "...", "qty": N}, ...], "bundlePrice": P}}</li>
 *   <li>CYCLE: {@code {"cycle": "MONTH", "tiers": [{"minAmount": A, "rebatePct": P}, ...]}}</li>
 * </ul>
 *
 * <p>{@code scopeFilterJson} shape (filter which line items the strategy applies to):
 * <pre>
 * {
 *   "productCategories": ["frozen", "deli"],
 *   "customerGroups": ["VIP", "A"],
 *   "regions": ["east", "south"]
 * }
 * </pre>
 * Empty {@code {}} = applies to all.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "pricing_strategies",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"factory_id", "strategy_code"})
        },
        indexes = {
                @Index(name = "idx_pricing_strategies_factory_enabled", columnList = "factory_id, enabled")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PricingStrategy extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** Per-factory unique business key (e.g. "DINGDONG_TIERED_2026"). */
    @Column(name = "strategy_code", nullable = false, length = 100)
    private String strategyCode;

    @Column(name = "strategy_name", length = 255)
    private String strategyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 20)
    private PricingStrategyType strategyType;

    /** JSONB filter: which line items this strategy applies to. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope_filter_json", columnDefinition = "jsonb")
    private Map<String, Object> scopeFilterJson;

    /** JSONB rules: type-specific shape (see class Javadoc). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules_json", columnDefinition = "jsonb")
    private Map<String, Object> rulesJson;

    /** Lower number = higher priority. Default 100. */
    @Column(name = "priority", nullable = false)
    private Integer priority = 100;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** NULL = no lower bound. */
    @Column(name = "valid_from")
    private LocalDate validFrom;

    /** NULL = no upper bound. */
    @Column(name = "valid_to")
    private LocalDate validTo;

    /**
     * AUD-4 P1 (edge audit 2026-05-20): JPA optimistic locking version.
     *
     * <p>Hibernate manages this column automatically — first save inserts
     * with {@code version=0}, every subsequent {@code save()} or
     * {@code merge()} increments it. If two parallel PUT requests load
     * the same row (both see {@code version=N}), Hibernate's flush detects
     * the stale snapshot on the loser and throws
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}.
     * {@link com.cretas.aims.exception.GlobalExceptionHandler} catches it
     * and surfaces a 409 with the actionHint
     * "请刷新页面查看最新数据后再编辑" — the user can retry without
     * silently losing the other user's edits (Lost Update prevention).
     *
     * <p>Column is added via Flyway {@code V20260626_02} with
     * {@code DEFAULT 0 NOT NULL}; backfill is implicit because the migration
     * runs before any code reads/writes the column.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
