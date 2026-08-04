package com.cretas.aims.dto.processentry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
// LaborSegment is in the same package (com.cretas.aims.dto.processentry) — no import needed.

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
        /** 该工序实际操作日期 (跨天生产: 焯水周一/熟制周三各记各日)。null → 报工日期回退当天。 */
        private LocalDate processDate;
        /** 成本桶: RAW_MATERIAL | SEASONING | PACKAGING | null(普通工序) */
        private String processCategory;
        private BigDecimal inputQuantity;
        private BigDecimal outputQuantity;
        /** Finished-goods net product weight in kg; used for yield when outputQuantity is a count unit. */
        private BigDecimal productWeight;
        /** 兼容字段：等同 outputUnit。 */
        private String unit;
        private String inputUnit;
        private String outputUnit;
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
        /**
         * SP-F: 多时段工时 (per-row caller 用)。非空且非空列表时,
         * materializeBatch 用 computeLaborCost(List, rate) 求和; 否则回退单段
         * (laborStartTime/laborEndTime/workerCount) 路径。recordChain 永不设此字段 (null),
         * 故 recordChain labor 行为不变。
         */
        private List<LaborSegment> laborSegments;
        /** SP-G G3a: 包装明细 [{name,cost}] (膜/气体/标签/其他); 随 YIELD 报工写入 ProductionReport。 */
        private List<java.util.Map<String, Object>> packagingDetail;
        /**
         * G2: 本工序自定义字段值 (config-driven, WorkProcess.customFieldSchema 约束), mirror
         * ProcessSheetRowRequest.customFields. 随 YIELD 报工命名空间并入 ProductionReport.customFields
         * (见 ClerkProcessEntryServiceImpl#processEntryCustomFields), 不覆盖既有内部记账 key。
         */
        private java.util.Map<String, Object> customFields;
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
        /**
         * 投料量 —— <b>单位是该批次自己的库存单位</b>({@code material_batches.quantity_unit}),
         * <b>不是 kg</b>。
         *
         * <p>⛔ 本 DTO <b>刻意没有 unit 字段</b>: 逐道录入按批次记账, 数量照着选中批次的库存单位填,
         * 服务端不做任何换算 —— 见 {@code ClerkProcessEntryServiceImpl} 建 RAW 边处。
         * 所以传 5 给一个按 {@code g} 存的批次就是扣 5 g, 不是 5 kg。
         *
         * <p>⚠️ 与姊妹路径口径<b>不同, 这不是遗漏</b>:
         * {@code ProcessSheetServiceImpl#resolveEdges} 收的是<b>报工单位</b>, 带 unit 上来,
         * 由 {@code convertReportingQuantityToStorage} 折成库存单位, 折不了的组合直接
         * {@code PROCESS_SHEET_SOURCE_UNIT_MISMATCH} 拦住(已由
         * {@code ProcessSheetWorkflowUnitNormalizationTest} 钉住)。两条路径<b>契约不同</b>:
         * 那条声明单位, 这条按库存单位。
         *
         * <p>🔴 已知边界(2026-08-04 查证): 因为没有 unit 输入, 服务端<b>检测不出</b>调用方
         * 按 kg 传给一个 {@code g} 批次这种<b>欠扣</b>(会留下幻库存)。反向的<b>过扣</b>仍由
         * 小结时的 {@code BATCH_INSUFFICIENT} 兜住({@code InterimSettleServiceImpl})。
         * prod 实测 0 例: {@code g} 批次上共 4 条消耗, 入库/已用/消耗三者全一致;
         * 全库「消耗量与入库量尺度异常」扫描 0 行。
         */
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
