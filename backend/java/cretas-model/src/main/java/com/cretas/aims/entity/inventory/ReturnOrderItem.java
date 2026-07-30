package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"returnOrder"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "return_order_items",
        indexes = {
                @Index(name = "idx_roi_order", columnList = "return_order_id"),
                @Index(name = "idx_roi_material", columnList = "material_type_id"),
                @Index(name = "idx_roi_product", columnList = "product_type_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class ReturnOrderItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_order_id", nullable = false, length = 191)
    private String returnOrderId;

    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    @Column(name = "item_name", length = 200)
    private String itemName;

    @NotNull
    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @PriceSensitive
    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    @PriceSensitive
    @Column(name = "line_amount", precision = 15, scale = 2)
    private BigDecimal lineAmount;

    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(name = "reason", length = 500)
    private String reason;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_order_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ReturnOrder returnOrder;

    // ==================== 计算 ====================

    /** 行金额 = lineAmount (if persisted) else 数量 × 单价. Price-sensitive: returns null when stripped. */
    @Transient
    @PriceSensitive
    public BigDecimal getLineAmount() {
        // Defensive null guard — both lineAmount and unitPrice are @PriceSensitive,
        // stripped to null for warehouse_manager. Original ZERO fallback would have
        // leaked "0.00" for stripped rows, misleading non-price users into thinking
        // the return is free. Return null instead to signal "not visible" vs "free".
        if (lineAmount != null) return lineAmount;
        if (unitPrice == null || quantity == null) return null;
        return quantity.multiply(unitPrice).setScale(2, BigDecimal.ROUND_HALF_UP);
    }
}
