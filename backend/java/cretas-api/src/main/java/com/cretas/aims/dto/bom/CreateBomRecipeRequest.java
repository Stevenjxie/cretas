package com.cretas.aims.dto.bom;

import com.cretas.aims.entity.bom.BomRecipe;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建 BOM 配方请求 DTO (M-BOM-1).
 *
 * <p>主子表创建一次完成: 主表元数据 + N 个子表项 (1+).
 *
 * <p>禁手写物料名称: {@code BomRecipeItemDTO.materialTypeId} {@code @NotBlank}
 * 强制从 raw_material_types 字典选择 (客户原话 May10 line 217-222).
 *
 * @author Cretas Team / Track D1
 * @since 2026-05-14
 */
@Data
@Schema(description = "创建 BOM 配方请求 (主子表一次创建)")
public class CreateBomRecipeRequest {

    @Schema(description = "产品类型ID", required = true)
    @NotBlank(message = "产品类型ID不能为空")
    @Size(max = 50, message = "产品类型ID长度不能超过50个字符")
    private String productTypeId;

    @Schema(description = "产品名称 (冗余字段, 默认从 product_types 取)")
    @Size(max = 200, message = "产品名称长度不能超过200个字符")
    private String productName;

    @Schema(description = "整产品级出成率 (0-100), 默认 100", defaultValue = "100")
    @DecimalMin(value = "0.01", message = "出成率必须 > 0")
    @DecimalMax(value = "100", message = "出成率必须 <= 100")
    private BigDecimal overallYieldRate;

    @Schema(description = "单份成品克数/件数 (客户: 200g/份)", required = true)
    @NotNull(message = "单份成品量不能为空")
    @DecimalMin(value = "0.0001", message = "单份成品量必须 > 0")
    private BigDecimal outputQuantityPerUnit;

    @Schema(description = "成品计量单位", required = true, defaultValue = "g")
    @NotBlank(message = "成品计量单位不能为空")
    @Size(max = 20)
    private String outputUnit;

    @Schema(description = "来源类型 (默认 MANUAL)")
    private BomRecipe.SourceType sourceType;

    @Schema(description = "来源样品ID (sourceType=SAMPLE_AUTOGEN 时必填)")
    @Size(max = 191)
    private String sourceSampleId;

    @Schema(description = "配方项列表", required = true)
    @NotEmpty(message = "配方项不能为空, 至少需要 1 项")
    @Valid
    private List<BomRecipeItemDTO> items;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过500个字符")
    private String notes;

    @Data
    @Schema(description = "配方项 (主子表的子)")
    public static class BomRecipeItemDTO {

        @Schema(description = "原料类型ID (硬外键 raw_material_types, 不允许手写)", required = true)
        @NotBlank(message = "原料类型ID不能为空, 必须从字典选择")
        @Size(max = 191)
        private String materialTypeId;

        @Schema(description = "标准用量 (每单位成品所需)", required = true)
        @NotNull(message = "标准用量不能为空")
        @DecimalMin(value = "0.0001", message = "标准用量必须 > 0")
        private BigDecimal standardQuantity;

        @Schema(description = "出成率 (0-100), 默认 100", defaultValue = "100")
        @DecimalMin(value = "0.01", message = "出成率必须 > 0")
        @DecimalMax(value = "100", message = "出成率必须 <= 100")
        private BigDecimal yieldRate;

        @Schema(description = "计量单位 (限定 g/kg/mg/ml/L/个/袋/箱/瓶/盒)", required = true)
        @NotBlank(message = "单位不能为空")
        @Pattern(regexp = "^(g|kg|mg|ml|L|个|袋|箱|瓶|盒)$",
                 message = "单位必须是 g/kg/mg/ml/L/个/袋/箱/瓶/盒 之一")
        private String unit;

        @Schema(description = "单价 (可选, 仅有 procurement:price:view 权限的角色可见)")
        @PositiveOrZero(message = "单价必须 >= 0")
        private BigDecimal unitPrice;

        @Schema(description = "税率 (百分比), 默认 0")
        @PositiveOrZero
        private BigDecimal taxRate;

        @Schema(description = "物料分类: RAW/AUXILIARY/PACKAGING, 默认 RAW")
        @Pattern(regexp = "^(RAW|AUXILIARY|PACKAGING)$",
                 message = "物料分类必须是 RAW/AUXILIARY/PACKAGING 之一")
        private String materialCategory;

        @Schema(description = "排序", defaultValue = "0")
        private Integer sortOrder;

        @Schema(description = "是否可选 (装饰菜等)", defaultValue = "false")
        private Boolean isOptional;

        @Schema(description = "替代分组 (同组互可替换)")
        @Size(max = 50)
        private String substituteGroup;

        @Schema(description = "备注")
        @Size(max = 500)
        private String remark;

        // ========== SP4-T3: 按份计量 + 半成品引用 ==========

        @Schema(description = "是否按份计量 (true = 每份用量, false = 按批次总量), 默认 false")
        private Boolean perPortion;

        @Schema(description = "半成品引用编码, 追踪原料→半成品→成品链路 (可选)")
        @Size(max = 100, message = "半成品引用编码长度不能超过100个字符")
        private String semiFinishedRefCode;

        // ========== SP1: 嵌套 BOM — 子产品引用 ==========

        /**
         * SP1: 子产品 product_type_id。
         * 当此行是一个半成品/组合装子组件时设置, 成本聚合将递归取子产品当前有效 BOM 的单位成本。
         * null = 普通原材料行 (用 unitPrice)。
         */
        @Schema(description = "SP1: 子产品类型ID (当此行是半成品/子组件时填写); null=普通原材料行")
        @Size(max = 100)
        private String subProductTypeId;

        // ========== SP8: 物料前三位主编码冗余 ==========

        @Schema(description = "SP8: 物料前三位主编码 (如 001/002/003); 可选, 不传则从关联物料自动回填")
        @Size(max = 3, message = "主编码长度不超过3个字符")
        private String primaryCodeRef;
    }
}
