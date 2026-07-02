/**
 * 工厂仓库管理 API client.
 *
 * Backend: FactoryWarehouseController @ /api/mobile/{factoryId}/factory/warehouses
 * Role gate (写操作): factory_super_admin / permission_admin
 *
 * @since 2026-06-16 (超级管理员仓库管理 UI)
 */
import request from './request'

// ==================== Types ====================

export type WarehouseType =
  | 'LOGISTICS'
  | 'WORKSHOP'
  | 'OTHER'
  | 'RAW'
  | 'WIP'
  | 'FINISHED'
  | 'LINESIDE'
  | 'RETURNS'
  | 'SCRAP'
  | 'TEMP'
  | 'QC'
  | 'OUTSOURCE'
  | 'TRANSFER'
  | 'SALTED'
  | 'RD'

export const WAREHOUSE_TYPE_LABELS: Record<WarehouseType, string> = {
  LOGISTICS: '物流仓',
  WORKSHOP: '鲜棉仓/车间',
  OTHER: '其他',
  RAW: '原料仓',
  WIP: '半成品仓',
  FINISHED: '成品仓',
  LINESIDE: '线边仓',
  RETURNS: '退货仓',
  SCRAP: '废料仓',
  TEMP: '暂存仓',
  QC: '质检仓',
  OUTSOURCE: '委外仓',
  TRANSFER: '调拨在途仓',
  SALTED: '盐化仓',
  RD: '研发/中试库',
}

export interface FactoryWarehouse {
  id: string
  factoryId: string
  code: string
  name: string
  type: WarehouseType
  isActive: boolean
  remarks?: string
  createdAt?: string
  updatedAt?: string
}

export interface CreateWarehouseBody {
  code: string
  name: string
  type: WarehouseType
  isActive?: boolean
  remarks?: string
}

export interface UpdateWarehouseBody {
  name?: string
  type?: WarehouseType
  isActive?: boolean
  remarks?: string
}

export interface WarehouseSpec {
  code: string
  name: string
  type: WarehouseType
}

export interface ApplyTemplateBody {
  templateKey?: string | null
  warehouses?: WarehouseSpec[]
}

export interface ApplyTemplateResult {
  created: number
  skipped: number
  items: FactoryWarehouse[]
}

// ==================== API functions ====================

export function listWarehouses(factoryId: string, type?: WarehouseType) {
  return request.get<any, { success: boolean; data: FactoryWarehouse[] }>(
    `/${factoryId}/factory/warehouses`,
    { params: type ? { type } : {} },
  )
}

export function createWarehouse(factoryId: string, body: CreateWarehouseBody) {
  return request.post<any, { success: boolean; data: FactoryWarehouse; message?: string }>(
    `/${factoryId}/factory/warehouses`,
    body,
  )
}

export function updateWarehouse(factoryId: string, id: string, body: UpdateWarehouseBody) {
  return request.put<any, { success: boolean; data: FactoryWarehouse; message?: string }>(
    `/${factoryId}/factory/warehouses/${id}`,
    body,
  )
}

export function deleteWarehouse(factoryId: string, id: string) {
  return request.delete<any, { success: boolean; message?: string }>(
    `/${factoryId}/factory/warehouses/${id}`,
  )
}

export function applyWarehouseTemplate(factoryId: string, body: ApplyTemplateBody) {
  return request.post<any, { success: boolean; data: ApplyTemplateResult; message?: string }>(
    `/${factoryId}/factory/warehouses/apply-template`,
    body,
  )
}

// ==================== 默认仓路由配置 (Layer 2) ====================
// Backend: FactoryWarehouseDefaultController @ /api/mobile/{factoryId}/factory/warehouse-defaults
// Role gate: factory_super_admin / permission_admin (backend @RequireRole)

/** 默认仓路由用途 — 镜像后端 WarehouseDefaultPurpose 枚举。 */
export type WarehouseDefaultPurpose =
  | 'LOGISTICS_DEFAULT'
  | 'WORKSHOP_DEFAULT'
  | 'RD_DEFAULT'
  | 'PURCHASE_INBOUND_DEFAULT'
  | 'SALES_OUTBOUND_DEFAULT'
  | 'PRODUCTION_RAW_DEFAULT'

