/**
 * Canvas Configuration System — TypeScript 类型定义
 */

// ========== 合并后有效配置 (API 响应) ==========

export interface EffectiveModuleConfig {
  moduleCode: string
  moduleName: string
  enabled: boolean
  fields: EffectiveField[]
  groups: FieldGroup[]
  workflowStates: WorkflowState[]
  workflowTransitions: WorkflowTransition[]
  workflowOptions: Record<string, unknown>
  customLabels: Record<string, string>
  renderingMode: 'LEGACY' | 'DYNAMIC' | 'DUAL' | 'CANVAS'
}

export interface EffectiveField {
  code: string
  /** Backend alias for `code`; some endpoints (canvas-editor effective config) emit
   *  this as the primary identifier. Keep both for compatibility. */
  fieldCode?: string
  label: string
  type: FieldType
  required: boolean
  visible: boolean
  readonly: boolean
  defaultValue: unknown
  options: FieldOption[] | null
  group: string
  order: number
  extra: Record<string, unknown>
  visibleWhen?: string
  computedWhen?: string
  source?: string
}

export type FieldType =
  | 'string'
  | 'text'
  | 'textarea'
  | 'longtext'
  | 'decimal'
  | 'integer'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'select'
  | 'reference'
  | 'json_array'
  | 'line_items'
  | 'attachment'
  | 'sub_table'

export interface FieldOption {
  value: string | number
  label: string
}

export interface FieldGroup {
  code: string
  label: string
  order: number
  visible: boolean
}

export interface WorkflowState {
  code: string
  label: string
  enabled: boolean
  isInitial: boolean
  isFinal: boolean
  tagType: string
}

export interface WorkflowTransition {
  from: string
  to: string
  action: string
  label: string
  buttonType: string
  enabled: boolean
  condition: string | null
  allowedRoles: string[]
  // R40 BUG-5: when false, transition is auto-triggered by upstream event;
  // FE must NOT render manual button (no backend endpoint, would 404).
  // Default true (backward compat for existing schemas without flag).
  manualTrigger?: boolean
}

// ========== 模块摘要 ==========

export interface ModuleSummary {
  moduleCode: string
  moduleName: string
  moduleCategory: string
  enabled: boolean
  renderingMode: string
}

// ========== 写入 DTO ==========

export interface ModuleConfigDTO {
  enabled?: boolean
  fieldConfig?: Record<string, unknown>
  workflowConfig?: Record<string, unknown>
  validationConfig?: Record<string, unknown>
  permissionConfig?: Record<string, unknown>
  layoutConfig?: Record<string, unknown>
  customLabels?: Record<string, unknown>
  renderingMode?: string
}

export interface FieldConfigDTO {
  visible?: boolean
  required?: boolean
  defaultValue?: unknown
  options?: unknown
  label?: string
}

// ========== Reference 配置 ==========

export interface ReferenceConfig {
  entity: string
  displayField: string
  valueField: string
  searchFields?: string[]
  filter?: Record<string, unknown>
  apiEndpoint: string
  /**
   * C-6 reactive default: 选中 entity 后写回 shadow 字段的映射.
   * key   = entity 响应字段名 (如 level1PerLevel2)
   * value = 写回 row/formData 的 shadow 键 (推荐 `_` 前缀, 如 _level1PerLevel2)
   * 配合 `computed`/`visibleWhen` SpEL 表达式实现 "选 X → 派生 Y" 模式.
   * 见 docs/superpowers/specs/2026-05-09-canvas-c6-reactive-default-framework.md §3.1
   */
  projectFields?: Record<string, string>
}

// ========== 行项目 Schema ==========

export interface ItemSchemaField {
  code: string
  type: FieldType
  label: string
  required: boolean
  min?: number
  max?: number
  precision?: number
  options?: FieldOption[]
  /** SpEL 表达式 — 行内跨字段计算 (e.g. "quantity * unitPrice", "qty / _level1PerLevel2"). C-6 Task 3 起用 evaluateSpelValue 而非 split('*') toy parser. */
  computed?: string
  /** C-6: SpEL 表达式 — 行内字段显隐控制 (e.g. "_specification == '抄码'"). 与 EffectiveField.visibleWhen 语义一致. */
  visibleWhen?: string
  /** C-6: 行内 reference 字段配置 (与顶层 EffectiveField.extra.referenceConfig 对齐). LineItemsEditor 使用. */
  referenceConfig?: ReferenceConfig
  /** C-6: 行内字段默认值 (sentinel TODAY/NOW/YESTERDAY 或字面量). LineItemsEditor.addRow 使用. */
  defaultValue?: unknown
}

// ========== 模块 API 路径映射 ==========

// Maps moduleCode (spec convention snake_case) → actual REST endpoint prefix
// Endpoints match the Controller @RequestMapping values in backend.
// Fixed (Round 3 audit): was using hyphen-case that didn't match any real controller.
export const MODULE_API_PATHS: Record<string, string> = {
  sales_order: 'sales/orders',
  bom: 'bom/recipes',
  inbound: 'material-batches',
  outbound: 'material-batches',
  production_report: 'process-work-reporting',
  production_plan: 'production-plans',
  purchase_order: 'purchase/orders',
  quality_inspection: 'processing/quality/inspections',
  equipment: 'equipment',
  inventory: 'inventory',
  customer: 'customers',
  supplier: 'suppliers',
  finance_ar: 'finance/ar',
  finance_ap: 'finance/ap',
  hr_employee: 'users',
  transfer: 'transfers',
  traceability: 'traceability',
}
