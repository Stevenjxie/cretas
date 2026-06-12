package com.cretas.aims.dto.inventory;

import com.cretas.aims.dto.sales.ExtraFeeItem;
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
public class CreateSalesOrderRequest {

    @NotBlank(message = "客户ID不能为空")
    @Size(max = 191, message = "客户ID长度不能超过191个字符")
    private String customerId;

    private LocalDate orderDate;

    private LocalDate requiredDeliveryDate;

    @Size(max = 500, message = "发货地址长度不能超过500个字符")
    private String deliveryAddress;

    private BigDecimal discountAmount;

    @Size(max = 5000, message = "备注长度不能超过5000个字符")
    private String remark;

    @Size(max = 100, message = "业务员长度不能超过100个字符")
    private String salesperson;

    /** 关联报价单 ID (OperationalQuote.id), 实现报价→订单数据联动 */
    @Size(max = 191, message = "报价单ID长度不能超过191个字符")
    private String quoteId;

    private Boolean shippingIncluded;

    private BigDecimal shippingFee;

    /** 其他费用 (装卸/包装/加急等) */
    private List<ExtraFeeItem> extraFees;

    // ==================== Sprint 4 W2 S-INVOICE-CLIENT-1 Option 3 三层 default 链 (第 2 层 SO override) ====================

    /** 单据级默认税率 (%) — 未传 → SO 创建时 prefill 自 customer.defaultTaxRate.
     *  传值表示用户单据级临时覆盖客户级 default (e.g. 客户 13%, 此单 9%). */
    private BigDecimal defaultTaxRate;

    /** 单据级默认开票类型 — 同 defaultTaxRate, 未传 → fallback 自 customer.defaultInvoiceType */
    private com.cretas.aims.entity.enums.InvoiceType defaultInvoiceType;

    /**
     * P3 多仓: 外部渠道采购单标题 (如 "0601-熟食T+2").
     * 对应 SalesOrder.externalOrderTitle. Nullable — 普通订单不传.
     */
    @Size(max = 100, message = "采购单标题长度不能超过100个字符")
    private String externalOrderTitle;

    @Valid
    @NotEmpty(message = "订单行项目不能为空")
    private List<SalesOrderItemDTO> items;

    /**
     * Canvas V3: 动态字段 (dual-track)
     * 用户在 Canvas 里配的自定义字段, 前端通过这个 Map 传过来, 后端通过 DynamicFieldService
     * 写入 cf_{fieldCode} 物理列. 不配的字段前端不传, 这个 Map 为 null.
     */
    private Map<String, Object> customFields;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SalesOrderItemDTO {

        @NotBlank(message = "产品ID不能为空")
        @Size(max = 191, message = "产品ID长度不能超过191个字符")
        private String productTypeId;

        @Size(max = 200, message = "产品名称长度不能超过200个字符")
        private String productName;

        @NotNull(message = "数量不能为空")
        private BigDecimal quantity;

        @NotBlank(message = "单位不能为空")
        @Size(max = 20, message = "单位长度不能超过20个字符")
        private String unit;

        private BigDecimal unitPrice;

        private BigDecimal discountRate;

        private BigDecimal taxRate;

        @Size(max = 5000, message = "备注长度不能超过5000个字符")
        private String remark;

        @Size(max = 500, message = "规格长度不能超过500个字符")
        private String specification;

        private BigDecimal boxQuantity;

        // ==================== P3 多仓字段 ====================

        /**
         * 目的仓全名 (如 外部渠道-北仑总仓). Nullable — 普通订单不传.
         * 对应 SalesOrderItem.destWarehouseName.
         */
        @Size(max = 100, message = "目的仓名称长度不能超过100个字符")
        private String destWarehouseName;

        /** 目的仓 code, 备货看板按此分组. Nullable. */
        @Size(max = 32, message = "目的仓code长度不能超过32个字符")
        private String destWarehouseCode;

        /** 外部渠道采购单ID. Nullable. */
        @Size(max = 64, message = "外部采购单ID长度不能超过64个字符")
        private String externalPoId;

        /** 商品条码 (外部渠道配送签收). Nullable. */
        @Size(max = 64, message = "条码长度不能超过64个字符")
        private String barcode;

        /** 预约入场时段字符串 (如 "8:00-12:00"). Nullable. */
        @Size(max = 50, message = "预约时段长度不能超过50个字符")
        private String appointmentTime;

        /** 行级要求到达日期. Nullable. */
        private LocalDate requiredArrivalDate;
    }
}
