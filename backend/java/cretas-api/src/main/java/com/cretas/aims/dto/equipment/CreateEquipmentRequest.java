package com.cretas.aims.dto.equipment;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 创建设备请求
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Schema(description = "创建设备请求")
public class CreateEquipmentRequest {

    // Bug fix (module-verify 2026-07-03): web-admin 设备表单 POST 用 "equipmentCode"
    // (对齐 EquipmentDTO GET 响应字段名/entity.equipmentCode), RN 移动端沿用旧字段名 "code"
    // (对齐 entity.code, 二者在实体上是同值冗余列)。JsonAlias 让两端都能正确写入,
    // 不然 web-admin 传的 equipmentCode 被静默丢弃 → 后端总是 auto-generate 编号。
    @Schema(description = "设备编码(可选，不填则自动生成)")
    @Size(max = 50, message = "设备编码长度不能超过50个字符")
    @JsonAlias("equipmentCode")
    private String code;

    @Schema(description = "设备名称", required = true)
    @NotBlank(message = "设备名称不能为空")
    @Size(max = 191, message = "设备名称长度不能超过191个字符")
    private String name;

    @Schema(description = "设备类别(CUTTING/PACKAGING/FREEZING等)")
    @Size(max = 50, message = "设备类别长度不能超过50个字符")
    private String category;

    @Schema(description = "设备类型")
    @Size(max = 50, message = "设备类型长度不能超过50个字符")
    private String type;

    @Schema(description = "设备状态(IDLE/RUNNING/MAINTENANCE/MALFUNCTION/SCRAPPED)")
    @Size(max = 20, message = "设备状态长度不能超过20个字符")
    private String status;

    @Schema(description = "设备型号")
    @Size(max = 100, message = "设备型号长度不能超过100个字符")
    private String model;

    @Schema(description = "制造商")
    @Size(max = 100, message = "制造商长度不能超过100个字符")
    private String manufacturer;

    @Schema(description = "序列号")
    @Size(max = 100, message = "序列号长度不能超过100个字符")
    private String serialNumber;

    @Schema(description = "购买日期")
    private LocalDate purchaseDate;

    @Schema(description = "购买价格")
    @PositiveOrZero(message = "购买价格必须大于等于0")
    private BigDecimal purchasePrice;

    @Schema(description = "折旧年限")
    @Positive(message = "折旧年限必须大于0")
    private Integer depreciationYears;

    @Schema(description = "每小时成本")
    @PositiveOrZero(message = "每小时成本必须大于等于0")
    private BigDecimal hourlyCost;

    @Schema(description = "功率(千瓦)")
    @PositiveOrZero(message = "功率必须大于等于0")
    private BigDecimal powerConsumptionKw;

    @Schema(description = "设备位置")
    @Size(max = 100, message = "设备位置长度不能超过100个字符")
    private String location;

    @Schema(description = "维护间隔(小时)")
    @Positive(message = "维护间隔必须大于0")
    private Integer maintenanceIntervalHours;

    @Schema(description = "维护间隔(天)")
    @Positive(message = "维护间隔必须大于0")
    private Integer maintenanceIntervalDays;

    @Schema(description = "保修到期日")
    private LocalDate warrantyExpiryDate;

    @Schema(description = "备注")
    @Size(max = 5000, message = "备注长度不能超过5000个字符")
    private String notes;

    /** 乐观锁版本号 (编辑时必传, 来自 GET 响应); mismatch → 409 Conflict */
    @Schema(description = "乐观锁版本号")
    private Long version;
}