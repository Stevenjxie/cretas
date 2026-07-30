package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * 价格表行项目
 * 每行对应一种原料/成品的定价
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"priceList"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "price_list_items",
        indexes = {
                @Index(name = "idx_pli_list", columnList = "price_list_id"),
                @Index(name = "idx_pli_material", columnList = "material_type_id"),
                @Index(name = "idx_pli_product", columnList = "product_type_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PriceListItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "price_list_id", nullable = false, length = 191)
    private String priceListId;

    /** 原料类型ID（原料定价时使用） */
    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    /** 产品类型ID（成品定价时使用） */
    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    /** 物品名称（冗余） */
    @Column(name = "item_name", length = 200)
    private String itemName;

    @Column(name = "unit", length = 20)
    private String unit;

    /** 标准单价 */
    @Column(name = "standard_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal standardPrice;

    /** 最低限价 */
    @Column(name = "min_price", precision = 15, scale = 4)
    private BigDecimal minPrice;

    /** 最高限价 */
    @Column(name = "max_price", precision = 15, scale = 4)
    private BigDecimal maxPrice;

    @Column(name = "remark", length = 500)
    private String remark;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_list_id", referencedColumnName = "id", insertable = false, updatable = false)
    private PriceList priceList;
}
