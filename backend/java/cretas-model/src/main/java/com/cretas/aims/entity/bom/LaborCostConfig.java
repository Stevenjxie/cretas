package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * 人工成本配置实体
 * 记录各工序的人工成本
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-13
 */
@Entity(name = "BomLaborCostConfig")
@Table(name = "bom_labor_cost_configs", indexes = {
    @Index(name = "idx_bom_labor_factory_product", columnList = "factory_id, product_type_id")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class LaborCostConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /**
     * 产品类型ID (NULL表示全局配置，适用于所有产品)
     */
    @Column(name = "product_type_id", length = 64)
    private String productTypeId;

    /**
     * 工序名称
     */
    @Column(name = "process_name", nullable = false, length = 100)
    private String processName;

    /**
     * 工序类别 (如: 前处理, 加工, 包装等)
     */
    @Column(name = "process_category", length = 50)
    private String processCategory;

    /**
     * 单价 (元/计价单位).
     *
     * <p>Labor rate is supplier-cost-equivalent: stripped to {@code null} for roles lacking
     * {@code procurement:price:view}. Mirrors {@link BomRecipeItem#unitPrice} (PR #455 BUG-2 sweep).
     */
    @PriceSensitive
    @Column(name = "unit_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /**
     * 计价单位 (如: 元/kg, 元/件, 元/小时)
     */
    @Column(name = "price_unit", length = 20)
    private String priceUnit;

    /**
     * 默认操作量/工作量 (每单位成品的默认工作量)
     */
    @Column(name = "default_quantity", precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal defaultQuantity = BigDecimal.ONE;

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
     * SP3: 每公斤标准人工成本 (元/kg).
     *
     * <p>用于 BOM 标准成本引擎的 per-kg 折算基准. NULL = 未配置 (诚实 gap).
     * 标记 @PriceSensitive — 仅 finance:read 角色可见.
     */
    @PriceSensitive
    @Column(name = "labor_cost_per_kg", precision = 15, scale = 4)
    private BigDecimal laborCostPerKg;

    /**
     * 计算人工成本
     * 成本 = 单价 * 数量
     */
    @Transient
    public BigDecimal calculateCost(BigDecimal quantity) {
        BigDecimal qty = quantity != null ? quantity : defaultQuantity;
        return unitPrice.multiply(qty).setScale(4, BigDecimal.ROUND_HALF_UP);
    }
}
