package com.cretas.aims.service.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成品(FG)批次跨单位换算 — 销售 FEFO 推荐 / 分配校验 / 发货扣减 三段共用。
 *
 * <p><b>背景 (2026-07 F006 现场确认, 🔴 C1)</b>: 同一 {@code productTypeId} 下的成品批次可能记录在
 * <b>不同单位</b> —— 小结时填了「本批实收重量」的批次 unit=kg, 未填的批次沿用计数单位(盒/个/件)。
 * 例: 同产品一批 0.45kg, 另一批 4454.5盒。销售 FEFO 推荐 / 分配校验 / 发货扣减 之前把不同批次的
 * {@code availableQuantity} 当作与发货行单位直接可比/可加总的裸数字处理 —— kg 与 盒 数量级完全不同,
 * 直接相加/相减/比较全部算错。
 *
 * <p>换算依据 {@link com.cretas.aims.entity.ProductType#getGramsPerUnit()} (每盒/份标准克重),
 * 与 {@link FeedUnitConverter}（逐道投料折算）/
 * {@link com.cretas.aims.service.restock.RestockUnitConverter}（备货看板）同一口径
 * (盒 = kg × 1000 / gramsPerUnit)。
 *
 * <p>🔴 <b>诚实 null (禁止降级)</b>: 单位不同且无法确定换算系数 (缺 gramsPerUnit, 或双方都是计数单位
 * 但字符串不同, 如 "盒" vs "个") → 返回 {@code null}。调用方必须据此 loud-fail / 跳过该批,
 * <b>绝不能把 null 当 0 或裸相加</b>。
 */
public final class FgQuantityUnitConverter {

    private FgQuantityUnitConverter() {}

    /** kg → 计数单位 换算精度 (盒/个/件 保留 2 位小数, 与 FeedUnitConverter/RestockUnitConverter 一致)。 */
    private static final int COUNT_SCALE = 2;
    /** 计数单位 → kg 换算精度 (kg 保留 4 位小数, 与 FinishedGoodsBatch.producedQuantity scale 一致)。 */
    private static final int KG_SCALE = 4;

    /**
     * 把数量 {@code qty}（原单位 {@code fromUnit}）换算为 {@code toUnit} 计量。
     *
     * <ul>
     *   <li>{@code fromUnit} 与 {@code toUnit} 字符串相等（含都为 null）→ 原样返回，无需换算。</li>
     *   <li>一方为计数单位 (盒/个/件/只)、另一方为非计数单位 (视为 kg) → 经 {@code gramsPerUnit} 换算。</li>
     *   <li>双方都是计数单位但字符串不同 (如 "盒" vs "个")，或双方都非计数单位但字符串不同
     *       (如 "kg" vs "斤") → 换算系数未知，返回 {@code null} (诚实失败，不臆造 1:1)。</li>
     *   <li>需要换算但 {@code gramsPerUnit} 缺失/非正 → 返回 {@code null}。</li>
     * </ul>
     *
     * @param qty          待换算数量 (null 直接返回 null)
     * @param fromUnit     qty 的原单位
     * @param toUnit       目标单位
     * @param gramsPerUnit 该产品每盒/份标准克重 ({@link com.cretas.aims.entity.ProductType#getGramsPerUnit()})
     * @return 换算后数量；无法换算时返回 {@code null}
     */
    public static BigDecimal convert(BigDecimal qty, String fromUnit, String toUnit, BigDecimal gramsPerUnit) {
        if (qty == null) {
            return null;
        }
        if (java.util.Objects.equals(fromUnit, toUnit)) {
            return qty;
        }
        if (fromUnit == null || toUnit == null) {
            // 一方单位未知 → 无法确定是否需要换算, 诚实失败.
            return null;
        }
        boolean fromIsCount = FeedUnitConverter.isCountUnit(fromUnit);
        boolean toIsCount = FeedUnitConverter.isCountUnit(toUnit);
        if (fromIsCount == toIsCount) {
            // 同类 (都计数 或 都非计数) 但字符串不同 → 换算系数未知, 不可臆造.
            return null;
        }
        if (gramsPerUnit == null || gramsPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (fromIsCount) {
            // 计数(盒/个/件/只) → 重量(视为 kg): kg = 数量 × gramsPerUnit(g) / 1000
            return qty.multiply(gramsPerUnit)
                    .divide(BigDecimal.valueOf(1000), KG_SCALE, RoundingMode.HALF_UP);
        }
        // 重量(视为 kg) → 计数: 盒数 = kg × 1000 / gramsPerUnit(g)
        return qty.multiply(BigDecimal.valueOf(1000))
                .divide(gramsPerUnit, COUNT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Converts through optional per-transaction packaging snapshots before applying the existing
     * count/weight conversion. This keeps two carton choices for the same SKU unambiguous:
     * source cartons are expanded with the source snapshot, while target cartons are collapsed
     * with the target snapshot.
     */
    public static BigDecimal convertWithPackaging(
            BigDecimal qty,
            String fromUnit,
            String toUnit,
            BigDecimal gramsPerUnit,
            String fromPackagingUnit,
            String fromPackagingBaseUnit,
            BigDecimal fromPackagingFactor,
            String toPackagingUnit,
            String toPackagingBaseUnit,
            BigDecimal toPackagingFactor) {
        if (qty == null) return null;

        BigDecimal workingQty = qty;
        String workingFrom = fromUnit;
        if (isPackagingMatch(fromUnit, fromPackagingUnit, fromPackagingBaseUnit, fromPackagingFactor)) {
            workingQty = workingQty.multiply(fromPackagingFactor);
            workingFrom = fromPackagingBaseUnit;
        }

        boolean targetIsPackaging = isPackagingMatch(
                toUnit, toPackagingUnit, toPackagingBaseUnit, toPackagingFactor);
        String workingTo = targetIsPackaging ? toPackagingBaseUnit : toUnit;
        BigDecimal converted = convert(workingQty, workingFrom, workingTo, gramsPerUnit);
        if (converted == null) return null;
        if (!targetIsPackaging) return converted;
        return converted.divide(toPackagingFactor, 4, RoundingMode.HALF_UP);
    }

    private static boolean isPackagingMatch(
            String actualUnit, String packagingUnit, String baseUnit, BigDecimal factor) {
        return actualUnit != null
                && actualUnit.equals(packagingUnit)
                && baseUnit != null
                && !baseUnit.isBlank()
                && factor != null
                && factor.signum() > 0;
    }
}
