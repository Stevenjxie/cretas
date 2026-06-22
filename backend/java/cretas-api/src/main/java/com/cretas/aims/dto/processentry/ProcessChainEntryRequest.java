package com.cretas.aims.dto.processentry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/** 文员逐道录入负载: 一条生产链(多个半成品批 + 1 成品批)。Spec §4. */
@Data
public class ProcessChainEntryRequest {

    @NotBlank
    private String idempotencyKey;

    /** 链中各批次(顺序: 上游半成品批在前, 成品批在后)。 */
    @NotNull
    private List<BatchEntry> batches;

    @Data
    public static class BatchEntry {
        /** 客户端分配的链内引用键(混锅来源用它指上游), 如 "焯水0613"。 */
        @NotBlank
        private String clientBatchKey;
        @NotBlank
        private String productTypeId;
        /** 可空: 系统生成 batchNumber。 */
        private String batchNumber;
        /** true=成品批(熟制→气调→包装); false=半成品批(原料→焯水)。 */
        private boolean finished;
        @NotNull
        private List<StepEntry> steps;
    }

    @Data
    public static class StepEntry {
        @NotNull
        private Integer processOrder;
        private String processName;
        /** 成本桶: RAW_MATERIAL | SEASONING | PACKAGING | null(普通工序) */
        private String processCategory;
        private BigDecimal inputQuantity;
        private BigDecimal outputQuantity;
        private String unit;                 // 默认 "kg"
        // 人工(起止+人数 → 工时)
        private String laborStartTime;       // "HH:mm"
        private String laborEndTime;
        private Integer workerCount;
        // 产出附加
        private List<Byproduct> byproducts;
        private BigDecimal wasteQuantity;
        private Integer sampleRetainQuantity;
        // 领料(首道): 消耗的原料 MaterialBatch
        private List<RawInput> rawMaterialInputs;
        // 熟制(混锅 + 调料)
        private Integer potCount;            // 锅数 N
        private List<BigDecimal> potRawKgs;  // 逐锅原料(N>1 必填)
        private List<UpstreamSource> upstreamSources; // 混锅来源
    }

    @Data
    public static class Byproduct {
        private String name;
        private BigDecimal quantity;
        private String unit;
        private BigDecimal unitPrice;        // 可空(无单价→不冲减)
    }

    @Data
    public static class RawInput {
        @NotBlank
        private String materialBatchId;      // 原料 MaterialBatch.id
        @NotNull
        private BigDecimal quantity;
    }

    @Data
    public static class UpstreamSource {
        /** 指向同负载里另一个 BatchEntry.clientBatchKey。 */
        @NotBlank
        private String sourceClientBatchKey;
        @NotNull
        private BigDecimal feedQuantityKg;
    }
}
