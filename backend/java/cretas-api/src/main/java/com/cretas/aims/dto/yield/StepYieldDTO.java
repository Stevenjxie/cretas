package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 单工序出成率 (YieldCalculationService 派生输出) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepYieldDTO {
    private Long workProcessTaskId;
    private Integer processOrder;
    private String processName;
    private BigDecimal totalInput;       // Σ input
    private BigDecimal totalOutput;      // Σ output
    private String inputUnit;
    private String outputUnit;
    /** 出成率 = Σoutput/Σinput; 单位不可比 (outputUnit!=inputUnit) 时为 null */
    private BigDecimal yieldRate;
    private Boolean unitComparable;      // false → yieldRate 不计, 仅展示量
    private BigDecimal carryover;        // 上道产出 − 本道投入 (>0 结转)
    /** A7 越界告警: null=未配区间/在区间内; 否则 "BELOW_MIN"/"ABOVE_MAX" */
    private String yieldAlert;
}
