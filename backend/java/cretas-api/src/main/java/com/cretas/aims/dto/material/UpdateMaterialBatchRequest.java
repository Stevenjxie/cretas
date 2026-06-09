package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 更新原材料批次请求对象 (qa-prompt v2.4 Rule 17.6 fix).
 *
 * 之前 PUT /material-batches/{id} 复用 {@link CreateMaterialBatchRequest}, 其 7 个
 * @NotNull/@NotBlank (materialTypeId/supplierId/receiptDate/receiptQuantity/quantityUnit/
 * totalWeight/totalValue) 在 partial body PUT 时会被 @Valid 错误拒绝. UI 编辑对话框
 * 把所有字段全填所以没爆, 但 API 直连/移动端/AI tool/Postman 做 partial update 就 400.
 *
 * 此 DTO 保留业务约束 (@DecimalMin/@Size) 用于值域校验, 但移除 null 拒绝, 允许 FE
 * 只发改动字段, mapper 对齐 null-guard 已经到位 (MaterialBatchMapper.updateEntity).
 *
 * @since 2026-04-24 (Rule 17.6 sweep)
 */
@Data
@Schema(description = "更新原材料批次请求")
public class UpdateMaterialBatchRequest {

    @Schema(description = "批次号 (编辑时不可改, 仅为字段对称存在; mapper 忽略)")
    @Size(max = 100, message = "批次号不能超过100个字符")
    private String batchNumber;

    @Schema(description = "原材料类型ID")
    private String materialTypeId;

    @Schema(description = "供应商ID")
    private String supplierId;

    @Schema(description = "入库日期")
    private LocalDate receiptDate;

    @Schema(description = "入库数量", example = "10")
    @DecimalMin(value = "0.01", message = "入库数量必须大于0")
    private BigDecimal receiptQuantity;

    @Schema(description = "数量单位", example = "箱")
    private String quantityUnit;

    @Schema(description = "箱数 (粗略统计用, 不参与库存计算)", example = "8")
    @PositiveOrZero(message = "箱数不能为负数")
    private Integer boxCount;

    @Schema(description = "每单位重量(kg)", example = "10.5")
    @DecimalMin(value = "0.001", message = "每单位重量必须大于0")
    private BigDecimal weightPerUnit;

    @Schema(description = "入库总重量(kg)")
    @DecimalMin(value = "0.001", message = "入库总重量必须大于0")
    private BigDecimal totalWeight;

    @Schema(description = "入库总价值(元)")
    @DecimalMin(value = "0.01", message = "入库总价值必须大于0")
    private BigDecimal totalValue;

    @Schema(description = "单价(元/kg)")
    @DecimalMin(value = "0.01", message = "单价必须大于0")
    private BigDecimal unitPrice;

    @Schema(description = "到期日期")
    private LocalDate expireDate;

    @Schema(description = "生产日期")
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

    @Schema(description = "发起单类型 (update 时通常不改; mapper 忽略)")
    @Size(max = 32)
    private String sourceDocType;

    @Schema(description = "发起单ID")
    @Size(max = 64)
    private String sourceDocId;

    @Schema(description = "Canvas 动态字段值")
    private Map<String, Object> customFields;

    /** SP4-A4: 生产厂家编号 */
    @Schema(description = "生产厂家编号 (SP4-A4, 可选)")
    @Size(max = 100, message = "厂家编号不能超过100个字符")
    private String factoryNumber;

    /** SP4-A4: 产地 */
    @Schema(description = "产地 (SP4-A4, 可选)")
    @Size(max = 200, message = "产地不能超过200个字符")
    private String originPlace;
}
