export type LabelQcTaskStatus =
  | 'DRAFT'
  | 'UPLOADING'
  | 'QUEUED'
  | 'ANALYZING'
  | 'NEEDS_REVIEW'
  | 'REVIEWED'
  | 'ANALYSIS_FAILED';

export type LabelQcTrainingStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

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
  /**
   * AI 初筛明细原文 (盒子框 + 每盒识别到的白标/彩标)。
   * 后端整段透传, 前端只解析出来画参考层, 不当作契约字段依赖。
   */
  screeningDetail?: string;
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
  version: number;
  photoCount: number;
  aiCandidateCount: number;
  finalDefectCount: number;
  reviewedBy?: number;
  reviewedAt?: string;
  archived: boolean;
  archivedBy?: number;
  archivedAt?: string;
  trainingStatus: LabelQcTrainingStatus;
  trainingDecidedBy?: number;
  trainingDecidedAt?: string;
  trainingDecisionNotes?: string;
  backupExportedBy?: number;
  backupExportedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface LabelQcTaskDetail {
  task: LabelQcTaskSummary;
  photos: LabelQcPhoto[];
}

export interface LabelQcTaskPage {
  content: LabelQcTaskSummary[];
  page: number;
  currentPage: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  number?: number;
  empty?: boolean;
}

export interface LabelQcAnnotationReviewRequest {
  annotationId?: string;
  label: LabelQcLabel;
  bbox?: LabelQcBoundingBox;
  notes?: string;
}

export interface LabelQcPhotoReviewRequest {
  photoId: string;
  annotations: LabelQcAnnotationReviewRequest[];
}

export interface LabelQcReviewTaskRequest {
  expectedVersion: number;
  reviewRequestId: string;
  photos: LabelQcPhotoReviewRequest[];
}
