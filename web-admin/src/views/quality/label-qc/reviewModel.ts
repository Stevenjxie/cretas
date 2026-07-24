import type {
  LabelQcAnnotationReview,
  LabelQcBoundingBox,
  LabelQcPhoto,
  LabelQcPhotoReview,
  LabelQcTaskDetail,
} from '@/api/labelQc';

export interface LabelQcReviewDraft extends LabelQcAnnotationReview {
  key: string;
  source: 'AI' | 'HUMAN';
  aiLabel?: LabelQcAnnotationReview['label'];
  aiConfidence?: number | null;
  aiEvidence?: string | null;
}

export interface LabelQcPhotoDraft {
  photoId: string;
  items: LabelQcReviewDraft[];
}

const candidateDefault = (photo: LabelQcPhoto): LabelQcReviewDraft[] => {
  if (photo.annotations.length === 0) {
    return [{
      key: `negative-${photo.id}`,
      source: 'HUMAN',
      label: 'NO_DEFECT',
      bbox: null,
      notes: '',
    }];
  }
  return photo.annotations.map((annotation) => ({
    key: annotation.id,
    source: annotation.source,
    annotationId: annotation.source === 'AI' ? annotation.id : undefined,
    aiLabel: annotation.aiLabel ?? undefined,
    aiConfidence: annotation.aiConfidence,
    aiEvidence: annotation.aiEvidence,
    label: annotation.humanLabel ?? annotation.aiLabel ?? 'UNJUDGEABLE',
    bbox: annotation.bbox ?? null,
    notes: annotation.reviewerNotes ?? '',
  }));
};

export function buildReviewDraft(detail: LabelQcTaskDetail): LabelQcPhotoDraft[] {
  return detail.photos.map((photo) => ({
    photoId: photo.id,
    items: candidateDefault(photo),
  }));
}

export function appendHumanBox(
  photoDraft: LabelQcPhotoDraft,
  bbox: LabelQcBoundingBox,
  key: string,
): void {
  const onlyAutoNegative = photoDraft.items.length === 1
    && photoDraft.items[0]?.source === 'HUMAN'
    && photoDraft.items[0]?.label === 'NO_DEFECT'
    && !photoDraft.items[0]?.annotationId
    && !photoDraft.items[0]?.bbox;
  if (onlyAutoNegative) {
    photoDraft.items = [];
  }
  photoDraft.items.push({
    key,
    source: 'HUMAN',
    label: 'MISSING_WHITE_LABEL',
    bbox,
    notes: '',
  });
}

export function validateReviewDraft(drafts: LabelQcPhotoDraft[]): string | null {
  if (drafts.length === 0) return '任务没有可审核照片';
  for (let photoIndex = 0; photoIndex < drafts.length; photoIndex += 1) {
    const photo = drafts[photoIndex];
    if (!photo || photo.items.length === 0) {
      return `第 ${photoIndex + 1} 张照片尚未给出审核结论`;
    }
    for (const item of photo.items) {
      const isDefect = item.label === 'MISSING_WHITE_LABEL'
        || item.label === 'MISSING_COLOR_LABEL';
      if (isDefect && !item.bbox) {
        return `第 ${photoIndex + 1} 张照片的缺标结论缺少问题框`;
      }
    }
  }
  return null;
}

export function toReviewRequest(drafts: LabelQcPhotoDraft[]): { photos: LabelQcPhotoReview[] } {
  return {
    photos: drafts.map((photo) => ({
      photoId: photo.photoId,
      annotations: photo.items.map((item) => ({
        annotationId: item.annotationId,
        label: item.label,
        bbox: item.bbox ?? null,
        notes: item.notes?.trim() || undefined,
      })),
    })),
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
