package com.cretas.aims.dto.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车辆 DTO
 * 用于仓库装车管理
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleDTO {

    /**
     * 车辆ID
     */
    private String id;

    /**
     * 工厂ID
     */
    private String factoryId;

    /**
     * 车牌号
     */
    private String plateNumber;

    /**
     * 司机姓名
     */
    private String driver;

    /**
     * 司机电话
     */
    private String phone;

    /**
     * 车辆载重容量 (kg)
     */
    private BigDecimal capacity;

    /**
     * 当前装载量 (kg)
     */
    private BigDecimal currentLoad;

    /**
     * 车辆状态
     * available - 可用
     * loading - 装载中
     * dispatched - 已发车
     * maintenance - 维护中
     */
    private String status;

    /**
     * 车辆类型
     */
    private String vehicleType;

    /**
     * 备注
     */
    private String notes;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
