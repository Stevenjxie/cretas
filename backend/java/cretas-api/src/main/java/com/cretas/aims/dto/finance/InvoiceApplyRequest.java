package com.cretas.aims.dto.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tax-direct invoice apply request — passed to {@link com.cretas.aims.service.finance.TaxDirectInvoiceProvider}.
 *
 * <p>F-TAX-DIRECT-1 Sprint 5 spike (2026-05-19). Provider-agnostic shape:
 * Baiwang / Nuonuo / Ruihong all consume the same payload, provider impl
 * adapts to vendor-specific JSON schema internally.
 *
 * <p>spec: docs/superpowers/specs/2026-05-19-tax-direct-spike.md §3.1
 */
@Data
@Builder
@Schema(description = "数电票税局直连开票请求")
public class InvoiceApplyRequest {

    @Schema(description = "Cretas 工厂 ID (用于多租户隔离)", required = true)
    @NotBlank
    private String factoryId;

    @Schema(description = "Cretas InvoiceRecord ID (本地发票申请的 ID, Provider 透传)", required = true)
    @NotBlank
    private String invoiceRecordId;

    @Schema(description = "销售方 (公司) 税号", required = true, example = "91320100MA1XYZ")
    @NotBlank
    private String sellerTaxId;

    @Schema(description = "销售方 (公司) 全称", required = true)
    @NotBlank
    private String sellerName;

    @Schema(description = "购方 (客户) 税号", required = true)
    @NotBlank
    private String buyerTaxId;

    @Schema(description = "购方 (客户) 全称", required = true)
    @NotBlank
    private String buyerName;

    @Schema(description = "发票类型", allowableValues = {"DIGITAL_NORMAL", "DIGITAL_SPECIAL"},
            required = true, example = "DIGITAL_NORMAL")
    @NotBlank
    private String invoiceType;

    @Schema(description = "明细行", required = true)
    @NotEmpty
    private List<InvoiceLineItem> items;

    @Schema(description = "不含税合计 (各税率小计之和)", required = true)
    @NotNull
    private BigDecimal totalAmount;

    @Schema(description = "税额合计", required = true)
    @NotNull
    private BigDecimal taxAmount;

    @Schema(description = "备注 (上链到税局, 客户可见)")
    private String remark;

    @Data
    @Builder
    @Schema(description = "发票明细行")
    public static class InvoiceLineItem {
        @Schema(description = "商品名称", required = true)
        @NotBlank
        private String name;

        @Schema(description = "规格 (可选)")
        private String specification;

        @Schema(description = "单位 (可选, 如 件 / kg)")
        private String unit;

        @Schema(description = "数量", required = true)
        @NotNull
        private BigDecimal quantity;

        @Schema(description = "不含税单价", required = true)
        @NotNull
        private BigDecimal unitPrice;

        @Schema(description = "税率 (0-1 小数, 如 0.09 = 9%)", required = true, example = "0.09")
        @NotNull
        private BigDecimal taxRate;

        @Schema(description = "税收分类编码 (税局规定的商品税类码)")
        private String taxCategoryCode;
    }
}