export const WAREHOUSE_DEFAULT_PURPOSE_LABELS: Record<WarehouseDefaultPurpose, string> = {
  // 用途拆分 (2026-07-02): LOGISTICS_DEFAULT 原本三语义共用, 已拆出采购入库/销售出货/生产领料三个独立用途。
  LOGISTICS_DEFAULT: '调拨 / 退货 / 其余默认仓',
  WORKSHOP_DEFAULT: '生产成品 / 报工消耗默认仓',
  RD_DEFAULT: '研发 / 试制默认仓',
  PURCHASE_INBOUND_DEFAULT: '采购入库默认仓',
  SALES_OUTBOUND_DEFAULT: '销售出货默认仓',
  PRODUCTION_RAW_DEFAULT: '生产领料默认仓',
}

export interface FactoryWarehouseDefault {
  id: string
  factoryId: string
  purpose: WarehouseDefaultPurpose
  warehouseId: string
  createdAt?: string
  updatedAt?: string
}

export interface WarehouseDefaultSpec {
  purpose: WarehouseDefaultPurpose
  warehouseId: string
}

export interface UpsertWarehouseDefaultsBody {
  /** 单条（与 defaults 二选一，defaults 优先） */
  purpose?: WarehouseDefaultPurpose
  warehouseId?: string
  /** 批量 */
  defaults?: WarehouseDefaultSpec[]
}

export function getWarehouseDefaults(factoryId: string) {
  return request.get<any, { success: boolean; data: FactoryWarehouseDefault[]; message?: string }>(
    `/${factoryId}/factory/warehouse-defaults`,
  )
}

export function saveWarehouseDefaults(factoryId: string, defaults: WarehouseDefaultSpec[]) {
  return request.put<any, { success: boolean; data: FactoryWarehouseDefault[]; message?: string }>(
    `/${factoryId}/factory/warehouse-defaults`,
    { defaults },
  )
}

/**
 * 清除某 purpose 的默认仓配置 → 回退系统默认仓 (WarehouseResolver 硬编码 code)。
 * 幂等：无配置时后端 no-op 仍返回成功。
 */
export function deleteWarehouseDefault(factoryId: string, purpose: WarehouseDefaultPurpose) {
  return request.delete<any, { success: boolean; message?: string }>(
    `/${factoryId}/factory/warehouse-defaults/${purpose}`,
  )
}

// ==================== Template presets (frontend constants) ====================

/** 六扇门含盐化代加工 7 仓预设（与后端 LIUSHANMEN_SALTED 对应） */
export const LIUSHANMEN_SALTED_TEMPLATE: WarehouseSpec[] = [
  { code: 'WH-LOG',    name: '物流仓',      type: 'LOGISTICS' },
  { code: 'WH-RAW',    name: '原料仓',      type: 'RAW' },
  { code: 'WH-WIP',    name: '半成品仓',    type: 'WIP' },
  { code: 'WH-FG',     name: '成品仓',      type: 'FINISHED' },
  { code: 'WH-WKS',    name: '鲜棉仓',      type: 'WORKSHOP' },
  { code: 'WH-RD',     name: '研发中试库',  type: 'RD' },
  { code: 'SALTED-01', name: '盐化仓代加工', type: 'SALTED' },
]

/** 标准仓型默认 code + name（自选仓型模板用） */
export const WAREHOUSE_TYPE_DEFAULTS: Record<WarehouseType, { code: string; name: string }> = {
  LOGISTICS: { code: 'WH-LOG',    name: '物流仓' },
  WORKSHOP:  { code: 'WH-WKS',    name: '鲜棉仓/车间' },
  RAW:       { code: 'WH-RAW',    name: '原料仓' },
  WIP:       { code: 'WH-WIP',    name: '半成品仓' },
  FINISHED:  { code: 'WH-FG',     name: '成品仓' },
  LINESIDE:  { code: 'WH-LS',     name: '线边仓' },
  RETURNS:   { code: 'WH-RET',    name: '退货仓' },
  SCRAP:     { code: 'WH-SCR',    name: '废料仓' },
  TEMP:      { code: 'WH-TEMP',   name: '暂存仓' },
  QC:        { code: 'WH-QC',     name: '质检仓' },
  OUTSOURCE: { code: 'WH-OUT',    name: '委外仓' },
  TRANSFER:  { code: 'WH-TRF',    name: '调拨在途仓' },
  SALTED:    { code: 'SALTED-01', name: '盐化仓' },
  RD:        { code: 'WH-RD',     name: '研发/中试库' },
  OTHER:     { code: 'WH-OTHER',  name: '其他仓' },
}
