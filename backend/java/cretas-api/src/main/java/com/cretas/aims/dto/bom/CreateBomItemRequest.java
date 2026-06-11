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
 * 创建 BOM 物料请求 DTO.
 *
 * <p>Rule 17.1 cleanup (PR #370 audit): isolates wire contract from {@link
 * com.cretas.aims.entity.bom.BomItem} persistence model. The entity carries
 * {@code factoryId} ({@code @NotNull}) which is auto-filled server-side by
 * {@link com.cretas.aims.controller.BomController#addBomItem}; binding the
 * entity directly to {@code @RequestBody} forces the FE to send a phantom
 * {@code factoryId} or fail entity-level {@code @NotNull} validation 100%.
 *
 * <p>Fields excluded from this DTO (auto-managed by service / controller):
 * <ul>
 *   <li>{@code id} — DB IDENTITY auto-generated</li>
 *   <li>{@code factoryId} — auto-filled from {@code @PathVariable}</li>
 *   <li>{@code createdAt} / {@code updatedAt} / {@code deletedAt} — {@code BaseEntity} audit</li>
 *   <li>{@code productName} / {@code materialName} — redundant denormalized fields the service may rehydrate</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-11
 */
@Data
@Schema(description = "创建 BOM 物料请求")
public class CreateBomItemRequest {

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

    @Schema(description = "成品含量/标准用量 (每单位成品所需原料量)", required = true)
    @NotNull(message = "标准用量不能为空")
    @Positive(message = "标准用量必须大于0")
    private BigDecimal standardQuantity;

    @Schema(description = "出成率 (0-100 的百分比, 默认 100.00)", defaultValue = "100.00")
    @PositiveOrZero(message = "出成率必须 >= 0")
    private BigDecimal yieldRate;

    @Schema(description = "计量单位")
    @Size(max = 20, message = "计量单位长度不能超过20个字符")
    private String unit;

    @Schema(description = "单价")
    @PositiveOrZero(message = "单价必须 >= 0")
    private BigDecimal unitPrice;

    @Schema(description = "税率 (百分比, 默认 0)", defaultValue = "0")
    @PositiveOrZero(message = "税率必须 >= 0")
    private BigDecimal taxRate;

    @Schema(description = "物料分类 RAW (原料) / AUXILIARY (辅料) / PACKAGING (包材)",
            defaultValue = "RAW")
    @Size(max = 32, message = "物料分类长度不能超过32个字符")
    private String materialCategory;

    @Schema(description = "排序顺序", defaultValue = "0")
    private Integer sortOrder;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过500个字符")
    private String remark;

    // #728 SP12: 组合装/嵌套 BOM 字段
    @Schema(description = "SP4: 按份投料标志 — true = 每份成品用量固定，不随出成率折算", defaultValue = "false")
    private Boolean perPortion;

    @Schema(description = "SP8: 半成品引用编码 (组合装)")
    @Size(max = 100)
    private String semiFinishedRefCode;

    @Schema(description = "SP12 #728: 子产品类型ID — 非 null 时触发嵌套 BOM 成本递归聚合")
    @Size(max = 100)
    private String subProductTypeId;
}
