/**
 * 盒⇄kg 投料折算 (前端) — 逐道录入把「计数单位(盒/个/件/只)」的成品(FG)/半成品(SFI)库存来源,
 * 折算为 kg 口径的投料可用量/超投判定/展示。折算依据 ProductType.gramsPerUnit (每盒/份标准克重)。
 *
 * 客户 (张权) 澄清: 盒↔kg 折算是确定性的 (每盒克重 BOM/SKU 固定), 故盒装成品可作 kg 道投料来源。
 *
 * 🔴 诚实 (禁止降级): 计数单位来源缺 gramsPerUnit → 拦截 (返回 block 文案), 绝不臆造 1盒=1kg。
 *
 * 后端对应: FeedUnitConverter (盒 = kg × 1000 / gramsPerUnit) + consumeForFeedStrict / consumeClerkSemiStrict。
 */

/** 计数单位判定，与后端 FeedUnitConverter.isCountUnit 保持同口径。 */
export function isCountUnit(unit: string | null | undefined): boolean {
  return !!unit && /盒|袋|包|个|件|只|份|瓶|罐/.test(unit);
}

/** 每盒克重是否有效 (>0)。诚实: null/0/负 → 无法折算。 */
export function hasValidGrams(gramsPerUnit: number | null | undefined): boolean {
  return gramsPerUnit != null && gramsPerUnit > 0;
}

/**
 * 计数单位库存的可投 kg 上限 = 可用盒数 × 每盒克重 / 1000。
 * @returns kg 可用量; gramsPerUnit 缺失/非正 → null (无法折算, 诚实 null)。
 */
export function boxAvailableKg(
  availableBoxes: number,
  gramsPerUnit: number | null | undefined,
): number | null {
  if (!hasValidGrams(gramsPerUnit)) return null;
  return (availableBoxes * (gramsPerUnit as number)) / 1000;
}

/**
 * kg 投料量折算为盒数 = kg × 1000 / gramsPerUnit (盒源扣减/计价口径)。
 * @returns 盒数; gramsPerUnit 缺失/非正 → null (诚实 null)。
 */
export function kgToBox(usageKg: number, gramsPerUnit: number | null | undefined): number | null {
  if (!hasValidGrams(gramsPerUnit)) return null;
  return (usageKg * 1000) / (gramsPerUnit as number);
}

/**
 * 计数单位投料来源的防呆校验 (超投/缺克重), 返回 block 文案或 null (通过)。
 * 仅当来源为计数单位 (盒/个/件/只) 时调用; kg 源走原有 kg 比较, 不进此函数。
 *
 * - 缺每盒克重 (gramsPerUnit null) → 拦截 (诚实 null, 禁止臆造)。
 * - 用量(kg) 超出该来源可投 kg (可用盒 × 每盒克重 / 1000) → 拦截 (超出)。
 * - 通过 → null。
 *
 * @param unit           来源库存单位 (盒/个/件/只)
 * @param gramsPerUnit   每盒克重 (null = 未配)
 * @param availableBoxes 可用盒数
 * @param usageKg        本道投料量 (kg)
 * @param sourceLabel    文案主语 (如 "该成品来源" / "该半成品来源" / 批次号)
 */
export function countUnitFeedWarning(
  unit: string | null | undefined,
  gramsPerUnit: number | null | undefined,
  availableBoxes: number,
  usageKg: number,
  sourceLabel: string,
): string | null {
  if (!hasValidGrams(gramsPerUnit)) {
    return `${sourceLabel}为${unit ?? '盒'}装但未配置每盒标准克重(每盒克重), 无法折算kg投料, 请先在产品配置设置标准克重`;
  }
  const availKg = boxAvailableKg(availableBoxes, gramsPerUnit) as number;
  if (usageKg > availKg) {
    return `${sourceLabel}用量 ${usageKg}kg 超出可投 ${availKg.toFixed(2)}kg `
      + `(余${availableBoxes}${unit ?? '盒'} × 每盒${gramsPerUnit}g)`;
  }
  return null;
}

/**
 * 计数单位来源下拉标签的折算后缀: " ≈ M kg (每盒 Xg)" 或 " ⚠未配每盒克重"。
 * 非计数单位来源返回空串 (不加后缀)。
 */
export function countUnitLabelSuffix(
  unit: string | null | undefined,
  gramsPerUnit: number | null | undefined,
  availableBoxes: number,
): string {
  if (!isCountUnit(unit)) return '';
  if (!hasValidGrams(gramsPerUnit)) return ' ⚠未配每盒克重(无法折算kg投料)';
  const availKg = boxAvailableKg(availableBoxes, gramsPerUnit) as number;
  return ` ≈ ${availKg.toFixed(2)}kg (每盒${gramsPerUnit}g)`;
}
