package com.cretas.aims.dto.yield;

import com.cretas.aims.security.PriceSensitive;
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

    // ── 单元 A.4/A.5: 整批成本聚合 (Σ steps, null-safe; 全 null → null, 绝不默认 0) ──────
    /** Σ 所有道人工成本(元); 全 null → null */
    @PriceSensitive
    private BigDecimal totalLaborCost;
    /** Σ 所有道材料成本(元); 全 null → null */
    @PriceSensitive
    private BigDecimal totalMaterialCost;
    /** 整批总成本 = totalLaborCost + totalMaterialCost (null-safe; 两者全 null → null) */
    @PriceSensitive
    private BigDecimal totalCost;

    // ── 适配单元3: 整批副产物/损耗/留样聚合 (Σ steps, null-safe) ──────────────────────
    /** Σ 所有道留样数 (null-safe; 全 null → null) */
    private Integer totalSampleRetain;
    /** Σ 所有道损耗量 (null-safe; 全 null → null, 绝不默认 0) */
    private BigDecimal totalWaste;

    // ── G8 Wave 3: C 进行中标注 (展示层防呆, 拍板 A 主算法 + C 标注) ──────────────────
    /**
     * 是否生产进行中 (批次未完工 或 仍有在制半成品 WIP 余额)。
     * <p>true → 看板应在 {@code cumulativeYieldRate} 旁标 "进行中, 含 X kg 在制半成品未计入成品";
     * false → 批次已完工, 出成率数字已锁定为最终值 (per 设计章一 ★推荐: A 数 + 一句话 + WIP 在制量)。</p>
     * <p><b>不引入第三种算法</b>: {@code cumulativeYieldRate} 始终是 A 完工口径; 此字段只控制展示标注。</p>
     */
    private Boolean inProgress;
    /** C 标注: 该批次在制半成品总量 = Σ AVAILABLE WIP available_quantity (未变成成品的中间品)。inProgress=false 时为 0/null。 */
    private BigDecimal wipInProgressQuantity;
    /** C 标注: 在制半成品量的单位 (取 WIP 行 unit, 多单位时取首个非空; 无在制则 null)。 */
    private String wipInProgressUnit;
    /**
     * 选填进行中参考数 (候选 B 口径 = 截至当前末道已产 / 首道已投)。
     * <p>与 A 口径 {@code cumulativeYieldRate} 区别: as-of 在生产途中持续偏低且波动 (在制 WIP 没变成成品),
     * 仅作"进行中"展示参考, <b>不可</b>当最终出成率。完工后 ≈ A 口径。</p>
     * <p>当前实现下 as-of 与 A 口径同源 (末道总产出/首道总投入), 故 inProgress 时两者相等;
     * 区别仅在语义标注 — 保留字段供未来按"已结清快照"细化时填差异值。</p>
     */
    private BigDecimal asOfYieldRate;
}
