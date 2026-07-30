package com.cretas.aims.entity.factory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * 工厂默认仓路由配置 (V20261027_30 seed 无, 纯 opt-in 配置).
 *
 * <p>把 {@link WarehouseResolver} 硬编码的默认仓 code (WH-LOG / WH-WKS / WH-RD) 变成
 * per-factory 可配置。每行 = 某工厂的某个 {@link WarehouseDefaultPurpose} 覆盖到自选的
 * {@code factory_warehouses.id}。
 *
 * <p><b>向后兼容</b>: 未配置 (无对应 purpose 行) 的工厂 resolver 回退到硬编码 code 查询,
 * 行为与现状 100% 一致。仅当工厂写入一行配置且该配置仍指向有效 (未软删) 仓库时才改变路由。
 *
 * @see com.cretas.aims.service.factory.WarehouseResolver
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "factory_warehouse_default",
        indexes = {
                @Index(name = "idx_fwd_factory", columnList = "factory_id"),
        }
)
@Where(clause = "deleted_at IS NULL")
public class FactoryWarehouseDefault extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 路由用途 (LOGISTICS_DEFAULT / WORKSHOP_DEFAULT / RD_DEFAULT). */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private WarehouseDefaultPurpose purpose;

    /** 覆盖目标仓库 id — 指向 {@code factory_warehouses.id}. */
    @Column(name = "warehouse_id", nullable = false, length = 64)
    private String warehouseId;
}
