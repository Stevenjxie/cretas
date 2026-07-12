package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.OwnershipType;
import com.cretas.aims.logistics.entity.enums.TemperatureMode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 物流车属性 — 与现有 {@code vehicles.id} (see {@link com.cretas.aims.entity.Vehicle})
 * 1:1 关联, 不改 {@code Vehicle.capacity} (kg 载重语义)。物流方数/来源/区域/班次落此表
 * (spec §1, §2 决策 5)。
 *
 * <p>Maps to table {@code logistics_vehicle_profiles} (V20261028_01)。{@code vehicle_id}
 * 唯一 (DB {@code uq_lvp_vehicle}, 部分索引 WHERE deleted_at IS NULL — JPA 侧约束语义略严,
 * 见 {@link LogisticsOrderBatch} 类注释同类说明)。
 */
@Entity
@Table(name = "logistics_vehicle_profiles",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_lvp_vehicle_jpa", columnNames = {"vehicle_id"})
        },
        indexes = {
                @Index(name = "idx_lvp_factory", columnList = "factory_id")
        })
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsVehicleProfile extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (capacityCbm == null) {
            capacityCbm = BigDecimal.ZERO;
        }
        if (maxWeightKg == null) {
            maxWeightKg = BigDecimal.ZERO;
        }
        if (source == null) {
            source = OwnershipType.OWNED;
        }
        if (temperatureMode == null) {
            temperatureMode = TemperatureMode.DUAL_TEMP;
        }
        if (active == null) {
            active = true;
        }
    }

    /** FK → {@code vehicles.id} (plain String, no JPA relation). Unique per DB {@code uq_lvp_vehicle}. */
    @Column(name = "vehicle_id", length = 36, nullable = false)
    private String vehicleId;

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    @Column(name = "capacity_cbm", nullable = false, precision = 12, scale = 3)
    private BigDecimal capacityCbm;

    @Column(name = "max_weight_kg", nullable = false, precision = 12, scale = 3)
    private BigDecimal maxWeightKg;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    private OwnershipType source;

    @Column(name = "body_type", length = 64)
    private String bodyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "temperature_mode", length = 24, nullable = false)
    private TemperatureMode temperatureMode;

    @Column(name = "service_areas", length = 512)
    private String serviceAreas;

    @Column(name = "available_from", length = 8)
    private String availableFrom;

    @Column(name = "available_to", length = 8)
    private String availableTo;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
