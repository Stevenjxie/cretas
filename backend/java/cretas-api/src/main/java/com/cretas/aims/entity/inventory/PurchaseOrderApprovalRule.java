package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 采购订单审核规则 — Sprint2-J P-FIN-1.
 *
 * <p>per-factory 可配置阈值, 替代 PurchaseServiceImpl 中硬编码的
 * PRICE_ALERT_THRESHOLD = 10. approveOrder 评估时:
 * <ul>
 *   <li>任一行 priceAlert = true (varianceFromBom/Avg 绝对值 > priceVarianceThreshold) →
 *       触发 PENDING_FINANCE_REVIEW</li>
 *   <li>order.totalAmount > amountThreshold → 触发 PENDING_FINANCE_REVIEW</li>
 * </ul>
 *
 * <p>多条同 factory + enabled=true 时按 createdAt 取最新一条 (allow ops 灰度).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "purchase_order_approval_rules",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"factory_id", "rule_name"})
        },
        indexes = {
                @Index(name = "idx_poar_factory_enabled", columnList = "factory_id, enabled")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PurchaseOrderApprovalRule extends BaseEntity {

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

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    /** 价格偏差阈值百分比 (默认 10.00 = 10%). 当前价 vs BOM/移动均 偏差绝对值超过此值 → priceAlert. */
    @Column(name = "price_variance_threshold", nullable = false, precision = 5, scale = 2)
    private BigDecimal priceVarianceThreshold = new BigDecimal("10.00");

    /** 总金额阈值 (null = 不参与判断). 订单 totalAmount > 此值 → 触发财务审核. */
    @Column(name = "amount_threshold", precision = 18, scale = 2)
    private BigDecimal amountThreshold;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /**
     * AUD-4 P1 JPA 乐观锁 version (mirror Canvas Phase A pattern). 由 Flyway 默认 0 NOT NULL.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
