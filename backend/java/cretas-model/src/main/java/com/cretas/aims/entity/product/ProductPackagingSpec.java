package com.cretas.aims.entity.product;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/** A selectable outer-packaging specification for one SKU. */
@Entity
@Table(name = "product_packaging_specs")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class ProductPackagingSpec extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 100)
    private String productTypeId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "package_unit", nullable = false, length = 20)
    private String packageUnit;

    @Column(name = "base_unit", nullable = false, length = 20)
    private String baseUnit;

    @Column(name = "conversion_factor", nullable = false, precision = 20, scale = 8)
    private BigDecimal conversionFactor;

    @Column(name = "is_default", nullable = false)
    private Boolean defaultSpec = false;

    @Column(name = "is_active", nullable = false)
    private Boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Version
    @Column(nullable = false)
    private Long version = 0L;
}
