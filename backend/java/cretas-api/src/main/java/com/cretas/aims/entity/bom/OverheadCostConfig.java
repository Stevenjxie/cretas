package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * 均摊费用配置实体
 * 记录间接费用的分摊配置
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-13
 */
@Entity(name = "BomOverheadCostConfig")
@Table(name = "bom_overhead_cost_configs", indexes = {
    @Index(name = "idx_overhead_factory", columnList = "factory_id")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class OverheadCostConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /**
     * 费用名称
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * 费用类别 (如: 设备折旧, 能源, 管理费用, 仓储等)
     */
    @Column(name = "category", length = 50)
    private String category;

    /**
     * 单价/费率.
     *
     * <p>Overhead rate is supplier-cost-equivalent: stripped to {@code null} for roles lacking
     * {@code procurement:price:view}. Mirrors {@link BomRecipeItem#unitPrice} (PR #455 BUG-2 sweep).
     */
    @PriceSensitive
    @Column(name = "unit_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /**
     * 计价单位 (如: 元/kg, 元/批次, 元/月)
     */
    @Column(name = "price_unit", length = 20)
    private String priceUnit;

    /**
     * 分摊方式 (PER_UNIT: 按单位分摊, PER_BATCH: 按批次分摊, FIXED: 固定金额)
     */
    @Column(name = "allocation_method", length = 20)
    @Builder.Default
    private String allocationMethod = "PER_UNIT";

    /**
     * 默认分摊比例/数量 (用于计算单位成品的分摊费用)
     */
    @Column(name = "allocation_rate", precision = 15, scale = 6)
    @Builder.Default
    private BigDecimal allocationRate = BigDecimal.ONE;

    /**
     * 是否启用
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 排序顺序
     */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * 备注
     */
    @Column(name = "remark", length = 500)
    private String remark;

    /**
     * 计算均摊成本
     * 成本 = 单价 * 分摊比例
     */
    @Transient
    public BigDecimal calculateCost() {
        return unitPrice.multiply(allocationRate).setScale(4, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * 计算指定数量的均摊成本
     */
    @Transient
    public BigDecimal calculateCost(BigDecimal quantity) {
        BigDecimal rate = quantity != null ? quantity : allocationRate;
        return unitPrice.multiply(rate).setScale(4, BigDecimal.ROUND_HALF_UP);
    }
}
