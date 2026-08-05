import type { BomRowMarker } from './bomOverlayMarkers';

/**
 * BOM 浮层 cell 的行/数据形状 —— 唯一权威定义。
 *
 * ⛔ Task 4/5 各自在组件文件里声明了一份同名接口(因为共享类型文件对它们不开放)，
 * `deriveBomOverlay` 的返回类型与组件的 prop 类型因此是两份互不相干的声明，
 * 编译期完全查不出字段错配(processName/usageSupported/outputName/baseUnit/id
 * 全部对不上也不会红)。这里把两侧收敛到同一份类型 —— `deriveBomOverlay` 的返回
 * 类型直接就是组件的 prop 类型，这类错配从「运行时才炸」变成「编译期挡下」。
 *
 * 两个组件文件仍 `export type { ... } from './bomOverlayTypes'` 保留原导入路径，
 * 已有的组件级单测不需要改导入。
 */

export interface AuxiliaryCellRow {
  id: string;
  materialName: string;
  /** Formatted dosage string, e.g. "2 g/kg". Missing/unresolved dosage must render an
   *  explicit placeholder — never silently fall back to "0" or a blank cell. */
  dosageText?: string | null;
  markers: BomRowMarker[];
}

export interface AuxiliaryCellData {
  processName: string;
  /**
   * Tri-state, NOT a plain boolean:
   * - `true`  — confirmed: this process's input basis has a convertible unit contract.
   * - `false` — confirmed: it does NOT (「每 kg 投入」没有分母可算) — the only case that
   *   may render the specific "缺换算契约" diagnosis.
   * - `null`  — unknown: BOM data hasn't loaded yet, the load failed, the product has no
   *   recipe at all, or the recipe is pinned to a revision whose node ids don't match this
   *   graph. All four causes collapse to the same `null` here (deriveBomOverlay has no way
   *   to tell them apart — see loadBomOverlayData in ProductProcessWorkflowEditor.vue) and
   *   must NOT be presented as "confirmed unsupported": that would be a diagnosis the code
   *   cannot actually justify (禁止降级处理, CLAUDE.md 核心原则 #1).
   * Both `false` and `null` grey out the cell and block *adding* new rows, but only
   * `false` may show the specific unit-contract copy — `null` must say the state is
   * simply not known yet.
   */
  usageSupported: boolean | null;
  rows: AuxiliaryCellRow[];
  /** The real (non-overlay) PROCESS node id this cell is attached to — the editor's
   *  add/edit-row handlers need this to know which process to open, without parsing
   *  it back out of the overlay node's `bom-overlay:aux:<id>` id string. */
  processNodeId: string;
  /**
   * Joint production: true when more than one BOM recipe binds seasoning to this same
   * workflow process node (e.g. two终端产出 sharing a 腌制 step). loadBomOverlayData only
   * keeps the first recipe's bindings ("first recipe wins", ruling 3a) — when this is true
   * the cell must say which recipe it is actually showing (`recipeOutputName`) so a user
   * editing it doesn't believe they're editing a different 产出's recipe.
   */
  sharedAcrossRecipes?: boolean;
  /** The 产出 name of the recipe currently driving this cell's rows — only meaningful
   *  (and only ever set) when `sharedAcrossRecipes` is true. */
  recipeOutputName?: string | null;
}

export interface PackagingCellRow {
  id: string;
  materialName: string;
  /** 已折算的用量表达, 含分母, 例如 "0.05 个/kg" */
  dosageText: string;
  /** 折算前的原始表达, 例如 "= 1 个 / 20 kg"; 缺省时不渲染 title */
  naturalHint?: string;
  markers: BomRowMarker[];
}

export interface PackagingCellData {
  /** 该包材 cell 所属的终端产出名 */
  outputName: string;
  /** 产出 SKU 的基本单位——分母来源,禁止硬编码 */
  baseUnit: string;
  rows: PackagingCellRow[];
  /** The real (non-overlay) FINISHED_GOOD node id this cell is attached to — same
   *  reasoning as AuxiliaryCellData.processNodeId. */
  outputNodeId: string;
}
