package com.cretas.aims.dto.processentry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ①c 成品作投料来源 — 逐道录入 FG 投料下拉的可投料成品批次项。
 *
 * <p>07-01 客户会议: 「在选批次时看到库里所有<b>成品和半成品</b>」。逐道 feed picker 此前只提供半成品(SFI),
 * 本 DTO 补齐成品(FG) 作可选投料来源。镜像 {@link com.cretas.aims.dto.yield.WipRowDTO} (SFI) 的字段口径,
 * 但取自 {@link com.cretas.aims.entity.inventory.FinishedGoodsBatch}。
 *
 * <p>② 批次下拉补字段: 除批号外, 携带 <b>品名 (productTypeName) + 生产日期 (productionDate) + 成本 (unitCost)</b>,
 * 供前端下拉标签 {@code 品名 | 批号 | 生产日期 | 余{qty}{unit} | 成本{unitCost}} 展示。
 * unitCost 诚实 null (未接通成本的成品 → null, 前端显示"成本未知", 不伪造 ¥0)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinishedGoodsStockItem {

    /** 成品批次号 (投料时作 sourceBatchNumber 传回, finishedGoods=true)。 */
    private String batchNumber;

    /** 产品类型 id (前端产品族分组/溯源)。 */
    private String productTypeId;

    /** 品名 (成品名称, 冗余回填)。 */
    private String productTypeName;

    /** 生产日期。 */
    private LocalDate productionDate;

    /** 可用量 = producedQuantity − shippedQuantity − reservedQuantity (> 0)。 */
    private BigDecimal availableQuantity;

    /** 库存单位 (盒/kg 等)。 */
    private String unit;

    /**
     * 单位成本 (FinishedGoodsBatch.unitCost) — 成本传导基准。
     * 🔴 诚实 null: 未接通成本的成品 → null (不伪造 ¥0), 区别于售价 unitPrice。
     */
    private BigDecimal unitCost;

    /**
     * 每盒/份标准克重 (取自 {@link com.cretas.aims.entity.ProductType#getGramsPerUnit()}, "1 份/盒 = X 克")。
     * 计数单位 (盒/个/件/只) 成品作 kg 道投料来源时, 前端据此把 kg⇄盒 折算 (余 N 盒 ≈ M kg)。
     * 🔴 诚实 null: 未配置每盒克重 → null (前端据此拦截盒装投料, 禁止臆造 1盒=1kg)。
     */
    private BigDecimal gramsPerUnit;
}
