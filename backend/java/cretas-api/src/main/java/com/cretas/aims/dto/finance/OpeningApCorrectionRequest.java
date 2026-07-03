package com.cretas.aims.dto.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 修正"误走采购入库建账"产生的幽灵应付 (misrouted opening AP correction) 请求。
 *
 * <p>客户把期初存货当成采购入库录入 → 每个物料挂了一笔供应商应付 (AP_INVOICE)。实际客户并不欠供应商钱,
 * 那笔应付是"建账口径错了"的产物。本操作: 红冲 (reverse) 指定的幽灵应付 + 补一张正确的期初凭证
 * (借 1403 / 贷 4001), <b>库存数量不动</b> (货是对的, 只是会计口径错了)。
 *
 * <p><b>不硬编码任何租户/记录</b>: 要修正哪几笔由 {@code apTransactionIds} 传入, organizer 审阅后
 * 对 LIUSHANMEN 的具体 2 笔调用。幂等: 同一笔重复调用不会双红冲/双过账。
 */
@Data
@Schema(description = "幽灵应付修正请求 (红冲误挂应付 + 补期初凭证, 库存不动)")
public class OpeningApCorrectionRequest {

    @Schema(description = "要修正的应付挂账 (AP_INVOICE) 交易ID列表",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "待修正的应付交易ID不能为空")
    private List<String> apTransactionIds;

    @Schema(description = "修正原因/备注 (可空; 写入红冲交易与凭证摘要, 便于审计追溯)")
    @Size(max = 500)
    private String reason;
}
