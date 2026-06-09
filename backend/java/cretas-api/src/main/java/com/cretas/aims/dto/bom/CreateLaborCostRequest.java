package com.cretas.aims.dto.bom;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建人工成本配置请求 DTO.
 *
 * <p>Rule 17.1 cleanup (PR #370 audit): isolates wire contract from {@link
 * com.cretas.aims.entity.bom.LaborCostConfig} persistence model. {@code
 * factoryId} is auto-filled by {@link
 * com.cretas.aims.controller.BomController#addLaborCost}; {@code id} is DB-
 * generated.
 *
 * <p>{@code productTypeId} is intentionally nullable — null means "global
 * config" applicable to all products.
 *
 * @author Cretas Team
 * @since 2026-05-11
 */
@Data
@Schema(description = "创建人工成本配置请求")
public class CreateLaborCostRequest {

    @Schema(description = "产品类型ID (NULL = 全局配置)")
    @Size(max = 64, message = "产品类型ID长度不能超过64个字符")
    private String productTypeId;

    @Schema(description = "工序名称", required = true)
    @NotBlank(message = "工序名称不能为空")
    @Size(max = 100, message = "工序名称长度不能超过100个字符")
    private String processName;

    @Schema(description = "工序类别")
    @Size(max = 50, message = "工序类别长度不能超过50个字符")
    private String processCategory;

    @Schema(description = "单价", required = true)
    @NotNull(message = "单价不能为空")
    @PositiveOrZero(message = "单价必须 >= 0")
    private BigDecimal unitPrice;

    @Schema(description = "计价单位 (元/kg, 元/件, 元/小时 等)")
    @Size(max = 20, message = "计价单位长度不能超过20个字符")
    private String priceUnit;

    @Schema(description = "默认操作量/工作量", defaultValue = "1")
    @PositiveOrZero(message = "默认操作量必须 >= 0")
    private BigDecimal defaultQuantity;

    @Schema(description = "是否启用", defaultValue = "true")
    private Boolean isActive;

    @Schema(description = "排序顺序", defaultValue = "0")
    private Integer sortOrder;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过500个字符")
    private String remark;

    /** SP3: 每公斤标准人工成本 (元/kg). NULL = 未配置. */
    @Schema(description = "SP3: 每公斤标准人工成本 (元/kg), null=未配置")
    @PositiveOrZero(message = "每公斤人工成本必须 >= 0")
    private BigDecimal laborCostPerKg;
}
