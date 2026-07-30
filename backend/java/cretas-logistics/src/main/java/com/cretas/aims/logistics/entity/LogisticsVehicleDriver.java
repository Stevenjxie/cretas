package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.DriverRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 车辆-司机绑定 (一车 2-3 司机, 白班/晚班/主备)。
 *
 * <p>{@code vehicleId} 引用 {@code vehicles.id}; {@code driverId} 引用
 * {@link LogisticsDriver#getId()}. 两者均以 plain String 列表达 FK (项目惯例)。
 *
 * <p>Maps to table {@code logistics_vehicle_drivers} (V20261028_01)。同车同司机
 * 同角色同班次不重复 (DB {@code uq_lvd_binding}, 部分索引 WHERE deleted_at IS NULL — JPA 侧
 * 约束语义略严, 见 {@link LogisticsOrderBatch} 类注释同类说明)。
 */
@Entity
@Table(name = "logistics_vehicle_drivers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_lvd_binding_jpa",
                        columnNames = {"vehicle_id", "driver_id", "role", "shift_start"})
        },
        indexes = {
                @Index(name = "idx_lvd_vehicle", columnList = "vehicle_id"),
                @Index(name = "idx_lvd_driver", columnList = "driver_id")
        })
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsVehicleDriver extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (role == null) {
            role = DriverRole.PRIMARY;
        }
        if (priority == null) {
            priority = 0;
        }
        if (active == null) {
            active = true;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    /** FK → {@code vehicles.id} (plain String, no JPA relation). */
    @Column(name = "vehicle_id", length = 36, nullable = false)
    private String vehicleId;

    /** FK → {@link LogisticsDriver#getId()} (plain String, no JPA relation). */
    @Column(name = "driver_id", length = 36, nullable = false)
    private String driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 16, nullable = false)
    private DriverRole role;

    @Column(name = "shift_start", length = 8)
    private String shiftStart;

    @Column(name = "shift_end", length = 8)
    private String shiftEnd;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
