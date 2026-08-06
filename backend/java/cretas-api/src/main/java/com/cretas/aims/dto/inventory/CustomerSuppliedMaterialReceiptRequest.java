package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Warehouse confirmation of one partial customer-supplied material receipt. */
@Data
public class CustomerSuppliedMaterialReceiptRequest {

    @NotBlank(message = "幂等键不能为空")
    @Size(max = 64, message = "幂等键不能超过64个字符")
    private String idempotencyKey;

    @NotNull(message = "实收数量不能为空")
    @DecimalMin(value = "0.01", message = "实收数量必须不小于0.01")
    @Digits(integer = 8, fraction = 2, message = "实收数量最多8位整数和2位小数")
    private BigDecimal receivedQuantity;

    /**
     * Optional canonical/display input from the UI. The task unit remains authoritative;
     * when supplied this value must normalize to the same inventory unit.
     */
    @Size(max = 20, message = "计量单位不能超过20个字符")
    private String unit;

    private LocalDate productionDate;
    private LocalDate expireDate;

    /** 客户/供应商给的批次号 —— 落在 {@code material_batches.supplier_batch_number}, 不是厂号。 */
    @Size(max = 100, message = "客户批次号不能超过100个字符")
    private String externalBatchNumber;

    /**
     * 合同号 —— 与采购收货同口径(行/批次级)。
     * 客户台账按合同号追溯来料, 客供料与采购来料没有理由只有一边能记。
     */
    @Size(max = 100, message = "合同号不能超过100个字符")
    private String contractNumber;

    /** 厂号 —— 生产厂家编号, 与「客户批次号」是两件事, 各占各的列。 */
    @Size(max = 100, message = "厂号不能超过100个字符")
    private String factoryNumber;

    /** 件数(件/箱) —— 抄码来料件数固定而重量不定。 */
    @Min(value = 0, message = "件数不能为负数")
    private Integer boxCount;

    @Size(max = 200, message = "产地不能超过200个字符")
    private String originPlace;

    @Size(max = 500, message = "备注不能超过500个字符")
    private String notes;
}
