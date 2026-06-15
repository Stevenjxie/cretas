package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.Valid;
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
public class CreateReceiveRecordRequest {

    /** 关联的采购订单ID（可选，支持无单入库） */
    @Size(max = 191, message = "采购订单ID长度不能超过191个字符")
    private String purchaseOrderId;

    @NotBlank(message = "供应商ID不能为空")
    @Size(max = 191, message = "供应商ID长度不能超过191个字符")
    private String supplierId;

    @NotNull(message = "入库日期不能为空")
    private LocalDate receiveDate;

    @Size(max = 191, message = "仓库ID长度不能超过191个字符")
    private String warehouseId;

    @Size(max = 5000, message = "备注长度不能超过5000个字符")
    private String remark;

    @Valid
    @NotEmpty(message = "入库行项目不能为空")
    private List<ReceiveItemDTO> items;

    /**
     * Round 11 — Canvas Integration Template slot for factory-configured dynamic fields.
     * Fields like 运输温度记录, 质检报告附件, 外包装状态 land here and are persisted
     * to cf_* columns on purchase_receive_records via DynamicFieldService.
     */
    private Map<String, Object> customFields;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReceiveItemDTO {

        @NotBlank(message = "原料类型ID不能为空")
        @Size(max = 191, message = "原料类型ID长度不能超过191个字符")
        private String materialTypeId;

        @Size(max = 200, message = "原料名称长度不能超过200个字符")
        private String materialName;

        @NotNull(message = "到货数量不能为空")
        private BigDecimal receivedQuantity;

        @NotBlank(message = "单位不能为空")
        @Size(max = 20, message = "单位长度不能超过20个字符")
        private String unit;

        private BigDecimal unitPrice;

        @Size(max = 50, message = "质检结果长度不能超过50个字符")
        private String qcResult;

        @Size(max = 100, message = "厂号长度不能超过100个字符")
        private String factoryNumber;

        @Size(max = 200, message = "产地长度不能超过200个字符")
        private String originPlace;

        @Size(max = 5000, message = "备注长度不能超过5000个字符")
        private String remark;
    }
}
