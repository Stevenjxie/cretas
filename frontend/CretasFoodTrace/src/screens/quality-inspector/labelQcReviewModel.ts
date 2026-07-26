import {
  LabelQcBoundingBox,
  LabelQcLabel,
  LabelQcReviewTaskRequest,
  LabelQcTaskDetail,
} from '../../types/labelQc';

export interface LabelQcReviewAnnotationDraft {
  key: string;
  annotationId?: string;
  source: 'AI' | 'HUMAN';
  aiLabel?: LabelQcLabel;
  aiEvidence?: string;
  label?: LabelQcLabel;
  bbox?: LabelQcBoundingBox;
}

export interface LabelQcReviewPhotoDraft {
  photoId: string;
  reviewed: boolean;
  annotations: LabelQcReviewAnnotationDraft[];
}

const DEFECT_LABELS: LabelQcLabel[] = [
  'MISSING_WHITE_LABEL',
  'MISSING_COLOR_LABEL',
  'UNJUDGEABLE',
];

const MIN_BOX_SIZE = 0.04;
const DEFAULT_BOX_SIZE = 0.18;

const clamp = (value: number, min: number, max: number): number =>
  Math.min(Math.max(value, min), max);

export const isDefectLabel = (label?: LabelQcLabel): boolean =>
  Boolean(label && DEFECT_LABELS.includes(label));

export const clampBoundingBox = (
  bbox: LabelQcBoundingBox,
): LabelQcBoundingBox => {
  const xMin = clamp(Math.min(bbox.xMin, bbox.xMax), 0, 1 - MIN_BOX_SIZE);
  const yMin = clamp(Math.min(bbox.yMin, bbox.yMax), 0, 1 - MIN_BOX_SIZE);
  const xMax = clamp(Math.max(bbox.xMin, bbox.xMax), xMin + MIN_BOX_SIZE, 1);
  const yMax = clamp(Math.max(bbox.yMin, bbox.yMax), yMin + MIN_BOX_SIZE, 1);
  return { xMin, yMin, xMax, yMax };
};

export const createCenteredBoundingBox = (
  x: number,
  y: number,
): LabelQcBoundingBox => {
  const half = DEFAULT_BOX_SIZE / 2;
  return clampBoundingBox({
    xMin: x - half,
    yMin: y - half,
    xMax: x + half,
    yMax: y + half,
  });
};

export const translateBoundingBox = (
  bbox: LabelQcBoundingBox,
  deltaX: number,
  deltaY: number,
): LabelQcBoundingBox => {
  const width = bbox.xMax - bbox.xMin;
  const height = bbox.yMax - bbox.yMin;
  const xMin = clamp(bbox.xMin + deltaX, 0, 1 - width);
  const yMin = clamp(bbox.yMin + deltaY, 0, 1 - height);
  return {
    xMin,
    yMin,
    xMax: xMin + width,
    yMax: yMin + height,
  };
};

export const resizeBoundingBox = (
  bbox: LabelQcBoundingBox,
  deltaX: number,
  deltaY: number,
): LabelQcBoundingBox =>
  clampBoundingBox({
    ...bbox,
    xMax: bbox.xMax + deltaX,
    yMax: bbox.yMax + deltaY,
  });

export const hydrateLabelQcReviewDrafts = (
  detail: LabelQcTaskDetail,
): LabelQcReviewPhotoDraft[] =>
  detail.photos
    .slice()
    .sort((left, right) => left.orderIndex - right.orderIndex)
    .map((photo) => ({
      photoId: photo.id,
      reviewed: photo.status === 'REVIEWED',
      annotations: photo.annotations.map((annotation) => ({
        key: annotation.id,
        annotationId: annotation.source === 'AI' ? annotation.id : undefined,
        source: annotation.source,
        aiLabel: annotation.aiLabel,
        aiEvidence: annotation.aiEvidence,
        label: annotation.humanLabel,
        bbox: annotation.bbox
          ? clampBoundingBox(annotation.bbox)
          : undefined,
      })),
    }));

export const addHumanAnnotation = (
  photo: LabelQcReviewPhotoDraft,
  x: number,
  y: number,
  key: string,
): LabelQcReviewPhotoDraft => ({
  ...photo,
  reviewed: false,
  annotations: [
    ...photo.annotations,
    {
      key,
      source: 'HUMAN',
      bbox: createCenteredBoundingBox(x, y),
    },
  ],
});

export const updateDraftAnnotation = (
  photo: LabelQcReviewPhotoDraft,
  key: string,
  updates: Partial<LabelQcReviewAnnotationDraft>,
): LabelQcReviewPhotoDraft => ({
  ...photo,
  reviewed: false,
  annotations: photo.annotations.map((annotation) =>
    annotation.key === key ? { ...annotation, ...updates } : annotation,
  ),
});

export const removeDraftAnnotation = (
  photo: LabelQcReviewPhotoDraft,
  key: string,
): LabelQcReviewPhotoDraft => ({
  ...photo,
  reviewed: false,
  annotations: photo.annotations.filter((annotation) => annotation.key !== key),
});

export const pendingAnnotationCount = (
  photo: LabelQcReviewPhotoDraft,
): number =>
  photo.annotations.filter((annotation) => !annotation.label).length;

export const isPhotoReviewComplete = (
  photo: LabelQcReviewPhotoDraft,
): boolean => photo.reviewed && pendingAnnotationCount(photo) === 0;

export const nextIncompletePhotoIndex = (
  photos: LabelQcReviewPhotoDraft[],
  currentIndex: number,
): number | null => {
  for (let offset = 1; offset <= photos.length; offset += 1) {
    const index = (currentIndex + offset) % photos.length;
    if (!isPhotoReviewComplete(photos[index]!)) return index;
  }
  return null;
};

export const markPhotoReviewed = (
  photo: LabelQcReviewPhotoDraft,
): LabelQcReviewPhotoDraft => {
  if (pendingAnnotationCount(photo) > 0) return photo;
  return { ...photo, reviewed: true };
};

export const buildLabelQcReviewRequest = (
  photos: LabelQcReviewPhotoDraft[],
): LabelQcReviewTaskRequest => {
  if (!photos.length || photos.some((photo) => !isPhotoReviewComplete(photo))) {
    throw new Error('仍有照片未完成最终确认');
  }

  return {
    photos: photos.map((photo) => {
      const annotations = photo.annotations.map((annotation) => {
        if (!annotation.label) {
          throw new Error('仍有标注未确认');
        }
        return {
          annotationId: annotation.annotationId,
          label: annotation.label,
          bbox: isDefectLabel(annotation.label)
            ? annotation.bbox
            : undefined,
          notes:
            annotation.source === 'HUMAN'
              ? '移动端人工补框'
              : annotation.label === 'NO_DEFECT'
                ? '人工拒绝 AI 疑点'
                : '人工确认 AI 疑点',
        };
      });

      return {
        photoId: photo.photoId,
        annotations: annotations.length
          ? annotations
          : [{ label: 'NO_DEFECT' as const, notes: '人工确认整图无其他问题' }],
      };
    }),
  };
};
