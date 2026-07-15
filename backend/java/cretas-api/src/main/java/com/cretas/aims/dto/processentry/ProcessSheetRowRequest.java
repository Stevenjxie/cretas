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

    /** 兼容字段：等同 outputUnit。 */
    private String unit;
    /** 实际投入单位；空时向后兼容为 unit/kg。 */
    private String inputUnit;
    /** 实际产出单位；空时向后兼容为 unit/kg。 */
    private String outputUnit;

    /** 多时段工时, 后端 Σ。 */
    private List<LaborSegment> laborSegments;

    /** 领料 (修油首道): 消耗原料 MaterialBatch。 */
    private List<RawInput> rawMaterialInputs;

    /**
     * 新报工契约：操作员只填写每种原料的实际投料总量，不选择来源批次。
     * 正式提交时由后端从生产库按 FEFO 自动分摊为 {@link RawInput}；草稿阶段不分摊、不占用库存。
     * 为空时继续兼容旧客户端显式提交 {@code rawMaterialInputs} 的路径。
     */
    private List<MaterialInputTotal> materialInputTotals;

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

    /**
     * G2: 本工序自定义字段值 (如 {"baume":12.5,"remark":"..."}), key 集合受
     * {@link com.cretas.aims.entity.WorkProcess#getCustomFieldSchema()} 约束 —— 未配置 schema
     * (customFieldSchema=null) 的工序不接受任何 key (保存时校验层 400 拒绝, 见
     * ProcessSheetServiceImpl#validateCustomFields)。随 row_payload 持久化, 跨保存往返;
     * 物化时并入 ProductionReport.customFields (见 ClerkProcessEntryServiceImpl#processEntryCustomFields)。
     */
    private Map<String, Object> customFields;

    /**
     * 2B.2 多产出 (fan-out): 一道工序一次报工同时产出多个产品 (如熟成鸡装箱同产 350g/400g,
     * 筛选同产 合格半成品+不合格损耗)。多产出 = 两组独立事实: input allocations (本请求的
     * rawMaterialInputs/upstreamSources) + output lines (本字段)。库存按实际 input allocations 扣减
     * (一次全量, <b>不</b>按重量/数量拆分/推断), 按每条 output line 入库; 血缘经同一份工序报工关联
     * 每个产出批次到全部实际投入批次。工序不处理成本/成本分摊。
     *
     * <p>为空 / size==1 → 走原单产出路径 (向后兼容, F006 现有流不受影响)。多产出时顶层
     * {@link #outputQuantity} 仅为满足 @NotNull (建议传 Σquantity), 实际逐 output 用各自 quantity。
     */
    private List<OutputLine> outputs;

    /**
     * 2B.2 内部标记 (合成产出行专用, FE 不传): true 表示本行是多产出分解出的产出行。
     * 用途: 删除/重存按 {@link #multiOutputBaseRowId} 整组级联 (防单行删除留幻库存)。
     * <b>不</b>用于成本处理 —— 工序不新增/不清空/不覆盖成本字段 (成本由既有机制自行处理)。
     */
    private Boolean multiOutputMember;

    /** 2B.2 内部: 多产出组的 base clientRowId (= FE 原始 clientRowId, 各产出行 clientRowId = base#i)。 */
    private String multiOutputBaseRowId;

    /** 2B.2 本行产出对应的 workflow 产出端口 id (端口身份; 单产出/legacy 可空)。随 payload 持久化供 FE 重载映射。 */
    private String workflowPortId;
    /** 2B.2 本行产出对应的 workflow 产出物料 Cell (节点) id。 */
    private String materialNodeId;

    /** 原料领料行: 消耗的原料 MaterialBatch + 投料量。 */
    @Data
    public static class RawInput {
        @NotBlank
        private String materialBatchId;
        @NotNull
        private BigDecimal quantity;
        // 2B.2 端口身份 (多产出/多投入必带, 区分同 SKU 出现在不同端口)。可空 (legacy 单产出/非 workflow)。
        /** 对应 workflow 投入端口 id。 */
        private String workflowPortId;
        /** 对应 workflow 物料 Cell (节点) id。 */
        private String materialNodeId;
        /** 该端口物料 SKU (原料 RawMaterialType id)。 */
        private String skuId;
    }

    /** 操作员录入的原料投料总量；数量单位当前只接受 kg。 */
    @Data
    public static class MaterialInputTotal {
        @NotBlank
        private String materialTypeId;
        @NotNull
        private BigDecimal quantity;
        private String unit;
        private String workflowPortId;
        private String materialNodeId;
    }

    /**
     * 2B.2 多产出行: 一个产出 = 一个产品 + 数量 (+ 分摊权重)。分解后每个 OutputLine 合成一条普通单产出行。
     */
    @Data
    public static class OutputLine {
        /** 产出产品 (半成品/成品 ProductType)。 */
        @NotBlank
        private String productTypeId;
        /** 对应 workflow 产出端口 id (B3 逐产出对齐端口类型/单位)。可空 (非 workflow 计划)。 */
        private String workflowPortId;
        /** 产出数量 (产出单位, 如 盒/kg) → 直接作为该产出的入库量。 */
        @NotNull
        private BigDecimal quantity;
        /** 产出单位。 */
        private String unit;
        /** 产出是否成品 (true → FG, false → 半成品 SFI)。 */
        private boolean finished;
        /** 对应 workflow 产出物料 Cell (节点) id (端口身份的另一半, 区分同 SKU 多端口)。 */
        private String materialNodeId;
        /** 可选成品重(kg): 若设则 FG 按 kg 入库 (同顶层 productWeight 语义); 多数多产出场景留空按 quantity 入库。 */
        private BigDecimal productWeight;
        /** Retained samples for this output SKU; inventory equals quantity minus this value. */
        private Integer sampleRetainQuantity;
    }

    /** 混锅上游引用: 上游 WIP 的持久化 batchNumber + 投料量 (kg)。 */
    @Data
    public static class UpstreamRef {
        @NotBlank
        private String sourceBatchNumber;
        @NotNull
        private BigDecimal feedQuantityKg;

        /**
         * 半成品库存(SFI)投料 (半成品直接产成品)。
         *
         * <p>true 时 {@code sourceBatchNumber} 指向常驻半成品库存
         * ({@link com.cretas.aims.entity.SemiFinishedInventory#getIntermediateBatchNo()}),
         * 而非本计划内的在制 WIP MaterialBatch。语义:
         * <ul>
         *   <li><b>保存</b>: 不解析为 in-plan WIP MaterialBatch, 不写 MaterialConsumption
         *       (material_consumptions.batch_id NOT NULL 只能持 MaterialBatch id, SFI 无对应行)。
         *       仍随 row_payload 持久化, 跨保存往返。</li>
         *   <li><b>小结</b>: 经 {@code WipInventoryService.consumeClerkSemi(intermediateBatchNo, feedQuantityKg)}
         *       从常驻 SFI 余额扣减 (不依赖本计划前序小结的 priorStocked)。</li>
         * </ul>
         * 默认 false (普通 in-plan 在制 WIP 引用, 行为不变)。
         */
        private boolean semiFinished;

        /**
         * 成品库存(FG)投料 (成品作投料来源)。
         *
         * <p>true 时 {@code sourceBatchNumber} 指向常驻成品库存
         * ({@link com.cretas.aims.entity.inventory.FinishedGoodsBatch#getBatchNumber()}),
         * 而非本计划内的在制 WIP MaterialBatch, 也不是半成品库存 (SFI)。语义 (与 {@link #semiFinished} 平行):
         * <ul>
         *   <li><b>保存</b>: 不解析为 in-plan WIP MaterialBatch, 不写 MaterialConsumption
         *       (与 SFI 同理, FG 无对应 MaterialBatch 行)。仅随 row_payload 持久化, 跨保存往返。
         *       保存期 loud-fail 校验该 FG 批次真实存在 (禁止降级)。</li>
         *   <li><b>小结</b>: 经 {@code FinishedGoodsFeedService.consumeForFeedStrict(batchNumber, feedQuantityKg)}
         *       从常驻成品库存严格扣减 (缺失/不足即抛 FG_NOT_FOUND/FG_INSUFFICIENT, 禁止降级)。
         *       FG 投料成本 = feedKg × FG.unitCost 流入本道产出成本 (诚实 null: FG.unitCost 未知 → 本道成本 null)。</li>
         * </ul>
         * ⛔ 不与 {@link #semiFinished} 同时为 true (互斥: 一条来源要么 in-plan WIP / 要么 SFI / 要么 FG)。
         * 默认 false。
         */
        private boolean finishedGoods;

        // 2B.2 端口身份 (多投入合流必带, 区分同 SKU 出现在不同投入端口)。可空 (legacy 单产出/非 workflow)。
        /** 对应 workflow 投入端口 id。 */
        private String workflowPortId;
        /** 对应 workflow 物料 Cell (节点) id。 */
        private String materialNodeId;
        /** 该端口物料 SKU (半成品/成品 ProductType id)。 */
        private String skuId;
    }
}
