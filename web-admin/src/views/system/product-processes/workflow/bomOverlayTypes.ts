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
  /** false when the process's input basis has no convertible unit contract, so
   *  "every kg of input" has no denominator — the whole cell must grey out and
   *  block adding rows instead of failing later at save time. */
  usageSupported: boolean;
  rows: AuxiliaryCellRow[];
  /** The real (non-overlay) PROCESS node id this cell is attached to — the editor's
   *  add/edit-row handlers need this to know which process to open, without parsing
   *  it back out of the overlay node's `bom-overlay:aux:<id>` id string. */
  processNodeId: string;
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
