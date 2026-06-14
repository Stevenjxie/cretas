package com.cretas.aims.service.bom;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * 六扇门 D1: 同口径「标准成本」解析服务.
 *
 * <p><b>背景 (假阳性超支报警根因)</b>: BOM 配方按客户口径只含 料/辅料/包材, 不含人工
 * ({@code BomRecipe.totalCost} 实为纯料, 因 {@code totalLaborCost}/{@code totalOverheadCost}
 * 全库 0 调用)。而"实际成本" ({@code SalesOrderItem.costUnitPrice} /
 * {@code ProductionCostUpdatedEvent.actualUnitCost}) 来自报工 rollup = 料 + 实际人工 + 实际制费。
 * 用 <b>纯料标准</b> 去比 <b>含人工实际</b> → 每个产品方差系统性虚高 → 误报超支。
 *
 * <p><b>客户原话 (转录 [36:48-37:09])</b>: "我一定要当时报价的一盒对应的实际 SKU 的实际人工,
 * 报价两个人工和实际天差大小" → 标准侧必须含人工才能跟含人工的实际同口径比。
 *
 * <p><b>口径定义</b> (转录 [14:46-16:23] 一致):
 * <ul>
 *   <li>料成本 = {@code BomRecipe.totalMaterialCost} (BOM 配方料 only, 客户: "原料成本定死")</li>
 *   <li>标准人工 = 研发线下评估手填的预估人工 {@code QuotationTask.laborPerKg} (元/kg成品),
 *       按 {@code BomRecipe.outputQuantityPerUnit} 折算到每单位成品</li>
 *   <li>标准制费 = 暂无规范的"标准制费"数据源 → 诚实 null (不默认 0)</li>
 * </ul>
 *
 * <p><b>⛔ 不改 BOM 配方本身</b>: 此服务只在 <b>对比/变体组装层</b> 补全标准人工, 不把人工写回
 * {@code BomRecipe} (转录 [107:29-33] "BOM 不需要人工的价格")。
 *
 * <p><b>诚实 null (红线)</b>: 某产品无研发预估人工 → 标准侧 {@code laborUnitCost} = null,
 * 整体 {@code totalUnitCost} 标缺 (不默认 0, 不静默只用料 — 那正是被修复的 bug)。
 * 超支报警在标准侧不完整时不误报 (caller 跳过)。
 */
public interface StandardCostService {

    /**
     * 解析产品的同口径标准单位成本 (料 + 标准人工 + 标准制费).
     *
     * <p>永不抛异常 (各数据源缺失 → 对应字段诚实 null)。返回对象本身永不 null。
     *
     * @param factoryId     工厂 ID
     * @param productTypeId 产品类型 ID
     * @return 标准单位成本拆分 (各组分诚实 null), 永不返回 null 对象本身
     */
    StandardUnitCost resolveStandardUnitCost(String factoryId, String productTypeId);

    /**
     * 同口径标准单位成本拆分值对象 (不可变).
     *
     * <p>各组分独立诚实 null:
     * <ul>
     *   <li>{@link #materialUnitCost} — BOM 料标准单位成本 (无 ACTIVE BOM / 含未定价料 → null)</li>
     *   <li>{@link #laborUnitCost} — 标准人工单位成本 (无研发预估人工 → null)</li>
     *   <li>{@link #overheadUnitCost} — 标准制费单位成本 (当前无规范数据源 → 恒 null)</li>
     *   <li>{@link #totalUnitCost} — 同口径标准总成本 = 料 + 人工 (+ 制费). 料或人工任一 null → null</li>
     *   <li>{@link #laborIncluded} — totalUnitCost 是否含人工 (true=同口径可比; false=口径不全, caller 应跳过报警)</li>
     *   <li>{@link #caliberHint} — 口径说明文案 (给财审看懂"标准成本含/不含人工")</li>
     * </ul>
     */
    @Value
    @Builder
    class StandardUnitCost {

        /** BOM 料标准单位成本 (= BomRecipe.totalMaterialCost). 无 BOM / 含未定价料 → null. */
        BigDecimal materialUnitCost;

        /** 标准人工单位成本 (研发预估 laborPerKg × 单位成品 kg). 无研发预估人工 → null. */
        BigDecimal laborUnitCost;

        /** 标准制费单位成本. 当前无规范"标准制费"数据源 → 恒 null (诚实留空). */
        BigDecimal overheadUnitCost;

        /**
         * 同口径标准总成本 = 料 + 标准人工 (+ 标准制费, 若有).
         *
         * <p>诚实 null: 料 null 或 标准人工 null → totalUnitCost = null (口径不全, 不与含人工实际比)。
         */
        BigDecimal totalUnitCost;

        /** totalUnitCost 是否含人工 (true=同口径可比报警; false=口径不全, caller 跳过报警). */
        boolean laborIncluded;

        /** 口径说明 (给财审/前端展示, 让用户看懂标准成本含/不含人工 + 缺什么). */
        String caliberHint;
    }
}
