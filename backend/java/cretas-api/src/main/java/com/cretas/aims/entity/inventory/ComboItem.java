package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.ProductType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * 套餐/组合商品明细
 * product→product 关系（区别于 BomRecipeItem 的 product→material）
 * 餐饮套餐：一个 COMBO 类型的 ProductType 包含多个子 ProductType
 * 工厂组合装：一个 FINISHED_PRODUCT 组合包含多个子产品
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"comboProduct", "childProduct"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "combo_items",
        indexes = {
                @Index(name = "idx_ci_combo", columnList = "combo_product_id"),
                @Index(name = "idx_ci_child", columnList = "child_product_id"),
                @Index(name = "idx_ci_factory", columnList = "factory_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class ComboItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    /** 套餐/组合产品ID（父） */
    @Column(name = "combo_product_id", nullable = false, length = 191)
    private String comboProductId;

    /** 子产品ID */
    @Column(name = "child_product_id", nullable = false, length = 191)
    private String childProductId;

    /** 子产品名称（冗余） */
    @Column(name = "child_product_name", length = 200)
    private String childProductName;

    /** 子产品在套餐中的数量 */
    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity = BigDecimal.ONE;

    /** 排序 */
    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    /** 是否可替换（餐饮场景：可换菜） */
    @Column(name = "replaceable")
    private Boolean replaceable = false;

    @Column(name = "remark", length = 500)
    private String remark;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "combo_product_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ProductType comboProduct;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "child_product_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ProductType childProduct;
}
