package com.cretas.aims.entity.pricing;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * SP5: 工厂毛利红线配置.
 *
 * <p>每个工厂可以配置一条"全局默认"记录 (productTypeId = null)，
 * 也可以对具体产品类型设置更细粒度的覆盖 (productTypeId 非 null).
 *
 * <p>查找优先级: product-level > factory-global.
 * 若均无配置 → 毛利检查跳过（不阻断报价）.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "factory_gross_margin_configs",
        indexes = {
                @Index(name = "idx_fgmc_factory", columnList = "factory_id"),
                @Index(name = "idx_fgmc_factory_product", columnList = "factory_id, product_type_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_fgmc_factory_product",
                        columnNames = {"factory_id", "product_type_id"})
        }
)
@Where(clause = "deleted_at IS NULL")
public class FactoryGrossMarginConfig extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 产品类型ID. null = 工厂全局默认配置.
     */
    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    /**
     * 目标毛利率 (0-1, e.g. 0.30 = 30%).
     * price-sensitive: 仅有权限角色可见.
     */
    @PriceSensitive
    @Column(name = "target_gross_margin", nullable = false, precision = 6, scale = 4)
    private BigDecimal targetGrossMargin;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;
}
