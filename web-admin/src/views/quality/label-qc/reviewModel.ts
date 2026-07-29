import type {
  LabelQcAnnotationReview,
  LabelQcBoundingBox,
  LabelQcLabel,
  LabelQcPhoto,
  LabelQcPhotoReview,
  LabelQcTaskDetail,
} from '@/api/labelQc';

export interface LabelQcReviewDraft extends Omit<LabelQcAnnotationReview, 'label'> {
  key: string;
  source: 'AI' | 'HUMAN';
  aiLabel?: LabelQcLabel;
  aiConfidence?: number | null;
  aiEvidence?: string | null;
  label?: LabelQcLabel;
}

export interface LabelQcPhotoDraft {
  photoId: string;
  reviewed: boolean;
  items: LabelQcReviewDraft[];
}

const isDefect = (label?: LabelQcLabel): boolean => (
  label === 'MISSING_WHITE_LABEL' || label === 'MISSING_COLOR_LABEL'
);

const candidateDefault = (photo: LabelQcPhoto): LabelQcReviewDraft[] => (
  photo.annotations.map((annotation) => {
    const persistedLabel = annotation.humanLabel
      ?? (photo.status === 'REVIEWED' ? annotation.aiLabel : null)
      ?? undefined;
    return {
      key: annotation.id,
      source: annotation.source,
      annotationId: annotation.source === 'AI' ? annotation.id : undefined,
      aiLabel: annotation.aiLabel ?? undefined,
      aiConfidence: annotation.aiConfidence,
      aiEvidence: annotation.aiEvidence,
      label: persistedLabel,
      bbox: annotation.bbox ?? null,
      notes: annotation.reviewerNotes ?? '',
    };
  })
);

export function buildReviewDraft(detail: LabelQcTaskDetail): LabelQcPhotoDraft[] {
  return detail.photos.map((photo) => ({
    photoId: photo.id,
    reviewed: photo.status === 'REVIEWED',
    items: candidateDefault(photo),
  }));
}

export function appendHumanBox(
  photoDraft: LabelQcPhotoDraft,
  bbox: LabelQcBoundingBox,
  key: string,
): LabelQcReviewDraft {
  const item: LabelQcReviewDraft = {
    key,
    source: 'HUMAN',
    bbox,
    notes: '',
  };
  photoDraft.items.push(item);
  photoDraft.reviewed = false;
  return item;
}

export function pendingItemCount(photo: LabelQcPhotoDraft): number {
  return photo.items.filter((item) => !item.label).length;
}

export function isPhotoComplete(photo: LabelQcPhotoDraft): boolean {
  return photo.reviewed && pendingItemCount(photo) === 0;
}

export function completedPhotoCount(drafts: LabelQcPhotoDraft[]): number {
  return drafts.filter(isPhotoComplete).length;
}

export function firstIncompletePhotoIndex(
  drafts: LabelQcPhotoDraft[],
  afterIndex = -1,
): number {
  if (drafts.length === 0) return -1;
  for (let offset = 1; offset <= drafts.length; offset += 1) {
    const index = (afterIndex + offset) % drafts.length;
    const photo = drafts[index];
    if (photo && !isPhotoComplete(photo)) return index;
  }
  return -1;
}

export function validatePhotoConclusion(photo: LabelQcPhotoDraft): string | null {
  const pending = pendingItemCount(photo);
  if (pending > 0) return `还有 ${pending} 个框未确认`;
  for (const item of photo.items) {
    if (isDefect(item.label) && !item.bbox) return '缺标结论缺少问题框';
  }
  return null;
}

export function markPhotoReviewed(photo: LabelQcPhotoDraft): string | null {
  const validation = validatePhotoConclusion(photo);
  if (validation) return validation;
  photo.reviewed = true;
  return null;
}

export function markPhotoNormal(photo: LabelQcPhotoDraft): void {
  const humanItemCount = photo.items.filter((item) => item.source === 'HUMAN').length;
  if (humanItemCount > 0) {
    throw new Error(`还有 ${humanItemCount} 个人工补框，请先逐个删除或确认问题`);
  }
  photo.items = photo.items
    .filter((item) => item.source === 'AI')
    .map((item) => ({
      ...item,
      label: 'NO_DEFECT' as const,
      notes: item.notes || '人工复核：本图未发现缺标',
    }));
  photo.reviewed = true;
}

const AUTO_REJECTION_NOTES = new Set([
  '人工复核：AI 疑点不成立',
  '人工复核：本图未发现缺标',
]);

export function restoreRejectedAiCandidate(
  photo: LabelQcPhotoDraft,
  itemKey: string,
): LabelQcReviewDraft | null {
  const item = photo.items.find((candidate) => candidate.key === itemKey);
  if (!item || item.source !== 'AI' || item.label !== 'NO_DEFECT') return null;
  item.label = undefined;
  if (item.notes && AUTO_REJECTION_NOTES.has(item.notes)) item.notes = '';
  photo.reviewed = false;
  return item;
}

