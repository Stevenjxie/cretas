/**
 * BOM 浮层行的数值格式化 —— 把 BomRecipeItemView / SeasoningBindingView 的原始字段
 * 转成 cell 组件要的展示字符串。
 *
 * 抽成独立文件(而不是散落在 ProductProcessWorkflowEditor.vue 的派生代码里)是因为
 * 这几个格式化规则各自都踩过坑, 值得单测钉住:
 *   - 辅料 dosagePerKgG 本来就是「每 kg 投入的克数」, 不需要再折算, 直接拼单位;
 *   - 包材 naturalHint 是 dosageText 的倒数表达("1 个能包多少 kg"), 不是另一份
 *     独立数据 —— 算错方向会把「0.05 个/kg」标成「= 1 个 / 0.05 kg」这种荒谬结果;
 *   - 两者都要求「没有可靠数值就返回空/undefined 走组件的占位态」, 不能垫 0。
 */

/**
 * 数字友好展示: 定点到 maxDecimals 再削掉尾随 0。
 * (规则原本抄自 views/production/bom/index.vue, 该页 2026-08-07 阶段 5 已删 ——
 *  此处已是唯一实现, 不再有"两处要保持一致"的对照方。)
 */
export function formatFriendlyNumber(value: unknown, maxDecimals = 4): string {
  const number = Number(value);
  if (!Number.isFinite(number)) return '—';
  return number.toFixed(maxDecimals).replace(/\.?0+$/, '');
}

/** 辅料行 dosageText —— dosagePerKgG 已经是「每 kg 投入需要多少克」, 原样拼单位即可。
 *  null/undefined/非正数一律返回 undefined, 让组件走「未填用量」占位, 不能显示 "0 g/kg"。 */
export function formatAuxiliaryDosageText(dosagePerKgG: number | null | undefined): string | undefined {
  if (dosagePerKgG == null || !Number.isFinite(dosagePerKgG) || dosagePerKgG <= 0) return undefined;
  return `${formatFriendlyNumber(dosagePerKgG)} g/kg`;
}

/** 包材行 dosageText —— standardQuantity 已经是「每 1 baseUnit 成品需要多少 unit 包材」。
 *  standardQuantity 缺失/非正数返回空串(组件按空串走「用量待补全」占位, 不能显示 0)。 */
export function formatPackagingDosageText(
  standardQuantity: number | null | undefined,
  unit: string | null | undefined,
  baseUnit: string,
): string {
  if (standardQuantity == null || !Number.isFinite(standardQuantity) || standardQuantity <= 0) return '';
  if (!unit) return '';
  return `${formatFriendlyNumber(standardQuantity)} ${unit}/${baseUnit}`;
}

/**
 * 包材行 naturalHint —— dosageText 的倒数表达, 给仓管看的原始口径。
 * dosageText "0.05 个/kg" 意味着「投 1 kg 成品要 0.05 个」, 倒过来就是
 * 「1 个能包 20 kg」——这才是仓管拿着一个真实包材时会问的问题。
 * 分母(standardQuantity)不是正数时无法倒算, 返回 undefined(组件不设 title,
 * 不设成空串 —— 空 tooltip 比没有 tooltip 更具误导性)。
 */
export function formatPackagingNaturalHint(
  standardQuantity: number | null | undefined,
  unit: string | null | undefined,
  baseUnit: string,
): string | undefined {
  if (standardQuantity == null || !Number.isFinite(standardQuantity) || standardQuantity <= 0) return undefined;
  if (!unit) return undefined;
  const reciprocal = 1 / standardQuantity;
  return `= 1 ${unit} / ${formatFriendlyNumber(reciprocal)} ${baseUnit}`;
}
