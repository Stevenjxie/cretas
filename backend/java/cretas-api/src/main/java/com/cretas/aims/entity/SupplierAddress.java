package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.SupplierAddressType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * 供应商地址（多值）。
 *
 * <p>{@code is_primary = TRUE} 的那条镜像回 {@code suppliers.address}。
 * ⚠️ {@code TraceabilityServiceImpl} 会从 {@code suppliers.address} 切「省/市」当溯源产地,
 * 所以主地址语义必须稳定 = 注册/办公地址, 不要让发货地把它顶掉。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "supplier_addresses",
       indexes = {
           @Index(name = "idx_supplier_addresses_supplier",
                  columnList = "factory_id, supplier_id, sort_order")
       })
@Where(clause = "deleted_at IS NULL")
public class SupplierAddress extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** 多租户隔离键 —— 所有查询必须带上。 */
    @Column(name = "factory_id", nullable = false, length = 255)
    private String factoryId;

    @Column(name = "supplier_id", nullable = false, length = 191)
    private String supplierId;

    /** 用户自定义标签, e.g.「昆山仓」。为空时 UI 显示 addressType 的中文名。 */
    @Column(name = "label", length = 60)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "address_type", nullable = false, length = 32)
    private SupplierAddressType addressType = SupplierAddressType.BUSINESS;

    @Column(name = "address", nullable = false, length = 500)
    private String address;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(name = "contact_phone", length = 40)
    private String contactPhone;

    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary = false;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
