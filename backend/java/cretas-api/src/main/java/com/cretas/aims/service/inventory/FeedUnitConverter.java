package com.cretas.aims.service.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 盒⇄kg 投料折算 — 逐道录入把「计数单位(盒/个/件/只)」的成品(FG)/半成品(SFI)库存来源, 折算为 kg 口径的
 * 投料/扣减/成本核算。折算依据 {@link com.cretas.aims.entity.ProductType#getGramsPerUnit()}
 * (每盒/份标准克重, "1 份/盒 = X 克"; 与 报工末道 份/盒→kg + 成品入库换算 同源)。
 *
 * <p>客户 (张权) 澄清: 盒↔kg 折算是<b>确定性</b>的 (每盒克重 BOM/SKU 固定), 故盒装成品可作 kg 道投料来源。
 *
 * <p>🔴 <b>诚实 null (禁止降级)</b>: 计数单位来源<b>缺</b> gramsPerUnit → 无法折算 → 返 {@code null}
 * (调用方 loud-fail / 成本判未知), <b>绝不臆造 1盒=1kg 或猜克重</b>。
 *
 * <p>换算口径与 {@link com.cretas.aims.service.restock.RestockUnitConverter#kgToBox} 一致
 * (盒 = kg × 1000 / gramsPerUnit, scale=2 HALF_UP), 但此工具额外承担「计数单位判定 + kg 直投透传」。
 */
public final class FeedUnitConverter {

    private FeedUnitConverter() {}

    /** 折算精度: 盒数保留 2 位小数 (kg-精确; 与 RestockUnitConverter 一致, 允许非整盒 = kg 准确, 见 PR 语义标注)。 */
    private static final int BOX_SCALE = 2;

    /**
     * 计数单位判定 — 与前端 {@code ProcessDataTable.isCountUnit} 同口径 (盒/个/件/只)。
     * 计数单位库存 (气调成品常按盒计) 与本道 kg 投料口径不同, 需经 gramsPerUnit 折算。
     */
    public static boolean isCountUnit(String unit) {
        if (unit == null) {
            return false;
        }
        return unit.contains("盒") || unit.contains("袋") || unit.contains("包")
                || unit.contains("个") || unit.contains("件") || unit.contains("只")
                || unit.contains("份") || unit.contains("瓶") || unit.contains("罐");
    }

    /**
     * 把 kg 投料量折算为来源库存的<b>原生单位</b>数量 (供扣减 + 成本核算, 两者用同一折算 → 口径一致)。
     *
     * <ul>
     *   <li><b>计数单位来源</b> (盒/个/件/只): 盒数 = {@code feedKg × 1000 / gramsPerUnit} (scale=2, HALF_UP);
     *       gramsPerUnit 缺失/非正 → 返 {@code null} (诚实 null, 禁止臆造)。</li>
     *   <li><b>非计数单位</b> (kg/重量单位): 原样返回 {@code feedKg} (kg 直投, 行为不变)。</li>
     * </ul>
     *
     * @param feedKg        本道投料量 (kg)
     * @param sourceUnit    来源库存原生单位 (FG/SFI 的 unit 字段)
     * @param gramsPerUnit  来源产品的每盒标准克重 (ProductType.gramsPerUnit; 计数单位来源必需)
     * @return 折算后的原生单位数量 (kg 源 = feedKg 原值; 盒源 = 盒数); 计数单位缺克重 → {@code null}
     */
    public static BigDecimal feedKgToSourceUnit(BigDecimal feedKg, String sourceUnit, BigDecimal gramsPerUnit) {
        if (feedKg == null) {
            return null;
        }
        if (!isCountUnit(sourceUnit)) {
            return feedKg; // kg / 重量单位: 直投, 不折算 (行为不变)
        }
        if (gramsPerUnit == null || gramsPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            return null; // 🔴 诚实 null: 计数单位来源缺每盒克重 → 无法折算 (禁止降级)
        }
        return feedKg.multiply(BigDecimal.valueOf(1000)).divide(gramsPerUnit, BOX_SCALE, RoundingMode.HALF_UP);
    }
}
