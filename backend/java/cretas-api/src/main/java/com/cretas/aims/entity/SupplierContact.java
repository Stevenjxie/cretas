package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.SupplierContactType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * 供应商联系人（多值）。
 *
 * <p>{@code is_primary = TRUE} 的那条会被服务层镜像回
 * {@code suppliers.contact_person / phone / email} —— 采购单 PDF、准入摘要、
 * 导入导出、AI Tool 等几十个既有读点因此零改动继续正确。见 V20261029_34 注释。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "supplier_contacts",
       indexes = {
           @Index(name = "idx_supplier_contacts_supplier",
                  columnList = "factory_id, supplier_id, sort_order")
       })
@Where(clause = "deleted_at IS NULL")
public class SupplierContact extends BaseEntity {

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

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_type", nullable = false, length = 32)
    private SupplierContactType contactType = SupplierContactType.OTHER;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "email", length = 100)
    private String email;

    /** 职务, e.g. 「销售经理」。 */
    @Column(name = "position", length = 100)
    private String position;

    /**
     * 主联系人。同一供应商下最多一条 (uq_supplier_contacts_primary 部分唯一索引强制),
     * 且它就是 {@code suppliers.contact_person/phone/email} 的来源。
     */
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
