/**
 * 发货确认 — 纯逻辑 (无 RN 依赖, 便于单测).
 * 防呆 Rule 1: 实发数量不得超过计划数量 / 不得为负 / 不得为空.
 */

/** 校验单行实发数量. 返回错误文案 (null = 合法). */
export function validateQty(text: string, plannedQty: number): string | null {
  const trimmed = (text ?? '').trim();
  if (trimmed === '') return '不能为空';
  const n = Number(trimmed);
  if (!Number.isFinite(n)) return '请输入数字';
  if (n < 0) return '不能为负';
  if (n > plannedQty) return `不能超过计划 ${plannedQty}`;
  return null;
}

export interface QtyRow {
  id: string;
  plannedQty: number;
  actualQtyText: string;
}

/** 全部行是否可提交 (任一非法则不可提交). */
export function canSubmitRows(rows: QtyRow[]): boolean {
  if (rows.length === 0) return false;
  return rows.every((r) => validateQty(r.actualQtyText, r.plannedQty) == null);
}

/** 构建 confirm 请求的 actualQuantities map (key = 行 id 字符串). */
export function buildActualQuantities(rows: QtyRow[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const r of rows) {
    if (r.id) out[r.id] = Number(r.actualQtyText);
  }
  return out;
}
