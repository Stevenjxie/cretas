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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 价格策略应用日志 — Canvas-Pricing Phase 4b 审计轨迹.
 *
 * <p>每次 {@code PricingEngine.calculate()} 调用持久化 1 行 (sister chat 实现).
 * Finance team 月度审计 cost-violation SO 用此表 (per spec §4 fool-proof rule).
 *
 * <p>{@code appliedStrategies} JSONB shape:
 * <pre>
 * [
 *   {"strategyId": "uuid", "strategyCode": "...", "type": "TIERED", "discountApplied": 1500.00},
 *   {"strategyId": "uuid", "strategyCode": "...", "type": "MEMBER", "discountApplied": 200.00}
 * ]
 * </pre>
 *
 * <p>Hot-path 查询: by businessEntityId DESC (audit lookup per SO line).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "pricing_application_logs",
        indexes = {
                @Index(name = "idx_pricing_logs_factory_entity",
                       columnList = "factory_id, business_entity_id, applied_at")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PricingApplicationLog extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** FK to {@code pricing_strategies.id}. NULL allowed (e.g. no-match row). */
    @Column(name = "strategy_id", length = 36)
    private String strategyId;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** e.g. "SO_LINE", "QUOTE_LINE". */
    @Column(name = "business_entity_type", length = 50)
    private String businessEntityType;

    /** e.g. "SO-20260531-0001/{productTypeId-UUID}" (orderNumber/productTypeId) — up to ~53 chars. */
    @Column(name = "business_entity_id", length = 120)
    private String businessEntityId;

    /** Unit price before applying any strategies. */
    @Column(name = "original_price", precision = 15, scale = 2)
    private BigDecimal originalPrice;

    /** Unit price after all stackable strategies applied. */
    @Column(name = "final_price", precision = 15, scale = 2)
    private BigDecimal finalPrice;

    /** {@code originalPrice - finalPrice} (positive = discount applied). */
    @Column(name = "discount", precision = 15, scale = 2)
    private BigDecimal discount;

    /** JSONB list of applied strategies (see class Javadoc). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applied_strategies", columnDefinition = "jsonb")
    private List<Map<String, Object>> appliedStrategies;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
}
