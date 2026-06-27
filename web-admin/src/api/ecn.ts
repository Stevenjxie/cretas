/**
 * ECN (工程变更通知) API — Sprint 6 W4-C extended.
 *
 * 对应后端 EcnController (M-BOM-VER-1, Sprint 3 Track-H ship PR #694).
 * Base: /api/mobile/{factoryId}/bom/ecns (request baseURL='/api/mobile').
 *
 * Sprint 6 W4-C 加: paginated list + impact report. ECN list 分页查询 + 影响报告
 * dialog + cascade BomVersion 审批 UI.
 */
import { get, post } from './request';

/** ECN 5-reason 枚举 (Sprint 3 Track-H ship). */
export type EcnReason =
  | 'CUSTOMER_REQUEST'
  | 'MATERIAL_DISCONTINUED'
  | 'COST_OPTIMIZATION'
  | 'QUALITY_DEFECT'
  | 'PROCESS_IMPROVEMENT';

/** ECN 状态机. */
export type EcnStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'EFFECTIVE';

export interface EngineeringChangeNotice {
  id: string;
  factoryId: string;
  /** auto-generated ECN-YYYY-NNNN. */
  ecnNumber: string;
  bomRecipeId: string;
  reason: EcnReason;
  status: EcnStatus;
  title: string;
  description?: string;
  /** 计划生效日期 (YYYY-MM-DD). */
  effectiveDate?: string;
  createdBy?: number;
  approvedBy?: number;
  approvedAt?: string;
  rejectionReason?: string;
  /** notify_roles JSON array. */
  notifyRoles?: string[];
  fromVersion?: number | string | null;
  toVersion?: number | string | null;
  changeContext?: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
}

export interface EcnCreateRequest {
  bomRecipeId: string;
  reason: EcnReason;
  title: string;
  description?: string;
  effectiveDate?: string;
  changeContext?: Record<string, unknown>;
  createdBy: number;
}

export interface EcnImpactReport {
  bomRecipeId: string;
  /** 影响的物料数. */
  impactedMaterialCount: number;
  /** 影响的成品 SKU 数. */
  impactedSkuCount: number;
  /** 影响的下游 PO 数 (未结). */
  impactedPurchaseOrderCount?: number;
  /** 估算成本差异. */
  estimatedCostDelta?: number;
  details?: Record<string, unknown>;
}

const base = (factoryId: string) => `/${factoryId}/bom/ecns`;

/** Sprint 6 W4-C — Paginated ECN list response. */
export interface EcnPageResponse {
  content: EngineeringChangeNotice[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  number: number; // 0-based alias (compat)
  empty: boolean;
}

/** Sprint 6 W4-C — Paginated list filter params. */
export interface EcnListParams {
  page?: number;
  size?: number;
  sortBy?: string;
  sortDirection?: 'ASC' | 'DESC';
  status?: EcnStatus;
  reason?: EcnReason;
  bomRecipeId?: string;
}

export const ecnApi = {
  /** Sprint 6 W4-C — Paginated list (默认 page=1 size=20). */
  list: (factoryId: string, params?: EcnListParams) =>
    get<EcnPageResponse>(base(factoryId), { params }),

  /** 创建 ECN DRAFT (auto generateEcnNumber ECN-YYYY-NNNN). */
  create: (factoryId: string, req: EcnCreateRequest) =>
    post<EngineeringChangeNotice>(base(factoryId), req),

  /** ECN 详情. */
  getById: (factoryId: string, ecnId: string) =>
    get<EngineeringChangeNotice>(`${base(factoryId)}/${ecnId}`),

  /** 提交审批 (DRAFT → SUBMITTED). */
  submitForApproval: (factoryId: string, ecnId: string) =>
    post<EngineeringChangeNotice>(`${base(factoryId)}/${ecnId}/submit`, {}),

  /** 审批通过 (cascade approve BomVersion DRAFT). */
  approve: (factoryId: string, ecnId: string, approverId: number) =>
    post<EngineeringChangeNotice>(`${base(factoryId)}/${ecnId}/approve`, { approverId }),

  /** 拒绝 (cascade reject BomVersion DRAFT). */
  reject: (
    factoryId: string,
    ecnId: string,
    approverId: number,
    rejectionReason: string,
  ) =>
    post<EngineeringChangeNotice>(`${base(factoryId)}/${ecnId}/reject`, {
      approverId,
      rejectionReason,
    }),

  /** 计算 ECN 影响范围 (read-only, 不创建 ECN). */
  calculateImpact: (
    factoryId: string,
    bomRecipeId: string,
    changeContext?: Record<string, unknown>,
  ) =>
    post<EcnImpactReport>(`${base(factoryId)}/calculate-impact`, {
      bomRecipeId,
      changeContext: changeContext ?? {},
    }),
};

/** UI 友好的中文 label. */
export const ECN_REASON_LABEL: Record<EcnReason, string> = {
  CUSTOMER_REQUEST: '客户要求',
  MATERIAL_DISCONTINUED: '物料停产',
  COST_OPTIMIZATION: '成本优化',
  QUALITY_DEFECT: '质量缺陷',
  PROCESS_IMPROVEMENT: '工艺改进',
};

export const ECN_STATUS_LABEL: Record<EcnStatus, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审批',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  EFFECTIVE: '已生效',
};
