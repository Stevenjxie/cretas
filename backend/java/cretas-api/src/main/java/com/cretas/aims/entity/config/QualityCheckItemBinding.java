package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * 质检项与产品类型绑定实体
 *
 * 用于将质检项目关联到特定的产品类型
 * 支持覆盖默认配置（如不同产品的温度标准不同）
 *
 * 示例:
 * - 冷冻带鱼: 中心温度 ≤ -18°C
 * - 冷藏带鱼: 中心温度 ≤ 4°C
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Entity
@Table(name = "quality_check_item_bindings",
       indexes = {
           @Index(name = "idx_qcib_factory", columnList = "factory_id"),
           @Index(name = "idx_qcib_product", columnList = "product_type_id"),
           @Index(name = "idx_qcib_item", columnList = "quality_check_item_id")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_qcib_product_item",
                            columnNames = {"product_type_id", "quality_check_item_id"})
       })
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class QualityCheckItemBinding extends BaseEntity {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /**
     * 产品类型ID
     */
    @Column(name = "product_type_id", length = 100, nullable = false)
    private String productTypeId;

    /**
     * 质检项ID
     */
    @Column(name = "quality_check_item_id", length = 50, nullable = false)
    private String qualityCheckItemId;

    // ==================== 覆盖配置 ====================
    // 以下字段用于覆盖质检项的默认配置

    /**
     * 覆盖: 标准值
     */
    @Column(name = "override_standard_value", length = 100)
    private String overrideStandardValue;

    /**
     * 覆盖: 最小值
     */
    @Column(name = "override_min_value", precision = 15, scale = 4)
    private BigDecimal overrideMinValue;

    /**
     * 覆盖: 最大值
     */
    @Column(name = "override_max_value", precision = 15, scale = 4)
    private BigDecimal overrideMaxValue;

    /**
     * 覆盖: 抽样比例
     */
    @Column(name = "override_sampling_ratio", precision = 5, scale = 2)
    private BigDecimal overrideSamplingRatio;

    /**
     * 覆盖: 是否必检
     */
    @Column(name = "override_is_required")
    private Boolean overrideIsRequired;

    /**
     * 排序顺序（在该产品的检测项中的顺序）
     */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * 是否启用（该产品是否需要此检测项）
     */
    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 备注
     */
    @Column(name = "notes", length = 500)
    private String notes;

    // ==================== 关联关系 ====================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_check_item_id", referencedColumnName = "id",
                insertable = false, updatable = false)
    private QualityCheckItem qualityCheckItem;
}
