package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 期初建账 (opening inventory onboarding) 单行录入项。
 *
 * <p>对应客户 ERP 迁移 / 首次建账时的一条起始库存 (某原料在某仓库的期初数量 + 单价)。
 * 前端把 Excel 逐行解析成 {@link OpeningInventoryItem} 列表放进 {@link OpeningInventoryRequest#getItems()}。
 *
 * <p><b>诚实-null (honest-null)</b>: {@code unitPrice} 可为空 —— 客户只知道数量不知道单价的原料,
 * 仍然建立批次 (数量入库), 但<b>不计入</b>期初凭证金额 (借 1403 / 贷 4001), 绝不臆造价值。
 */
@Data
@Schema(description = "期初建账单行 (某原料在某仓库的起始库存)")
public class OpeningInventoryItem {

    @Schema(description = "原材料类型ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "原材料类型不能为空")
    private String materialTypeId;

    @Schema(description = "目标仓库ID (可空; 空时默认按采购入库默认仓解析, 通常为原料仓/主仓)")
    @Size(max = 64)
    private String warehouseId;

    @Schema(description = "期初数量 (以该原料的计量单位计, 如 kg)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "期初数量不能为空")
    @DecimalMin(value = "0.01", message = "期初数量必须大于0")
    private BigDecimal quantity;

    @Schema(description = "数量单位 (可空; 空时取原料主数据规范单位)")
    @Size(max = 20)
    private String quantityUnit;

    @Schema(description = "期初单价 (元/单位, 可空 —— 诚实-null: 不填则建批次但不计入期初凭证金额)")
    @DecimalMin(value = "0.01", message = "期初单价必须大于0")
    private BigDecimal unitPrice;

    @Schema(description = "批次号 (可空; 空时系统自动生成, 防手敲重码)")
    @Size(max = 100)
    private String batchNumber;

    @Schema(description = "生产日期 (可空)")
    private LocalDate productionDate;

    @Schema(description = "到期日期 (可空; 空且原料配置保质期时按生产日期自动推算)")
    private LocalDate expiryDate;
}
