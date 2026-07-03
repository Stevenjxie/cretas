package com.cretas.aims.dto.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 幽灵应付修正结果 (逐笔 outcome + 汇总)。
 */
@Data
@Builder
@Schema(description = "幽灵应付修正结果")
public class OpeningApCorrectionResult {

    @Schema(description = "成功修正的应付笔数 (含幂等命中的既有修正)")
    private int correctedCount;

    @Schema(description = "跳过的应付笔数 (非应付挂账/不存在/跨租户)")
    private int skippedCount;

    @Schema(description = "红冲的应付总额 (= 补记的期初存货总额)")
    private BigDecimal totalReversedAmount;

    @Schema(description = "逐笔明细")
    private List<Outcome> outcomes;

    @Data
    @Builder
    @Schema(description = "单笔修正明细")
    public static class Outcome {
        @Schema(description = "原应付交易ID")
        private String apTransactionId;
        @Schema(description = "结果: CORRECTED / ALREADY_CORRECTED / SKIPPED")
        private String status;
        @Schema(description = "红冲金额 (= 期初凭证金额)")
        private BigDecimal amount;
        @Schema(description = "红冲交易ID (AP_CREDIT_NOTE)")
        private String reversalTransactionId;
        @Schema(description = "补记的期初凭证ID")
        private String voucherId;
        @Schema(description = "跳过原因 (status=SKIPPED 时)")
        private String message;
    }
}
