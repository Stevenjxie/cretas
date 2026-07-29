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
  /** AI 初筛明细原文 JSON：托盘框 + 每盒识别到的白标/彩标框。可能为 null（旧数据或 VL 模式）。 */
  screeningDetail?: string | null;
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
  reviewedBy?: number | null;
  reviewedAt?: string | null;
  archived: boolean;
  archivedBy?: number | null;
  archivedAt?: string | null;
  trainingStatus: LabelQcTrainingStatus;
  trainingDecidedBy?: number | null;
  trainingDecidedAt?: string | null;
  trainingDecisionNotes?: string | null;
  backupExportedBy?: number | null;
  backupExportedAt?: string | null;
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
  expectedVersion?: number;
  reviewRequestId?: string;
  photos: LabelQcPhotoReview[];
}

export interface LabelQcTrainingDecisionRequest {
  approved: boolean;
  expectedVersion: number;
  notes?: string;
}

export interface LabelQcTaskBackup {
  data: LabelQcTaskDetail;
  exportedAt: string;
  exportedBy: number;
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
    archived?: boolean;
    page?: number;
    size?: number;
  } = {},
): Promise<ApiResponse<LabelQcPage<LabelQcTaskSummary>>> {
  const statuses = params.statuses?.length ? params.statuses.join(',') : undefined;
  return get<LabelQcPage<LabelQcTaskSummary>>(`/${factoryId}/label-qc/tasks`, {
    params: {
      page: params.page ?? 1,
      size: params.size ?? 20,
      statuses,
      archived: params.archived ?? false,
    },
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

export function archiveLabelQcTask(
  factoryId: string,
  taskId: string,
): Promise<ApiResponse<LabelQcTaskDetail>> {
  return post<LabelQcTaskDetail>(`/${factoryId}/label-qc/tasks/${taskId}/archive`);
}

export function restoreLabelQcTask(
  factoryId: string,
  taskId: string,
): Promise<ApiResponse<LabelQcTaskDetail>> {
  return post<LabelQcTaskDetail>(`/${factoryId}/label-qc/tasks/${taskId}/restore`);
}

export function backupLabelQcTask(
  factoryId: string,
  taskId: string,
): Promise<ApiResponse<LabelQcTaskBackup>> {
  return post<LabelQcTaskBackup>(`/${factoryId}/label-qc/tasks/${taskId}/backup`);
}

export function decideLabelQcTraining(
  factoryId: string,
  taskId: string,
  payload: LabelQcTrainingDecisionRequest,
): Promise<ApiResponse<LabelQcTaskDetail>> {
  return put<LabelQcTaskDetail>(
    `/${factoryId}/label-qc/tasks/${taskId}/training-decision`,
    payload,
  );
}

export function exportLabelQcTrainingData(
  factoryId: string,
  params: { from: string; to: string; limit?: number },
): Promise<ApiResponse<LabelQcTrainingPhoto[]>> {
  return get<LabelQcTrainingPhoto[]>(`/${factoryId}/label-qc/training-export`, { params });
}
