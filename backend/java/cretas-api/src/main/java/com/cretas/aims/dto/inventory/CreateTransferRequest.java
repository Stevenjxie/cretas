package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateTransferRequest {

    @NotBlank(message = "调拨类型不能为空")
    @Size(max = 50, message = "调拨类型长度不能超过50个字符")
    private String transferType;

    @NotBlank(message = "调入方ID不能为空")
    @Size(max = 191, message = "调入方ID长度不能超过191个字符")
    private String targetFactoryId;

    /** 工厂内跨仓调拨: 调出仓库ID (nullable). V3 P0-5 */
    @Size(max = 191, message = "调出仓库ID长度不能超过191个字符")
    private String sourceWarehouseId;

    /** 工厂内跨仓调拨: 调入仓库ID (nullable). V3 P0-5 */
    @Size(max = 191, message = "调入仓库ID长度不能超过191个字符")
    private String targetWarehouseId;

    @NotNull(message = "调拨日期不能为空")
    private LocalDate transferDate;

    private LocalDate expectedArrivalDate;

    @Size(max = 5000, message = "备注长度不能超过5000个字符")
    private String remark;

    @Valid
    @NotEmpty(message = "调拨行项目不能为空")
    private List<TransferItemDTO> items;

    /**
     * Round 11 T4 — Canvas Integration Template slot for factory-configured
     * dynamic fields. Fields like 运输车牌号, 司机联系方式, 预计成本 land here
     * and are persisted to cf_* columns on internal_transfers via DynamicFieldService.
     */
    private Map<String, Object> customFields;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferItemDTO {

        /** RAW_MATERIAL 或 FINISHED_GOODS */
        @NotBlank(message = "物品类型不能为空")
        @Size(max = 50, message = "物品类型长度不能超过50个字符")
        private String itemType;

        /** 原料ID（itemType=RAW_MATERIAL时） */
        @Size(max = 191, message = "原料ID长度不能超过191个字符")
        private String materialTypeId;

        /** 产品ID（itemType=FINISHED_GOODS时） */
        @Size(max = 191, message = "产品ID长度不能超过191个字符")
        private String productTypeId;

        @Size(max = 200, message = "品项名称长度不能超过200个字符")
        private String itemName;

        @NotNull(message = "数量不能为空")
        @DecimalMin(value = "0.01", message = "调拨数量必须大于0")
        private BigDecimal quantity;

        @NotBlank(message = "单位不能为空")
        @Size(max = 20, message = "单位长度不能超过20个字符")
        private String unit;

        private BigDecimal unitPrice;

        @Size(max = 5000, message = "备注长度不能超过5000个字符")
        private String remark;
    }
}
