/**
 * BomVersion API — Sprint 5 Track-H H-2 frontend follow-up.
 *
 * 对应后端 BomVersionController (M-BOM-VER-1, Sprint 3 Track-H ship PR #694).
 * Base: /api/mobile/{factoryId}/bom/versions (request baseURL='/api/mobile').
 *
 * 后端 RBAC: GET 公开 (但 @PriceSensitive snapshot_json cost 字段会被 strip),
 * POST/approve/reject/submit 需 production:read_write / rd:read_write / finance:read_write.
 */
import { get, post } from './request';

/** BomVersion 状态机. */
export type BomVersionStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'OBSOLETE'
  | 'REJECTED';

/** BomVersion entity (1:1 mirror of Java BomVersion). */
export interface BomVersion {
  id: string;
  factoryId: string;
  bomRecipeId: string;
  versionNumber: number;
  /** Snapshot of BomRecipe + BomRecipeItem list at approval time. price-sensitive fields may be stripped. */
  snapshotJson: Record<string, unknown>;
  status: BomVersionStatus;
  /** ISO date YYYY-MM-DD, null while DRAFT/PENDING_APPROVAL/REJECTED. */
  effectiveFrom?: string | null;
  /** null = currently effective. */
  effectiveTo?: string | null;
  createdBy?: number | null;
  approvedBy?: number | null;
  /** ISO instant. */
  approvedAt?: string | null;
  /** Logical FK to EngineeringChangeNotice.id; null = manual version. */
  ecnId?: string | null;
  rejectionReason?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateBomDraftRequest {
  bomRecipeId: string;
  createdBy: number;
}

export interface SubmitBomVersionRequest {
  ecnId?: string;
}

export interface ApproveBomVersionRequest {
  approverId: number;
}

export interface RejectBomVersionRequest {
  approverId: number;
  rejectionReason: string;
}

const base = (factoryId: string) => `/${factoryId}/bom/versions`;

export const bomVersionApi = {
  /** 创建 DRAFT (snapshot 自动 from 当前 BomRecipe). */
  createDraft: (factoryId: string, req: CreateBomDraftRequest) =>
    post<BomVersion>(base(factoryId), req),

  /** BomVersion 详情. */
  getById: (factoryId: string, versionId: string) =>
    get<BomVersion>(`${base(factoryId)}/${versionId}`),

  /** BOM 全部历史版本 (newest first). */
  getHistory: (factoryId: string, bomRecipeId: string) =>
    get<BomVersion[]>(`${base(factoryId)}/by-recipe/${bomRecipeId}/history`),

  /** BOM 当前生效版本 (status=APPROVED + effective_to IS NULL). */
  getCurrent: (factoryId: string, bomRecipeId: string) =>
    get<BomVersion>(`${base(factoryId)}/by-recipe/${bomRecipeId}/current`),

  /** BOM 某历史日期的生效版本 (订单追溯). date: YYYY-MM-DD. */
  getEffectiveAt: (factoryId: string, bomRecipeId: string, date: string) =>
    get<BomVersion>(`${base(factoryId)}/by-recipe/${bomRecipeId}/effective-at`, {
      params: { date },
    }),

  /** 提交审批 (DRAFT → PENDING_APPROVAL). 可关联 ECN. */
  submitForApproval: (
    factoryId: string,
    versionId: string,
    req?: SubmitBomVersionRequest,
  ) => post<BomVersion>(`${base(factoryId)}/${versionId}/submit`, req ?? {}),

  /** 审批通过 (PENDING/DRAFT → APPROVED, effective_from=today, 旧版自动 OBSOLETE). */
  approve: (factoryId: string, versionId: string, req: ApproveBomVersionRequest) =>
    post<BomVersion>(`${base(factoryId)}/${versionId}/approve`, req),

  /** 拒绝 (PENDING_APPROVAL → REJECTED, terminal). */
  reject: (factoryId: string, versionId: string, req: RejectBomVersionRequest) =>
    post<BomVersion>(`${base(factoryId)}/${versionId}/reject`, req),
};
