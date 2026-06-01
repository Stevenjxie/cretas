package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 工序级出成率聚合 (厂级跨批, GET /production/yield/by-process 输出) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessYieldAggDTO {
    private String processName;
    private BigDecimal inputQuantity;   // Σ input_quantity, scale 2
    private BigDecimal outputQuantity;  // Σ output_quantity, scale 2
    private BigDecimal conversionRate;  // 出成率% 0-100, scale 1; 单位不可比时 null
    private BigDecimal wastageRate;     // 损耗率% 0-100, scale 1; 单位不可比时 null
    private String unit;                // 工序标准投入单位 (work_processes.unit)
    private Boolean unitComparable;     // wp.unit == wp.output_unit
    private Integer batchCount;
}
