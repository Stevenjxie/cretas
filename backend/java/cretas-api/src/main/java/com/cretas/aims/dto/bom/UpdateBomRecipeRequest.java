package com.cretas.aims.dto.bom;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 更新 BOM 配方请求 DTO (M-BOM-1, 仅 DRAFT 状态可改).
 *
 * <p>PUT 是 full-replace 语义: items 列表完全替换 (旧 items 全 soft-delete, 新 list insert).
 *
 * @author Cretas Team / Track D1
 * @since 2026-05-14
 */
@Data
@Schema(description = "更新 BOM 配方请求 (仅 DRAFT 状态)")
public class UpdateBomRecipeRequest {

    @Schema(description = "产品名称")
    @Size(max = 200)
    private String productName;

    @Schema(description = "已废弃：整体出成率由正式批次历史自动计算，人工传值会被忽略", deprecated = true)
    private BigDecimal overallYieldRate;

    @Schema(description = "单份成品量")
    @DecimalMin(value = "0.0001")
    private BigDecimal outputQuantityPerUnit;

    @Schema(description = "成品计量单位")
    @Size(max = 20)
    private String outputUnit;

    @Schema(description = "配方项 (full-replace)")
    @Valid
    private List<CreateBomRecipeRequest.BomRecipeItemDTO> items;

    @Schema(description = "备注")
    @Size(max = 500)
    private String notes;
}
