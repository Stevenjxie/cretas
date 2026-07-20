package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "supplier_materials",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_material_identity",
                columnNames = {"factory_id", "supplier_id", "material_type_id"}),
        indexes = {
                @Index(name = "idx_supplier_material_supplier", columnList = "factory_id,supplier_id,active"),
                @Index(name = "idx_supplier_material_material", columnList = "factory_id,material_type_id,active")
        })
@Where(clause = "deleted_at IS NULL")
public class SupplierMaterial extends BaseEntity {
    @Id @Column(name = "id", nullable = false, length = 64)
    private String id;
    @PrePersist void assignId() { if (id == null) id = UUID.randomUUID().toString(); }
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;
    @Column(name = "supplier_id", nullable = false, length = 191)
    private String supplierId;
    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;
    @Column(name = "supplier_material_code", length = 100)
    private String supplierMaterialCode;
    @PriceSensitive
    @Column(name = "default_purchase_price", precision = 18, scale = 6)
    private BigDecimal defaultPurchasePrice;
    @Column(name = "currency", nullable = false, length = 8)
    private String currency;
    @Column(name = "purchase_unit", nullable = false, length = 20)
    private String purchaseUnit;
    @Column(name = "min_order_quantity", precision = 18, scale = 6)
    private BigDecimal minOrderQuantity;
    @Column(name = "lead_time_days")
    private Integer leadTimeDays;
    @Column(name = "preferred", nullable = false)
    private Boolean preferred;
    @Column(name = "active", nullable = false)
    private Boolean active;
    @Version @Column(name = "version", nullable = false)
    private Long version;
}
