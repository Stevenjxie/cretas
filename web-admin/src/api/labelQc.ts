import { get, post, put } from './request';
import type { ApiResponse } from '@/types/api';

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
  aiCandidateId?: string | null;
  aiLabel?: LabelQcLabel | null;
  aiConfidence?: number | null;
  aiEvidence?: string | null;
  humanLabel?: LabelQcLabel | null;
  bbox?: LabelQcBoundingBox | null;
  reviewerNotes?: string | null;
}

export interface LabelQcPhoto {
  id: string;
  attachmentId: string;
  orderIndex: number;
  imageWidth: number;
  imageHeight: number;
  status: LabelQcPhotoStatus;
  imageUrl?: string | null;
  aiModel?: string | null;
  promptVersion?: string | null;
  analysisError?: string | null;
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
  reviewedBy?: number | null;
  reviewedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface LabelQcTaskDetail {
  task: LabelQcTaskSummary;
  photos: LabelQcPhoto[];
}

export interface LabelQcPage<T> {
  content: T[];
  page: number;
  currentPage: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface LabelQcStatusCounts {
  counts: Partial<Record<LabelQcTaskStatus, number>>;
}

export interface LabelQcAnnotationReview {
  annotationId?: string;
  label: LabelQcLabel;
  bbox?: LabelQcBoundingBox | null;
  notes?: string;
}

export interface LabelQcPhotoReview {
  photoId: string;
  annotations: LabelQcAnnotationReview[];
}

export interface LabelQcReviewRequest {
  photos: LabelQcPhotoReview[];
}

export interface LabelQcTrainingPhoto {
  taskId: string;
  photoId: string;
  imageUrl: string;
  imageWidth: number;
  imageHeight: number;
  skuCode: string;
  skuName: string;
  batchNumber: string;
  productionDate: string;
  reviewedAt: string;
  finalAnnotations: LabelQcAnnotation[];
}

export function listLabelQcTasks(
  factoryId: string,
  params: {
    statuses?: LabelQcTaskStatus[];
    page?: number;
    size?: number;
  } = {},
): Promise<ApiResponse<LabelQcPage<LabelQcTaskSummary>>> {
  const statuses = params.statuses?.length ? params.statuses.join(',') : undefined;
  return get<LabelQcPage<LabelQcTaskSummary>>(`/${factoryId}/label-qc/tasks`, {
    params: { page: params.page ?? 1, size: params.size ?? 20, statuses },
  });
}

export function getLabelQcStatusCounts(
  factoryId: string,
): Promise<ApiResponse<LabelQcStatusCounts>> {
  return get<LabelQcStatusCounts>(`/${factoryId}/label-qc/tasks/status-counts`);
}

export function getLabelQcTask(
  factoryId: string,
  taskId: string,
): Promise<ApiResponse<LabelQcTaskDetail>> {
  return get<LabelQcTaskDetail>(`/${factoryId}/label-qc/tasks/${taskId}`);
}

export function reviewLabelQcTask(
  factoryId: string,
  taskId: string,
  payload: LabelQcReviewRequest,
): Promise<ApiResponse<LabelQcTaskDetail>> {
  return put<LabelQcTaskDetail>(`/${factoryId}/label-qc/tasks/${taskId}/review`, payload);
}

export function retryLabelQcTask(
  factoryId: string,
  taskId: string,
): Promise<ApiResponse<LabelQcTaskDetail>> {
  return post<LabelQcTaskDetail>(`/${factoryId}/label-qc/tasks/${taskId}/retry`);
}

export function exportLabelQcTrainingData(
  factoryId: string,
  params: { from: string; to: string; limit?: number },
): Promise<ApiResponse<LabelQcTrainingPhoto[]>> {
  return get<LabelQcTrainingPhoto[]>(`/${factoryId}/label-qc/training-export`, { params });
}
