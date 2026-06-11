package com.cretas.aims.dto.bom;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新 BOM 物料请求 DTO.
 *
 * <p>Rule 17.1 cleanup (PR #370 audit). See {@link CreateBomItemRequest} for
 * background. {@code id} comes from the path variable; {@code factoryId} is
 * auto-filled by the controller. Everything else mirrors the create shape
 * (this endpoint is full-replace, not partial-patch).
 *
 * @author Cretas Team
 * @since 2026-05-11
 */
@Data
@Schema(description = "更新 BOM 物料请求")
public class UpdateBomItemRequest {

    @Schema(description = "产品类型ID", required = true)
    @NotBlank(message = "产品类型ID不能为空")
    @Size(max = 64, message = "产品类型ID长度不能超过64个字符")
    private String productTypeId;

    @Schema(description = "产品名称 (可选, 冗余字段方便查询)")
    @Size(max = 100, message = "产品名称长度不能超过100个字符")
    private String productName;

    @Schema(description = "原辅料类型ID", required = true)
    @NotBlank(message = "原辅料类型ID不能为空")
    @Size(max = 64, message = "原辅料类型ID长度不能超过64个字符")
    private String materialTypeId;

    @Schema(description = "原辅料名称 (可选, 冗余字段方便查询)")
    @Size(max = 100, message = "原辅料名称长度不能超过100个字符")
    private String materialName;

    @Schema(description = "成品含量/标准用量", required = true)
    @NotNull(message = "标准用量不能为空")
    @Positive(message = "标准用量必须大于0")
    private BigDecimal standardQuantity;

    @Schema(description = "出成率 (0-100 的百分比)")
    @PositiveOrZero(message = "出成率必须 >= 0")
    private BigDecimal yieldRate;

    @Schema(description = "计量单位")
    @Size(max = 20, message = "计量单位长度不能超过20个字符")
    private String unit;

    @Schema(description = "单价")
    @PositiveOrZero(message = "单价必须 >= 0")
    private BigDecimal unitPrice;

    @Schema(description = "税率 (百分比)")
    @PositiveOrZero(message = "税率必须 >= 0")
    private BigDecimal taxRate;

    @Schema(description = "物料分类 RAW / AUXILIARY / PACKAGING")
    @Size(max = 32, message = "物料分类长度不能超过32个字符")
    private String materialCategory;

    @Schema(description = "排序顺序")
    private Integer sortOrder;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过500个字符")
    private String remark;

    // #728 SP12: 组合装/嵌套 BOM 字段
    @Schema(description = "SP4: 按份投料标志")
    private Boolean perPortion;

    @Schema(description = "SP8: 半成品引用编码")
    @Size(max = 100)
    private String semiFinishedRefCode;

    @Schema(description = "SP12 #728: 子产品类型ID — 嵌套 BOM")
    @Size(max = 100)
    private String subProductTypeId;
}
