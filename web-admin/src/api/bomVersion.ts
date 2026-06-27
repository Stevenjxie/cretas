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
  ecnReasonDetail?: string;
}

/** Sprint 6 W4-C — ECN reason 枚举 (mirror of Java EngineeringChangeNotice.EcnReason). */
export type BomBatchEcnReason =
  | 'CUSTOMER_REQUEST'
  | 'MATERIAL_DISCONTINUED'
  | 'COST_OPTIMIZATION'
  | 'QUALITY_DEFECT'
  | 'PROCESS_IMPROVEMENT';

/** Sprint 6 W4-C — Common fields for all 4 batch operations. */
interface BatchCommon {
  /** Either allInFactory=true OR explicit recipeIds list. */
  allInFactory?: boolean;
  recipeIds?: string[];
  ecnReason: BomBatchEcnReason;
  ecnReasonDetail?: string;
  /** ISO date YYYY-MM-DD. */
  effectiveDate?: string;
  createdBy: number;
}

/** Sprint 6 W4-C — 批量修改请求 (多 BOM 同一物料 standardQuantity 改). */
export interface BatchModifyRequest extends BatchCommon {
  materialId: string;
  newStandardQuantity: number;
  newUnit?: string;
}

/** Sprint 6 W4-C — 批量替换请求 (物料 A → B 跨多 BOM). */
export interface BatchReplaceRequest extends BatchCommon {
  oldMaterialId: string;
  newMaterialId: string;
  /** 默认 true: 保留原 quantity / unit, 仅换 material 引用. */
  preserveQuantity?: boolean;
}

/** Sprint 6 W4-C — 批量删除请求. */
export interface BatchDeleteRequest extends BatchCommon {
  materialId: string;
}

/** Sprint 6 W4-C — 批量新增请求. */
export interface BatchAddRequest extends BatchCommon {
  materialId: string;
  standardQuantity: number;
  yieldRate?: number;
  unit: string;
  materialCategory?: string;
  /** 默认 true: recipe 已含此 material 跳过. */
  skipIfAlreadyPresent?: boolean;
}

/** Sprint 6 W4-C — 批量操作返回 (含 ECN DRAFT id + N BomVersion DRAFT ids). */
export interface BatchResult {
  operation: 'MODIFY' | 'REPLACE' | 'DELETE' | 'ADD';
  ecnId: string;
  ecnNumber: string;
  affectedRecipeIds: string[];
  /** recipeId → versionId. */
  draftVersionIds: Record<string, string>;
  /** recipeId → 跳过原因 (e.g. "recipe contains no item for material XXX"). */
  skippedRecipes: Record<string, string>;
  warnings: string[];
}

/** Sprint 6 W4-C — BomRecipe (subset used by reverse query). */
export interface BomRecipe {
  id: string;
  factoryId: string;
  recipeCode?: string;
  productTypeId?: string;
  productName?: string;
  version?: number;
  isCurrent?: boolean;
  unit?: string;
  status?: string;
}

/** Sprint 6 W4-C — Per-item usage row of reverse query. */
export interface BomItemUsage {
  recipeId: string;
  recipeCode?: string;
  productTypeId?: string;
  productName?: string;
  version?: number;
  isCurrent?: boolean;
  itemId?: number;
  standardQuantity?: number;
  yieldRate?: number;
  actualQuantity?: number;
  unit?: string;
  materialCategory?: string;
  isOptional?: boolean;
  substituteGroup?: string;
  itemCost?: number;
  unitPrice?: number;
}

const base = (factoryId: string) => `/${factoryId}/bom/versions`;
const batchBase = (factoryId: string) => `/${factoryId}/bom/batch`;
const reverseBase = (factoryId: string) => `/${factoryId}/bom/reverse-query`;

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

/**
 * Sprint 6 W4-C — 批量操作 API.
 *
 * 4 批量操作 (modify / replace / delete / add) 每个产生 1 ECN DRAFT + N BomVersion DRAFT.
 * 不直接 mutate BomRecipe / BomRecipeItem — 走 ECN 审批后才 apply.
 */
export const bomBatchApi = {
  /** 批量修改 — 多 BOM 同一物料 standardQuantity 全改. */
  batchModify: (factoryId: string, req: BatchModifyRequest) =>
    post<BatchResult>(`${batchBase(factoryId)}/modify`, req),

  /** 批量替换 — 物料 A → B 跨多 BOM. */
  batchReplace: (factoryId: string, req: BatchReplaceRequest) =>
    post<BatchResult>(`${batchBase(factoryId)}/replace`, req),

  /** 批量删除 — 多 BOM 同一物料 item 全删. */
  batchDelete: (factoryId: string, req: BatchDeleteRequest) =>
    post<BatchResult>(`${batchBase(factoryId)}/delete`, req),

  /** 批量新增 — 多 BOM 同时加一物料 item. */
  batchAdd: (factoryId: string, req: BatchAddRequest) =>
    post<BatchResult>(`${batchBase(factoryId)}/add`, req),
};

/**
 * Sprint 6 W4-C — 反查 API.
 *
 * 物料 → BOM 反查: 哪些 BOM 用了某物料 + 替换影响分析.
 */
export const bomReverseQueryApi = {
  /** 哪些 BOM 用了这个物料 (distinct BomRecipes). */
  findRecipesByMaterial: (factoryId: string, materialId: string) =>
    get<BomRecipe[]>(`${reverseBase(factoryId)}/by-material/${materialId}`),

  /** 哪些 BOM 用了这个物料 — 含 BomRecipeItem 用量 / 出成率 / 成本明细. */
  findUsageByMaterial: (factoryId: string, materialId: string) =>
    get<BomItemUsage[]>(`${reverseBase(factoryId)}/by-material/${materialId}/usage`),
};
