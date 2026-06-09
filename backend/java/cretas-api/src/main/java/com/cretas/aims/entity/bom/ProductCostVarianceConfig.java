package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * SP3 三价成本引擎 — 产品超支报警阈值配置.
 *
 * <p>存储每个工厂 (可选: 每个产品) 的成本超支百分比阈值.
 * 解析优先级:
 * <ol>
 *   <li>product-specific: factory_id + product_type_id NOT NULL</li>
 *   <li>factory-global:   factory_id + product_type_id IS NULL</li>
 *   <li>system default:   10% (在 Java 层 hardcode, 无 DB 行)</li>
 * </ol>
 *
 * @since SP3 (2026-06)
 */
@Entity(name = "ProductCostVarianceConfig")
@Table(
    name = "product_cost_variance_configs",
    indexes = {
        @Index(name = "idx_variance_config_factory", columnList = "factory_id"),
        @Index(name = "idx_variance_config_factory_product", columnList = "factory_id, product_type_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_variance_config_factory_product",
            columnNames = {"factory_id", "product_type_id"})
    }
)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class ProductCostVarianceConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 工厂ID */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /**
     * 产品类型ID (NULL 表示工厂全局默认, NOT NULL 表示产品专属).
     */
    @Column(name = "product_type_id", length = 64)
    private String productTypeId;

    /**
     * 超支百分比阈值 (e.g. 10.00 = 10%).
     * 当实际成本超过标准成本 {@code varianceThreshold}% 时触发报警.
     */
    @Column(name = "variance_threshold", nullable = false, precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal varianceThreshold = new BigDecimal("10.00");

    /** 是否启用 */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /** 备注 */
    @Column(name = "remark", length = 500)
    private String remark;
}
