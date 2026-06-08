package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 创建原材料批次请求对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Schema(description = "创建原材料批次请求")
public class CreateMaterialBatchRequest {

    @Schema(description = "批次号(可选，不填则自动生成，格式: MAT-YYYYMMDD-HHMMSS)")
    @Size(max = 100, message = "批次号不能超过100个字符")
    private String batchNumber;

    @Schema(description = "原材料类型ID", required = true)
    @NotNull(message = "原材料类型不能为空")
    private String materialTypeId;

    @Schema(description = "供应商ID", required = true)
    @NotNull(message = "供应商不能为空")
    private String supplierId;

    @Schema(description = "入库日期", required = true)
    @NotNull(message = "入库日期不能为空")
    private LocalDate receiptDate;

    @Schema(description = "入库数量", required = true, example = "10")
    @NotNull(message = "入库数量不能为空")
    @DecimalMin(value = "0.01", message = "入库数量必须大于0")
    private BigDecimal receiptQuantity;

    @Schema(description = "数量单位", required = true, example = "箱")
    @NotBlank(message = "数量单位不能为空")
    private String quantityUnit;

    @Schema(description = "箱数 (可选, 粗略统计用, 不参与库存计算; 实际库存以称重 kg 为准)", example = "8")
    @PositiveOrZero(message = "箱数不能为负数")
    private Integer boxCount;

    @Schema(description = "每单位重量(kg)", example = "10.5")
    @DecimalMin(value = "0.001", message = "每单位重量必须大于0")
    private BigDecimal weightPerUnit;

    @Schema(description = "入库总重量(kg)", required = true)
    @NotNull(message = "入库总重量不能为空")
    @DecimalMin(value = "0.001", message = "入库总重量必须大于0")
    private BigDecimal totalWeight;

    @Schema(description = "入库总价值(元)", required = true)
    @NotNull(message = "入库总价值不能为空")
    @DecimalMin(value = "0.01", message = "入库总价值必须大于0")
    private BigDecimal totalValue;

    @Schema(description = "单价(元/kg，可选，不填则自动计算)")
    @DecimalMin(value = "0.01", message = "单价必须大于0")
    private BigDecimal unitPrice;

    @Schema(description = "到期日期（可选，不填则根据保质期天数自动计算）")
    private LocalDate expireDate;

    @Schema(description = "生产日期（可选）")
    private LocalDate productionDate;

    @Schema(description = "存储位置")
    @Size(max = 100, message = "存储位置不能超过100个字符")
    private String storageLocation;

    @Schema(description = "质量证书")
    @Size(max = 100, message = "质量证书不能超过100个字符")
    private String qualityCertificate;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注不能超过500个字符")
    private String notes;

    @Schema(description = "发起单类型 (P0-17/B9+B10): PURCHASE_RECEIVE 采购入库 / MATERIAL_REQUISITION_RETURN 生产退料 / SALES_RETURN 销售退货 / INVENTORY_GAIN 盘盈入库(notes必填) / FREE_GIFT 赠品入库(notes必填) / MANUAL_ADJUST 手工调整(notes必填)")
    @Size(max = 32)
    private String sourceDocType;

    @Schema(description = "发起单ID (P0-17): 对应类型的单据主键")
    @Size(max = 64)
    private String sourceDocId;

    /**
     * D1 双仓流转 (2026-05-10 spec, PR #309 A1=A).
     * 可选 — null 时 service 层默认按 source 类型推: 采购入库 → WH-LOG, 报工/生产 → WH-WKS.
     */
    @Schema(description = "目标仓库ID (可选，null 时默认按业务场景推断：采购入库默认 WH-LOG)")
    @Size(max = 64)
    private String warehouseId;

    /**
     * Round 9 Fix (R8-α Gap #3 per-module template continuation): Canvas V3 dynamic
     * field values. Customer-configured fields (如:产地, 批次来源证明, 农残检测报告,
     * 供应商 QC 等级 etc.) arrive here and get persisted via DynamicFieldService.
     * Previously MaterialBatch entity had a customFields column but the DTO didn't
     * accept the values — frontend Canvas form submissions silently dropped them.
     */
    @Schema(description = "Canvas 动态字段值 (Round 9 Fix)")
    private Map<String, Object> customFields;
}