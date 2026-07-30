package com.cretas.aims.entity;

import lombok.Data;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车辆实体
 * 用于仓库装车管理
 */
@Data
@Entity
@Table(name = "vehicles")
public class Vehicle {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", length = 36, nullable = false)
    private String factoryId;

    /**
     * 车牌号
     */
    @Column(name = "plate_number", length = 20, nullable = false)
    private String plateNumber;

    /**
     * 司机姓名
     */
    @Column(name = "driver_name", length = 50)
    private String driverName;

    /**
     * 司机电话
     */
    @Column(name = "driver_phone", length = 20)
    private String driverPhone;

    /**
     * 车辆载重容量 (kg)
     */
    @Column(name = "capacity", precision = 10, scale = 2)
    private BigDecimal capacity;

    /**
     * 当前装载量 (kg)
     */
    @Column(name = "current_load", precision = 10, scale = 2)
    private BigDecimal currentLoad = BigDecimal.ZERO;

    /**
     * 车辆状态
     * available - 可用
     * loading - 装载中
     * dispatched - 已发车
     * maintenance - 维护中
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VehicleStatus status = VehicleStatus.available;

    /**
     * 车辆类型
     */
    @Column(name = "vehicle_type", length = 50)
    private String vehicleType;

    /**
     * 备注
     */
    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public enum VehicleStatus {
        available, loading, dispatched, maintenance
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = java.util.UUID.randomUUID().toString();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
