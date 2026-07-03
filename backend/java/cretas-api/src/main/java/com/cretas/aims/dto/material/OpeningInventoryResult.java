package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 期初建账结果。
 */
@Data
@Builder
@Schema(description = "期初建账结果")
public class OpeningInventoryResult {

    @Schema(description = "幂等业务键 (回显; 重复提交用它命中)")
    private String batchKey;

    @Schema(description = "是否幂等命中 (true=之前已建过, 本次未新建)")
    private boolean idempotentHit;

    @Schema(description = "本次建立的批次数")
    private int createdCount;

    @Schema(description = "计入期初凭证的行数 (有单价)")
    private int pricedCount;

    @Schema(description = "未录单价的行数 (诚实-null: 建了批次但不计入凭证金额)")
    private int uncostedCount;

    @Schema(description = "期初存货总价值 (Σ数量×单价, 即凭证借方 1403 金额)")
    private BigDecimal totalOpeningValue;

    @Schema(description = "期初凭证ID (全部行未录价时为 null)")
    private String voucherId;

    @Schema(description = "期初凭证号 (全部行未录价时为 null)")
    private String voucherNumber;

    @Schema(description = "建立的批次ID列表")
    private List<String> batchIds;

    @Schema(description = "建立的批次号列表")
    private List<String> batchNumbers;
}
