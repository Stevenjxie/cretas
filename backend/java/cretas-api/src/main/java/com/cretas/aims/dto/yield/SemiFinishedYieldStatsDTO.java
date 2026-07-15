package com.cretas.aims.dto.yield;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "半成品 SKU 全历史加权出成率")
public class SemiFinishedYieldStatsDTO {

    private String factoryId;
    private String semiFinishedSkuId;

    @Schema(description = "有效已小结批次实际投入合计（kg）")
    private BigDecimal totalInputKg;

    @Schema(description = "有效已小结批次实际产出合计（kg）")
    private BigDecimal totalOutputKg;

    @Schema(description = "加权出成率 = 总产出 kg / 总投入 kg；无有效数据时为 null")
    private BigDecimal weightedYieldRate;

    private long batchCount;

    @Schema(description = "统计事实来源")
    private String source;
}
