package com.cretas.aims.dto.processentry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * SP-F 逐工序电子表格 — 单行增量录入请求 (spec §4.2)。
 *
 * <p>一行 = 一个批次的一道工序。上游引用走真实持久化的 batchNumber (跨请求),
 * 不同于 SP-B 抽屉的内存 clientBatchKey 互指。
 *
 * <p>切片不带 byproducts / sampleRetainQuantity (defer 随 Q6/气调, spec §2.3)。
 */
@Data
public class ProcessSheetRowRequest {

    /** 客户端稳定行 id (upsert 键)。 */
    @NotBlank
    private String clientRowId;

    /** 工序代码: "xiuyou" | "chaoshui" | "shuzhi" | ... */
    @NotBlank
    private String processCode;

    @NotNull
    private Integer processOrder;

    private String processName;

    @NotBlank
    private String productTypeId;

    /** 可空: 首存系统生成 (CLK-W-/CLK-B-)。 */
    private String batchNumber;

    /** 切片内均 false (未到气调成品批)。 */
    private boolean finished;

    private BigDecimal inputQuantity;

    /** 仅 >0 才物化 WIP 批 (见 spec §4.4); <=0 → DRAFT 行不物化。 */
    @NotNull
    private BigDecimal outputQuantity;

    /** 默认 "kg"。 */
    private String unit;

    /** 多时段工时, 后端 Σ。 */
    private List<LaborSegment> laborSegments;

    /** 领料 (修油首道): 消耗原料 MaterialBatch。 */
    private List<RawInput> rawMaterialInputs;

    /** 混锅: 按真实持久化 batchNumber 引用上游 WIP。 */
    private List<UpstreamRef> upstreamSources;

    /** 锅数 N。 */
    private Integer potCount;

    /** 逐锅原料 (N>1 必填, spec §9)。 */
    private List<BigDecimal> potRawKgs;

    /** 触发 RecipeCostCalculator (熟制调料)。 */
    private boolean seasoningStep;

    /** 可选防双击 (同 clientRowId 一次保存内)。 */
    private String idempotencyKey;

    /** 原料领料行: 消耗的原料 MaterialBatch + 投料量。 */
    @Data
    public static class RawInput {
        @NotBlank
        private String materialBatchId;
        @NotNull
        private BigDecimal quantity;
    }

    /** 混锅上游引用: 上游 WIP 的持久化 batchNumber + 投料量 (kg)。 */
    @Data
    public static class UpstreamRef {
        @NotBlank
        private String sourceBatchNumber;
        @NotNull
        private BigDecimal feedQuantityKg;
    }
}
