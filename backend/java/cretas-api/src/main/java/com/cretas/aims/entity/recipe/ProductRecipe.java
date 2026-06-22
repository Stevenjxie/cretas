package com.cretas.aims.entity.recipe;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "product_recipes", indexes = {
        @Index(name = "idx_precipe_factory_product", columnList = "factory_id,product_type_id")
})
@Where(clause = "deleted_at IS NULL")
public class ProductRecipe extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 64)
    private String productTypeId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** 注射率, 如 0.20 */
    @Column(name = "injection_rate", precision = 8, scale = 4)
    private BigDecimal injectionRate;

    /** 熟制每锅基准原料(kg), 如 160 */
    @Column(name = "cooking_pot_base_kg", precision = 12, scale = 3)
    private BigDecimal cookingPotBaseKg;

    /** 第二锅起比例, 默认 0.3333, per-SKU 可配 */
    @Column(name = "subsequent_pot_ratio", nullable = false, precision = 8, scale = 4)
    private BigDecimal subsequentPotRatio;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
