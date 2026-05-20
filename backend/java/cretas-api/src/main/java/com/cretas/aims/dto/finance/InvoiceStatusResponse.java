package com.cretas.aims.dto.finance;

import com.cretas.aims.entity.enums.TaxDirectStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Tax-direct invoice status query response.
 *
 * <p>F-TAX-DIRECT-1 Sprint 5 spike (2026-05-19). Polled by
 * scheduled job after async apply() to detect SUCCESS / FAILED transitions.
 *
 * <p>spec: docs/superpowers/specs/2026-05-19-tax-direct-spike.md §3.1
 */
@Data
@Builder
@Schema(description = "数电票开票状态查询响应")
public class InvoiceStatusResponse {

    @Schema(description = "Provider 内部 ID", required = true)
    private String providerInvoiceId;

    @Schema(description = "当前状态", required = true)
    private TaxDirectStatus status;

    @Schema(description = "税局生成的发票号 (status=SUCCESS 才有)")
    private String taxInvoiceNumber;

    @Schema(description = "税局生成的开票时间")
    private LocalDateTime issuedAt;

    @Schema(description = "PDF 下载 URL (status=SUCCESS, 否则 null)")
    private String pdfUrl;

    @Schema(description = "错误消息 (status=FAILED 时填充)")
    private String errorMessage;
}
