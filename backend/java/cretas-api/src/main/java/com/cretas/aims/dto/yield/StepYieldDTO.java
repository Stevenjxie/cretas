package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
    /** P1-3 (G4): Σ 本道各次报工工时(分钟); 全 null → null (B3 效率指标用) */
    private Integer totalWorkMinutes;
    /** P1-3 (G4): Σ 本道各次报工人数; 全 null → null (本道人次, 单道通常≈实际人数) */
    private Integer totalWorkers;

    // ── 单元 A.4/A.5: 逐道成本 (Σ 本道各次报工的持久化成本, null-safe) ──────────────
    /** Σ 本道人工成本(元); 全 null → null (绝不默认 0) */
    private BigDecimal laborCost;
    /** Σ 本道材料成本(元); 全 null → null (绝不默认 0) */
    private BigDecimal materialCost;
    /** 本道总成本 = laborCost + materialCost (null-safe; 两者全 null → null) */
    private BigDecimal stepCost;

    // ── 适配单元3: 传统报工证据/工时段/副产物/损耗/留样 (本道各次报工合并) ──────────────
    /** 证据图片 URL (本道各次报工 photos 合并去重; 无则 null) */
    private List<String> photos;
    /** 多时段×人数工时明细 (本道各次报工 laborSegments 拼接; 无则 null) */
    private List<Map<String, Object>> laborSegments;
    /** 副产物明细 (本道各次报工 byproducts 拼接; 无则 null) */
    private List<Map<String, Object>> byproducts;
    /** Σ 本道损耗量 (null-safe; 全 null → null, 绝不默认 0) */
    private BigDecimal wasteQuantity;
    /** Σ 本道留样数 (null-safe; 全 null → null; 通常仅末道有) */
    private Integer sampleRetainQuantity;
}
