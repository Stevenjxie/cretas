package com.cretas.aims.dto.equipment;

import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
/**
 * 设备数据传输对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EquipmentDTO {
    private String id;  // API层使用String，Service层进行Long↔String转换（FactoryEquipment.id是Long类型）
    private String factoryId;
    private String equipmentCode;
    private String name;
    private String type;
    private String model;
    private String manufacturer;
    private String serialNumber;
    // 购买信息
    private LocalDate purchaseDate;
    @PriceSensitive
    private BigDecimal purchasePrice;
    private Integer depreciationYears;
    @PriceSensitive
    private BigDecimal currentValue;
    // 运行信息
    private String status;
    private String location;
    @PriceSensitive
    private BigDecimal hourlyCost;
    private BigDecimal powerConsumptionKw;
    private Integer totalRunningHours;
    @PriceSensitive
    private BigDecimal totalOperatingCost;
    // 维护信息
    private Integer maintenanceIntervalHours;
    private LocalDate lastMaintenanceDate;
    private LocalDate nextMaintenanceDate;
    private LocalDate warrantyExpiryDate;
    private Integer maintenanceCount;
    @PriceSensitive
    private BigDecimal totalMaintenanceCost;
    private Boolean needsMaintenance;
    // 效率信息
    private Double utilizationRate;
    private Double efficiency;
    private Double availability;
    // 其他信息
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private String createdByName;

    /** Optimistic lock version — FE must echo on PUT to detect concurrent edits (409 on mismatch) */
    private Integer version;
    // 操作员信息
    private Long operatorId;
    private String operatorName;

    // ==================== 前端字段别名 ====================

    /**
     * equipmentName 别名（兼容前端）
     * 前端使用 equipmentName，后端使用 name
     */
    @JsonProperty("equipmentName")
    public String getEquipmentName() {
        return name;
    }

    /**
     * equipmentType 别名（兼容前端）
     * 前端使用 equipmentType，后端使用 type
     */
    @JsonProperty("equipmentType")
    public String getEquipmentType() {
        return type;
    }

    /**
     * code 别名（兼容前端）
     * 前端使用 code，后端使用 equipmentCode
     */
    @JsonProperty("code")
    public String getCode() {
        return equipmentCode;
    }

    /**
     * maintenanceInterval 别名（兼容前端）
     * 前端使用 maintenanceInterval，后端使用 maintenanceIntervalHours
     */
    @JsonProperty("maintenanceInterval")
    public Integer getMaintenanceInterval() {
        return maintenanceIntervalHours;
    }
}