export function validateReviewDraft(drafts: LabelQcPhotoDraft[]): string | null {
  if (drafts.length === 0) return '任务没有可审核照片';
  for (let photoIndex = 0; photoIndex < drafts.length; photoIndex += 1) {
    const photo = drafts[photoIndex];
    if (!photo) return `第 ${photoIndex + 1} 张照片数据缺失`;
    const photoValidation = validatePhotoConclusion(photo);
    if (photoValidation) return `第 ${photoIndex + 1} 张照片${photoValidation}`;
    if (!photo.reviewed) return `第 ${photoIndex + 1} 张照片尚未给出整图结论`;
  }
  return null;
}

export function toReviewRequest(drafts: LabelQcPhotoDraft[]): { photos: LabelQcPhotoReview[] } {
  const validation = validateReviewDraft(drafts);
  if (validation) throw new Error(validation);
  return {
    photos: drafts.map((photo) => {
      const annotations = photo.items.map((item) => ({
        annotationId: item.annotationId,
        label: item.label!,
        bbox: item.bbox ?? null,
        notes: item.notes?.trim() || undefined,
      }));
      if (annotations.length === 0) {
        annotations.push({
          annotationId: undefined,
          label: 'NO_DEFECT',
          bbox: null,
          notes: '人工复核：本图未发现缺标',
        });
      }
      return {
        photoId: photo.photoId,
        annotations,
      };
    }),
  };
}

export function normalizedBox(
  startX: number,
  startY: number,
  endX: number,
  endY: number,
  width: number,
  height: number,
): LabelQcBoundingBox | null {
  if (width <= 0 || height <= 0) return null;
  const left = Math.max(0, Math.min(startX, endX));
  const top = Math.max(0, Math.min(startY, endY));
  const right = Math.min(width, Math.max(startX, endX));
  const bottom = Math.min(height, Math.max(startY, endY));
  if (right - left < 8 || bottom - top < 8) return null;
  return {
    xMin: left / width,
    yMin: top / height,
    xMax: right / width,
    yMax: bottom / height,
  };
}

/**
 * 涂抹笔迹 → 外接矩形(像素)。笔刷半径要算进去, 否则圈出来的框比实际涂过的范围
 * 小一圈 —— 质检员涂的是"这块区域", 不是"这条中心线"。
 *
 * 下游训练的是检测器(框), 所以笔迹本身不落库: 涂抹只是比精确拖拽更快的圈定方式,
 * 最终产物和拉框完全一样。
 */
export function strokeBounds(
  stroke: { x: number; y: number }[],
  radius: number,
): { x0: number; y0: number; x1: number; y1: number } | null {
  if (!stroke.length || radius <= 0) return null;
  let x0 = Infinity;
  let y0 = Infinity;
  let x1 = -Infinity;
  let y1 = -Infinity;
  for (const point of stroke) {
    if (!Number.isFinite(point.x) || !Number.isFinite(point.y)) continue;
    x0 = Math.min(x0, point.x);
    y0 = Math.min(y0, point.y);
    x1 = Math.max(x1, point.x);
    y1 = Math.max(y1, point.y);
  }
  if (!Number.isFinite(x0) || !Number.isFinite(y0)) return null;
  return { x0: x0 - radius, y0: y0 - radius, x1: x1 + radius, y1: y1 + radius };
}

export function pointBox(
  x: number,
  y: number,
  width = 0.22,
  height = 0.12,
): LabelQcBoundingBox {
  const xMin = Math.max(0, Math.min(1 - width, x - width / 2));
  const yMin = Math.max(0, Math.min(1 - height, y - height / 2));
  return {
    xMin,
    yMin,
    xMax: xMin + width,
    yMax: yMin + height,
  };
}

export function moveBox(
  box: LabelQcBoundingBox,
  deltaX: number,
  deltaY: number,
): LabelQcBoundingBox {
  const width = box.xMax - box.xMin;
  const height = box.yMax - box.yMin;
  const xMin = Math.max(0, Math.min(1 - width, box.xMin + deltaX));
  const yMin = Math.max(0, Math.min(1 - height, box.yMin + deltaY));
  return { xMin, yMin, xMax: xMin + width, yMax: yMin + height };
}

export function resizeBox(
  box: LabelQcBoundingBox,
  deltaX: number,
  deltaY: number,
  minimumSize = 0.03,
): LabelQcBoundingBox {
  return {
    ...box,
    xMax: Math.max(box.xMin + minimumSize, Math.min(1, box.xMax + deltaX)),
    yMax: Math.max(box.yMin + minimumSize, Math.min(1, box.yMax + deltaY)),
  };
}
