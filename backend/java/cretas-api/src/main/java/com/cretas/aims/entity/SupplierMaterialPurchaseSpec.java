package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/** Purchase-package configuration owned by one supplier-material relationship. */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "supplier_material_purchase_specs",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_material_purchase_spec_name",
                columnNames = {"factory_id", "supplier_material_id", "name"}),
        indexes = @Index(name = "idx_supplier_material_purchase_spec",
                columnList = "factory_id,supplier_material_id,active"))
@Where(clause = "deleted_at IS NULL")
public class SupplierMaterialPurchaseSpec extends BaseEntity {
    @Id @Column(length = 64)
    private String id;
    @PrePersist void assignId() { if (id == null) id = UUID.randomUUID().toString(); }
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;
    @Column(name = "supplier_material_id", nullable = false, length = 64)
    private String supplierMaterialId;
    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(name = "purchase_package_unit", nullable = false, length = 20)
    private String purchasePackageUnit;
    @Column(name = "inventory_base_unit", nullable = false, length = 20)
    private String inventoryBaseUnit;
    @Column(name = "conversion_factor", nullable = false, precision = 24, scale = 12)
    private BigDecimal conversionFactor;
    @PriceSensitive @Column(name = "quoted_price", precision = 18, scale = 6)
    private BigDecimal quotedPrice;
    @Column(nullable = false, length = 8)
    private String currency;
    @Column(name = "min_order_quantity", precision = 18, scale = 6)
    private BigDecimal minOrderQuantity;
    @Column(name = "lead_time_days")
    private Integer leadTimeDays;
    @Column(name = "is_default", nullable = false)
    private Boolean defaultSpec;
    @Column(nullable = false)
    private Boolean active;
    @Version @Column(nullable = false)
    private Long version;
}
