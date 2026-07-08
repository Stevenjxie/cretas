/**
 * 工序管理类型定义
 *
 * 对接后端:
 * - WorkProcessController (/api/mobile/{factoryId}/work-processes)
 * - ProductWorkProcessController (/api/mobile/{factoryId}/product-work-processes)
 *
 * Track D2 — M-WP-1 / M-WP-2 (六扇门第四次会议)
 */

/**
 * 工序类别选项
 *
 * 后端 WorkProcess.processCategory 为 VARCHAR(50) free-text, 前端用 enum-like 常量约束。
 * 客户原话 (六扇门第四次 line 88-90): 拆包/分割=前处理, 卤制=加工, 装筐=包装。
 */
export const WORK_PROCESS_CATEGORIES = [
  { value: 'PRE_PROCESS', label: '前处理' },
  { value: 'PROCESSING', label: '加工' },
  { value: 'PACKAGING', label: '包装' },
  { value: 'QUALITY', label: '质检' },
] as const;

export type WorkProcessCategory = (typeof WORK_PROCESS_CATEGORIES)[number]['value'];

/**
 * 产出单位常用选项 (客户原话 line 78 "产出单位是工金", 也可选 件/份/kg)
 */
export const WORK_PROCESS_UNIT_OPTIONS = ['工金', '件', '份', 'kg', '盒', '包'] as const;

/**
 * 工序定义
 */
export interface WorkProcess {
  id: string;
  factoryId?: string;
  processName: string;
  processCategory?: string;
  description?: string;
  /** 产出单位, 默认 'kg' */
  unit: string;
  /** 预估工时 (分钟) */
  estimatedMinutes?: number;
  sortOrder?: number;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * 创建工序请求
 */
export interface CreateWorkProcessRequest {
  processName: string;
  processCategory?: string;
  description?: string;
  unit?: string;
  estimatedMinutes?: number;
  sortOrder?: number;
}

/**
 * 更新工序请求 (所有字段可选)
 */
export interface UpdateWorkProcessRequest extends Partial<CreateWorkProcessRequest> {
  isActive?: boolean;
}

/**
 * 批量排序更新
 */
export interface WorkProcessSortOrderUpdate {
  id: string;
  sortOrder: number;
}

/**
 * 产品 × 工序绑定 (模板)
 */
export interface ProductWorkProcess {
  id: number;
  productTypeId: string;
  workProcessId: string;
  /** 工序在产品流程中的顺序 (0..N) */
  processOrder?: number;
  /** 单位覆写 (空时用 WorkProcess.unit) */
  unitOverride?: string;
  /** 预估工时覆写 (分钟) */
  estimatedMinutesOverride?: number;
  isActive: boolean;
  /**
   * Wave2 可配置报工粒度: 是否需报工。
   * true (默认) = 逐道报; false = 该工序免报 (spawn 跳过, 不生成报工任务, 配置保留供溯源)。
   */
  reportingRequired?: boolean;
  /** 是否允许本道工序从多个上游批次混批投料。 */
  allowMultipleUpstreamSources?: boolean;
  /** 后端 join 提供的只读字段 */
  processName?: string;
  processCategory?: string;
  defaultUnit?: string;
  defaultEstimatedMinutes?: number;
  createdAt?: string;
}

/**
 * 创建产品 × 工序绑定请求
 */
export interface CreateProductWorkProcessRequest {
  productTypeId: string;
  workProcessId: string;
  processOrder?: number;
  unitOverride?: string;
  estimatedMinutesOverride?: number;
  /** Wave2: 是否需报工 (省略 → 后端默认 true 逐道报; false → 免报)。 */
  reportingRequired?: boolean;
  /** 是否允许本道工序从多个上游批次混批投料。 */
  allowMultipleUpstreamSources?: boolean;
}

/**
 * 更新产品 × 工序绑定请求
 */
export interface UpdateProductWorkProcessRequest extends Partial<CreateProductWorkProcessRequest> {
  isActive?: boolean;
}

/**
 * 批量排序 (产品 × 工序绑定)
 */
export interface ProductWorkProcessBatchSortRequest {
  items: Array<{ id: number; processOrder: number }>;
}
