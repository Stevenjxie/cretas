import type { LabelQcBoundingBox, LabelQcObjectType } from '@/api/labelQc';

export interface OverlayLabelBox {
  key: string;
  type: LabelQcObjectType;
  bbox: LabelQcBoundingBox;
}

export interface HorizontalGuideRow {
  key: string;
  type: LabelQcObjectType;
  left: number;
  top: number;
  width: number;
  count: number;
}

function area(box: LabelQcBoundingBox): number {
  return Math.max(0, box.xMax - box.xMin) * Math.max(0, box.yMax - box.yMin);
}

function intersectionArea(a: LabelQcBoundingBox, b: LabelQcBoundingBox): number {
  return Math.max(0, Math.min(a.xMax, b.xMax) - Math.max(a.xMin, b.xMin))
    * Math.max(0, Math.min(a.yMax, b.yMax) - Math.max(a.yMin, b.yMin));
}

/**
 * Treats two boxes as duplicate proposals only when they cover most of the
 * smaller box and at least half of their union. This is intentionally stricter
 * than ordinary overlap so adjacent physical labels are never auto-removed.
 */
export function isObviousDuplicate(
  a: LabelQcBoundingBox,
  b: LabelQcBoundingBox,
): boolean {
  const overlap = intersectionArea(a, b);
  const smaller = Math.min(area(a), area(b));
  const union = area(a) + area(b) - overlap;
  if (smaller <= 0 || union <= 0) return false;
  return overlap / smaller >= 0.8 && overlap / union >= 0.5;
}

/**
 * Collapses a whole connected duplicate group, including 3+ stacked boxes,
 * and keeps the larger proposal as the safer editable starting point.
 */
export function deduplicateOverlayBoxes<T extends OverlayLabelBox>(boxes: T[]): {
  kept: T[];
  rejected: T[];
} {
  const parent = boxes.map((_, index) => index);
  const find = (index: number): number => {
    let cursor = index;
    while (parent[cursor] !== cursor) {
      parent[cursor] = parent[parent[cursor]!]!;
      cursor = parent[cursor]!;
    }
    return cursor;
  };
  const union = (left: number, right: number): void => {
    const leftRoot = find(left);
    const rightRoot = find(right);
    if (leftRoot !== rightRoot) parent[rightRoot] = leftRoot;
  };

  for (let left = 0; left < boxes.length; left += 1) {
    for (let right = left + 1; right < boxes.length; right += 1) {
      if (boxes[left]!.type !== boxes[right]!.type) continue;
      if (isObviousDuplicate(boxes[left]!.bbox, boxes[right]!.bbox)) union(left, right);
    }
  }

  const groups = new Map<number, number[]>();
  boxes.forEach((_, index) => {
    const root = find(index);
    groups.set(root, [...(groups.get(root) ?? []), index]);
  });

  const keepIndexes = new Set<number>();
  for (const indexes of groups.values()) {
    const preferred = [...indexes].sort((a, b) => area(boxes[b]!.bbox) - area(boxes[a]!.bbox))[0];
    if (preferred !== undefined) keepIndexes.add(preferred);
  }

  return {
    kept: boxes.filter((_, index) => keepIndexes.has(index)),
    rejected: boxes.filter((_, index) => !keepIndexes.has(index)),
  };
}

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  if (sorted.length % 2) return sorted[middle]!;
  return ((sorted[middle - 1] ?? 0) + (sorted[middle] ?? 0)) / 2;
}

/** Builds solid horizontal row guides; it never emits vertical connectors. */
export function buildHorizontalGuideRows(boxes: OverlayLabelBox[]): HorizontalGuideRow[] {
  const rows: HorizontalGuideRow[] = [];
  for (const type of ['WHITE_LABEL', 'COLOR_LABEL'] as const) {
    const candidates = boxes
      .filter((box) => box.type === type)
      .map((box) => ({
        ...box,
        centerX: (box.bbox.xMin + box.bbox.xMax) / 2,
        centerY: (box.bbox.yMin + box.bbox.yMax) / 2,
        height: box.bbox.yMax - box.bbox.yMin,
      }))
      .sort((a, b) => a.centerY - b.centerY);
    if (candidates.length < 2) continue;
    const tolerance = Math.max(0.015, median(candidates.map((box) => box.height)) * 0.65);
    const grouped: typeof candidates[] = [];
    for (const candidate of candidates) {
      const row = grouped.find((group) => (
        Math.abs(candidate.centerY - median(group.map((box) => box.centerY))) <= tolerance
      ));
      if (row) row.push(candidate);
      else grouped.push([candidate]);
    }
    grouped.forEach((group, index) => {
      if (group.length < 2) return;
      const centers = group.map((box) => box.centerX);
      const left = Math.min(...centers);
      const right = Math.max(...centers);
      rows.push({
        key: `${type}-${index}-${group.map((box) => box.key).join('-')}`,
        type,
        left,
        top: median(group.map((box) => box.centerY)),
        width: right - left,
        count: group.length,
      });
    });
  }
  return rows;
}
