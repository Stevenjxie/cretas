package com.cretas.aims.dto.yield;

import com.cretas.aims.security.PriceSensitive;
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
    @PriceSensitive
    private BigDecimal laborCost;
    /** Σ 本道材料成本(元); 全 null → null (绝不默认 0) */
    @PriceSensitive
    private BigDecimal materialCost;
    /** 本道总成本 = laborCost + materialCost (null-safe; 两者全 null → null) */
    @PriceSensitive
    private BigDecimal stepCost;

    // ── 适配单元3: 传统报工证据/工时段/副产物/损耗/留样 (本道各次报工合并) ──────────────
    /** 证据图片 URL (本道各次报工 photos 合并去重; 无则 null) */
    private List<String> photos;
    /** 多时段×人数工时明细 (本道各次报工 laborSegments 拼接; 无则 null) */
    private List<Map<String, Object>> laborSegments;
    private BigDecimal processedQuantity;
    private String processedUnit;
    private BigDecimal stageOutputQuantity;
    private String stageOutputUnit;
    private BigDecimal segmentWasteQuantity;
    private String segmentWasteUnit;
    /** 副产物明细 (本道各次报工 byproducts 拼接; 无则 null) */
    private List<Map<String, Object>> byproducts;
    /** Σ 本道损耗量 (null-safe; 全 null → null, 绝不默认 0) */
    private BigDecimal wasteQuantity;
    /** Σ 本道留样数 (null-safe; 全 null → null; 通常仅末道有) */
    private Integer sampleRetainQuantity;

    // ── 三阶段报工 (单元1): 阶段推断 + 照片按 reportKind 分组 ──────────────
    /** 本道阶段: AWAITING_INPUT (无投入) / IN_PRODUCTION (有投入无产出) / COMPLETED (有产出)。
     *  旧式报工 (reportKind null) 按 input/output 有无推断, 与三阶段语义一致。 */
    private String phase;
    /** 投入阶段照片 (来自 reportKind ∈ {INPUT, SEGMENT} 的报工; 旧式 null reportKind 也归此组); 去重保序; 无则 null */
    private List<String> inputPhotos;
    /** 产出阶段照片 (来自 reportKind == OUTPUT 的报工); 去重保序; 无则 null */
    private List<String> outputPhotos;
    /** T161 per-photo annotation parallel to inputPhotos; each element: {url, label, note}; null = no annotations */
    private List<Map<String, Object>> inputPhotoAnnotations;
    /** T161 per-photo annotation parallel to outputPhotos; each element: {url, label, note}; null = no annotations */
    private List<Map<String, Object>> outputPhotoAnnotations;

    // ── SP1 双产出: outputKind/semiOutputQuantity/semiCode (本道各次报工聚合) ──────────────
    /** 产出类型: FINISHED / SEMI / BOTH / null (旧式, 视同 FINISHED).
     *  多次报工时取首个非 null 值 (同批同道 outputKind 应一致). */
    private String outputKind;
    /** Σ 本道半成品产出量 (outputKind=SEMI/BOTH 时有值); null=本道无半成品产出 */
    private BigDecimal semiOutputQuantity;
    /** 半成品产出单位; semiOutputQuantity 为 null 时此字段也为 null */
    private String semiOutputUnit;
    /** 半成品编号/批次号 (对应 SemiFinishedInventory.intermediateBatchNo); null=未指定 */
    private String semiCode;
}
