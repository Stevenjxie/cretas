/**
 * 工序类别中「支持调味参数」的那两个值 —— 与后端
 * `com.cretas.aims.constant.SeasoningProcessCategory` 一一对应。
 *
 * ## 为什么前端也要有一份
 * 画布 AI 有两条路：补丁路（后端 ProductProcessWorkflowConfigTool 校验）和
 * 确定性编译器路（**前端**把 LLM 的语义规格建成图）。第二条路的类别闸只能在前端落地，
 * 后端根本看不到那份规格。两边都需要判据 ⇒ 两边都不许写裸字符串 `'熟制'` / `'注射'`。
 *
 * ⛔ 改这里必须同步改后端常量类，反之亦然。判据由
 * `__tests__/seasoningProcessCategory.source.spec.ts` 钉住（它直接读 Java 源文件比对）。
 */

/** 熟制类工序（如卤制、蒸煮）—— 支持「后续锅调料比例」。 */
export const SEASONING_CATEGORY_COOKING = '熟制';

/** 注射类工序（如盐水注射）—— 支持「注射量(kg)」。 */
export const SEASONING_CATEGORY_INJECTION = '注射';

/** 允许配「后续锅调料比例」的工序类别。 */
export const POT_RATIO_CATEGORIES: readonly string[] = [SEASONING_CATEGORY_COOKING];

/** 允许配「注射量」的工序类别。 */
export const INJECTION_CATEGORIES: readonly string[] = [SEASONING_CATEGORY_INJECTION];

/**
 * ⛔ 「没设类别」按**不允许**处理，不是按允许。
 * 缺证据时降级放行会让 AI 把锅序比例写进任意一道没填类别的工序，而那正是
 * 类别闸想拦的事（禁止降级处理）。
 */
export function allowsPotRatio(processCategory: unknown): boolean {
  return typeof processCategory === 'string'
    && POT_RATIO_CATEGORIES.includes(processCategory.trim());
}

export function allowsInjection(processCategory: unknown): boolean {
  return typeof processCategory === 'string'
    && INJECTION_CATEGORIES.includes(processCategory.trim());
}

/** 用量（克/kg）：必须 > 0。0 不是「没配」，是「配了个静默无效的行」。 */
export function isValidDosagePerKgG(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

/** 锅序比例：0–100。0 合法 —— 「后续锅不再加这味调料」是真实配置。 */
export function isValidSubsequentPotRatio(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100;
}

/** 注射量：必须 > 0。不注射就不该是注射类工序。 */
export function isValidInjectionAmount(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}
