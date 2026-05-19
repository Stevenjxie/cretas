package com.cretas.aims.dto.finance;

import com.cretas.aims.entity.enums.TaxDirectStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Tax-direct invoice apply response — returned by {@link com.cretas.aims.service.finance.TaxDirectInvoiceProvider}.
 *
 * <p>F-TAX-DIRECT-1 Sprint 5 spike (2026-05-19). Async pattern: apply()
 * returns immediately with PROCESSING status; SUCCESS is polled via
 * {@link com.cretas.aims.service.finance.TaxDirectInvoiceProvider#queryStatus(String)}.
 *
 * <p>spec: docs/superpowers/specs/2026-05-19-tax-direct-spike.md §3.1
 */
@Data
@Builder
@Schema(description = "数电票税局直连开票响应")
public class InvoiceApplyResponse {

    @Schema(description = "Provider 内部 ID — Cretas 持久化到 invoice_records.tax_direct_provider_id",
            required = true)
    private String providerInvoiceId;

    @Schema(description = "Provider 名称 (BAIWANG / NUONUO / RUIHONG)", required = true)
    private String providerName;

    @Schema(description = "当前状态", required = true)
    private TaxDirectStatus status;

    @Schema(description = "Provider 估计的完成时间 (税局异步出票, 通常 5-30 分钟)")
    private LocalDateTime estimatedCompletionAt;

    @Schema(description = "错误消息 (status=FAILED 时填充)")
    private String errorMessage;

    @Schema(description = "Provider 原始响应 JSON (debug 用, 不上前端)")
    private String rawProviderResponse;
}
