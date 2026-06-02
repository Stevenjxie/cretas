package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** 整批出成率 + 工序链 (GET /yield 输出) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchYieldDTO {
    private Long batchId;
    private String batchNumber;
    private BigDecimal firstStepInput;       // 首道总投入 (998)
    private BigDecimal lastStepOutput;       // 末道总产出 (382.08 或 3184 盒)
    private String firstStepInputUnit;
    private String lastStepOutputUnit;
    /** 端到端累计出成率 (折算到首道单位; 跨单位用 standardGramsPerUnit 折算) */
    private BigDecimal cumulativeYieldRate;  // 0.3828
    private List<StepYieldDTO> steps;
    /** 数据是否完整 (每道都有 input+output); 缺则 cumulativeYieldRate 标不可信 */
    private Boolean complete;
    /** P1-3 (G4): Σ 所有道工时(分钟); 全 null → null */
    private Integer totalWorkMinutes;
    /** P1-3 (G4): Σ 所有道人数 — 跨道相加是"人次" (同一人多道会重复计), UI 必须标"总人次" */
    private Integer totalWorkers;
}
