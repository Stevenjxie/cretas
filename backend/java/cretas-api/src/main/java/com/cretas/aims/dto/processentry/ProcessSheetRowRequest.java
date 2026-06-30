package com.cretas.aims.dto.processentry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * SP-F 逐工序电子表格 — 单行增量录入请求 (spec §4.2)。
 *
 * <p>一行 = 一个批次的一道工序。上游引用走真实持久化的 batchNumber (跨请求),
 * 不同于 SP-B 抽屉的内存 clientBatchKey 互指。
 *
 * <p>SP-G G3a: 补入 byproducts / sampleRetainQuantity / packagingDetail (原 defer 已解锁)。
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

    /** 该工序实际操作日期 (跨天: 焯水/熟制各记各日)。null → 报工日期回退当天。前端「流程日期」列。 */
    private LocalDate processDate;

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

    // SP-G G3a: 产出附加 (mirror ProcessChainEntryRequest.StepEntry)
    /** 副产物明细 [{name,quantity,unit,unitPrice}]。 */
    private List<ProcessChainEntryRequest.Byproduct> byproducts;
    /** 留样件数 (末道装盒后留样)。 */
    private Integer sampleRetainQuantity;
    /** 包装明细 [{name,cost}] (膜/气体/标签/其他)。 */
    private List<Map<String, Object>> packagingDetail;

    /** 成品重(kg) — 气调/末道录入, 用于按重量算真实出成率 (frontend fields['productWeight']) */
    private java.math.BigDecimal productWeight;

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
