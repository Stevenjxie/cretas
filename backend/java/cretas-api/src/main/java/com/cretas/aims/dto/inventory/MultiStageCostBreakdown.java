package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 六扇门多段生产成本逐段分配 DTO (六扇门两点报工成本拆分核心)。
 *
 * <p><b>客户场景 (六扇门)</b>: 六扇门做不了逐道工序报工, 只做"两点报工"
 * (领料入 + 产出出), 没有逐道工序数据。成本按"段"分摊: 原料→半成品1→半成品2→成品,
 * 每个半成品过程 (每段) 消耗多少钱 (料 + 人工 + 制费), 逐段累积到成品。
 *
 * <p><b>"段"定义</b>: 一个段 = 一次两点报工的转化, 对应一行
 * {@link com.cretas.aims.entity.SemiFinishedInventory} (一个 intermediateBatchNo/semiCode):
 * <ul>
 *   <li>原料 → 半成品1 一段 (消耗原料 + 该段人工 + 该段制费, 产出半成品1)</li>
 *   <li>半成品1 → 半成品2 一段 (领用半成品1 + 该段人工 + 制费, 产出半成品2)</li>
 *   <li>半成品2 → 成品 一段 (领用半成品2 + 该段人工 + 制费, 产出成品)</li>
 * </ul>
 *
 * <p><b>每段消耗算法</b> (镜像 #713 WIP 移动均价):
 * <pre>
 *   段消耗 = 材料领料金额 (逐料 × 移动均价)
 *          + 人工 (该段两点报工 laborCost; 两点未结时诚实 null)
 *          + 制费 (该段 equipmentCost/otherCost; 无数据时诚实 null)
 *   产出半成品 unitCost = SemiFinishedInventory.unitCost (#713 已逐段滚动累积的移动均价)
 *   该段累积投入成本 = 上游领用半成品累积成本 (上段 unitCost × 领用量) + 本段新增消耗
 * </pre>
 *
 * <p><b>两点报工人工诚实 null</b> (客户铁律): 两点报工 (领料入 + 产出出) 时, 人工往往
 * "登下一期"按期间统一分摊, 报工当下 laborCost 为 null。此时该段 {@link StageCost#laborCost}
 * 诚实显示 null + {@link StageCost#laborHint} 说明"人工登下一期"。绝不伪造 0 或估算值。
 *
 * <p><b>每盒口径</b>: 每段消耗 ÷ 最终成品盒数 = 该段折算到每盒成品的贡献
 * ({@link StageCost#contributionPerBox})。最终成品盒数未知 (未完工/无成品产出) 时为 null。
 *
 * <p>所有金额字段标 {@code @PriceSensitive} — 无 finance:read / sales:read_write 权限的角色
 * 由 ResponseAdvice strip 到 null。UI 须 null 守卫。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiStageCostBreakdown {

    /** 销售订单 ID (回溯起点)。 */
    private String orderId;

    /** 产品名称 (成品)。 */
    private String productName;

    /**
     * 最终成品盒数 (每盒口径分母)。
     *
     * <p>来源: 末段 (最后一个 SemiFinishedInventory) 的 producedQuantity, 或订单行数量。
     * 未完工 / 无成品产出时 null → 各段 contributionPerBox 也为 null。
     */
    private BigDecimal finalOutputBoxes;

    /** 最终成品产出单位 (盒/kg/...)。 */
    private String finalOutputUnit;

    /**
     * 逐段成本明细 (按 processOrder 升序: 第一段=最上游原料转化, 末段=成品)。
     * 空列表 = 订单未投产 / 无半成品 WIP 记录。
     */
    private List<StageCost> stages;

    /**
     * 全链段消耗合计 (= Σ stages[].stageSubtotal, 非 null 段之和)。
     * 所有段都无成本时 null。
     */
    @PriceSensitive
    private BigDecimal totalChainCost;

    /**
     * 全链折每盒成本合计 (= Σ stages[].contributionPerBox, 非 null 段之和)。
     * finalOutputBoxes 未知时 null。
     */
    @PriceSensitive
    private BigDecimal totalCostPerBox;

    /** 关联到的半成品段数 (供财务判断数据完整度; 0 = 无两点报工 WIP 记录)。 */
    private Integer stageCount;

    /** 数据缺失场景的友好说明 (无投产 / 无 WIP 段 / 人工未结)。null = 数据完整。 */
    private String dataSourceHint;

    /**
     * 单段成本 (一次两点报工转化 = 一行 SemiFinishedInventory)。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageCost {

        /** 段序 (processOrder; 升序展示)。 */
        private Integer stageOrder;

        /** 半成品编码 (intermediateBatchNo / semiCode)。 */
        private String semiCode;

        /** 段名 (工序名 / 产品名回退)。 */
        private String stageName;

        /** 本段产出半成品名 (productTypeName 回填)。 */
        private String productName;

        /** 本段产出量 (producedQuantity)。 */
        private BigDecimal producedQuantity;

        /** 本段产出单位。 */
        private String producedUnit;

        /**
         * 本段领用的上游半成品量 (consumedFromUpstream); 首段 (原料起点) 无上游 → null。
         */
        private BigDecimal upstreamConsumedQuantity;

        /**
         * 本段领用的上游半成品累积成本 (= 上段 unitCost × 本段领用量)。
         * 首段无上游 → null 或 0。
         */
        @PriceSensitive
        private BigDecimal upstreamCarriedCost;

        /** 本段材料消耗合计 (= Σ materials[].amount; 逐料移动均价 × 用量)。无领料 → null。 */
        @PriceSensitive
        private BigDecimal materialCost;

        /**
         * 本段人工成本; 两点报工"登下一期"未结时诚实 null (不伪造)。
         */
        @PriceSensitive
        private BigDecimal laborCost;

        /** 人工为 null 时的说明 (如"两点报工人工登下一期, 按期间统一分摊")。null = 人工已结。 */
        private String laborHint;

        /** 本段制费 (设备 + 其他); 无数据 → null。 */
        @PriceSensitive
        private BigDecimal overheadCost;

        /**
         * 本段段小计 (= 本段新增消耗: materialCost + laborCost + overheadCost, 非 null 之和)。
         * <p>注: 不含 upstreamCarriedCost (那是上游已计的累积, 避免重复计数)。
         * 三者全 null → null。
         */
        @PriceSensitive
        private BigDecimal stageSubtotal;

        /**
         * 产出半成品移动均价 (= SemiFinishedInventory.unitCost; #713 逐段滚动累积)。
         * <p>= 累积投入成本 / 累积产出量 = (上游累积 + 本段新增) / 产出量。无成本 → null。
         */
        @PriceSensitive
        private BigDecimal outputUnitCost;

        /**
         * 本段累积成本 (= SemiFinishedInventory.accumulatedCost; 上游 + 本段全部投入)。
         * 无成本 → null。
         */
        @PriceSensitive
        private BigDecimal accumulatedCost;

        /**
         * 本段折每盒成品贡献 (= stageSubtotal / finalOutputBoxes)。
         * finalOutputBoxes 未知或 stageSubtotal 为 null → null。
         */
        @PriceSensitive
        private BigDecimal contributionPerBox;

        /** 逐料明细 (本段材料消耗)。无领料 → 空列表。 */
        private List<FinanceCostBreakdown.MaterialCostLine> materials;

        /** 本段数据缺失说明 (人工未结 / 无领料)。null = 完整。 */
        private String stageHint;
    }
}
