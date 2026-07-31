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

/**
 * 计数单位的规范码 (COUNT + PACKAGE 两个量纲), 与后端
 * UnitContractServiceImpl.systemUnits() 的 dimension 一一对应。
 *
 * 🔴 原来的判定只有一条中文正则 `/盒|袋|包|个|件|只|份|瓶|罐/` —— `bag`/`box`/`pcs` 这些
 * **英文码一律判 false → 被当成质量单位走 kg 数学**。客户 2026-07-31 那个 SKU 的单位存的
 * 正是 `bag`。这类错**不报错**, 只是算出来的数不对, 比被 409 拦住更难发现。
 */
const COUNT_UNIT_CODES = new Set([
  // COUNT
  'pcs', '件', '个', '只', 'portion', '份', 'slice', '片', 'item', '项',
  // PACKAGE
  'box', '盒', 'case', '箱', 'bag', '袋', 'pack', '包', 'bottle', '瓶',
  'can', '罐', 'crate', '框', '筐', 'pail', '桶', 'roll', '卷',
]);

/**
 * 计数单位判定，与后端 FeedUnitConverter.isCountUnit 保持同口径。
 *
 * 两段式与后端逐条对应: 先按规范码/别名**精确**查, 再退回原来的中文子串**模糊**匹配
 * (认得「盒(500g)」「大盒」这类复合标签)。取**或** → 改动前判为计数单位的, 改动后一定还是。
 *
 * ⚠️ 本次新增被判为计数单位的: 全部英文码 + 中文的 箱/框/筐/桶/卷/片/项。它们此前按 kg 透传,
 * 之后要求 gramsPerUnit, 缺了会走「诚实拦截」而不再静默按 kg 算 —— 那正是期望行为。
 */
export function isCountUnit(unit: string | null | undefined): boolean {
  if (!unit) return false;
  const normalized = unit.trim().toLowerCase();
  return COUNT_UNIT_CODES.has(normalized) || /盒|袋|包|个|件|只|份|瓶|罐/.test(unit);
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
