export type LabelQcTaskStatus =
  | 'DRAFT'
  | 'UPLOADING'
  | 'QUEUED'
  | 'ANALYZING'
  | 'NEEDS_REVIEW'
  | 'REVIEWED'
  | 'ANALYSIS_FAILED';

export type LabelQcPhotoStatus =
  | 'UPLOADED'
  | 'QUEUED'
  | 'ANALYZING'
  | 'ANALYZED'
  | 'ANALYSIS_FAILED'
  | 'REVIEWED';

export type LabelQcLabel =
  | 'MISSING_WHITE_LABEL'
  | 'MISSING_COLOR_LABEL'
  | 'NO_DEFECT'
  | 'UNJUDGEABLE';

export interface LabelQcBoundingBox {
  xMin: number;
  yMin: number;
  xMax: number;
  yMax: number;
}

export interface LabelQcAnnotation {
  id: string;
  source: 'AI' | 'HUMAN';
  aiCandidateId?: string;
  aiLabel?: LabelQcLabel;
  aiConfidence?: number;
  aiEvidence?: string;
  humanLabel?: LabelQcLabel;
  bbox?: LabelQcBoundingBox;
  reviewerNotes?: string;
}

export interface LabelQcPhoto {
  id: string;
  attachmentId: string;
  orderIndex: number;
  imageWidth: number;
  imageHeight: number;
  status: LabelQcPhotoStatus;
  imageUrl?: string;
  aiModel?: string;
  promptVersion?: string;
  analysisError?: string;
  annotations: LabelQcAnnotation[];
}

export interface LabelQcTaskSummary {
  id: string;
  productTypeId: string;
  skuCode: string;
  skuName: string;
  batchNumber: string;
  productionDate: string;
  createdBy: number;
  status: LabelQcTaskStatus;
  photoCount: number;
  aiCandidateCount: number;
  finalDefectCount: number;
  reviewedBy?: number;
  reviewedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface LabelQcTaskDetail {
  task: LabelQcTaskSummary;
  photos: LabelQcPhoto[];
}
