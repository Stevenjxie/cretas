package com.cretas.aims.logistics.dto.resource;

import com.cretas.aims.logistics.entity.enums.OwnershipType;
import com.cretas.aims.logistics.entity.enums.TemperatureMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 物流车辆列表项 — 现有 {@link com.cretas.aims.entity.Vehicle} 基础字段 +
 * 1:1 关联 {@link com.cretas.aims.logistics.entity.LogisticsVehicleProfile} 物流专属字段
 * 合并后的读模型。镜像前端 {@code web-admin/src/api/logistics.ts} {@code LogisticsVehicle}。
 *
 * <p>{@code version} = profile 的乐观锁版本；若该车辆尚未配置过物流 profile（新车/未维护），
 * 服务层用默认值合成（capacityCbm=0/source=OWNED/temperatureMode=DUAL_TEMP/active=true/version=0），
 * 首次 {@code PUT .../profile} 会插入新 profile 行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsVehicleDto {
    private String id;
    private String plateNumber;
    private String driverName;
    private String driverPhone;
    private BigDecimal capacityCbm;
    private BigDecimal maxWeightKg;
    private OwnershipType source;
    private String bodyType;
    private TemperatureMode temperatureMode;
    private List<String> serviceAreas;
    private String availableFrom;
    private String availableTo;
    private boolean active;
    private List<VehicleDriverBindingDto> drivers;
    private Long version;
}
