<script setup lang="ts">
/**
 * 数据完整度查看页面
 * 展示各业务模块的数据填充率和字段级别完整度明细
 *
 * Data source: Python service POST /api/client-requirement/completeness/compute
 * Requires: asyncpg + PostgreSQL with the relevant entity tables
 * Fallback: Shows friendly "service unavailable" message instead of error
 */
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, DataAnalysis, Search, Plus, Edit, Delete } from '@element-plus/icons-vue'
import { useAuthStore } from '@/store/modules/auth'
import { pythonFetch } from '@/api/smartbi/common'

const authStore = useAuthStore()
const factoryId = computed(() => authStore.factoryId)
// Restaurant tenant detection — mirrors backend ``is_restaurant_tenant``
// helper. RESTAURANT + BRANCH route through restaurant analytical semantics;
// FACTORY / HEADQUARTERS / CENTRAL_KITCHEN stay on manufacturing path.
const isRestaurantTenant = computed(() => {
  const t = authStore.factoryType
  return t === 'RESTAURANT' || t === 'BRANCH'
})
const loading = ref(false)

// Service availability state
const serviceAvailable = ref(true)
const errorMessage = ref('')

interface FieldCompleteness {
  [field: string]: number
}

interface CompletenessMetadata {
  minDate?: string | null
  maxDate?: string | null
  uniqueProducts?: number
  [key: string]: unknown
}

interface CompletenessItem {
  entityType: string
  overallCompleteness: number
  totalRecords: number
  fieldCompleteness: FieldCompleteness
  // POS entities (POS_TRANSACTION / POS_ITEM) include optional metadata
  // such as date range and unique product counts. Manufacturing entities
  // omit this field.
  metadata?: CompletenessMetadata | null
}

// --- Feature 2B: Custom Validation Rules ---
interface ValidationRule {
  id: string
  entityType: string
  fieldName: string
  ruleType: 'required' | 'numeric' | 'date_format' | 'min_length' | 'regex'
  ruleValue?: string
  enabled: boolean
  label: string
}

const RULE_TYPE_OPTIONS = [
  { value: 'required', label: '必填' },
  { value: 'numeric', label: '数值' },
  { value: 'date_format', label: '日期格式' },
  { value: 'min_length', label: '最小长度' },
  { value: 'regex', label: '正则' },
] as const

// Field options per entity type — mirrors the Python backend ENTITY_FIELD_MAP
// + POS_ENTITY_FIELD_MAP.
const ENTITY_FIELD_OPTIONS: Record<string, string[]> = {
  PROCESSING_BATCH: [
    'batch_number', 'product_name', 'planned_quantity', 'actual_quantity',
    'good_quantity', 'defect_quantity', 'yield_rate', 'material_cost',
    'labor_cost', 'equipment_cost', 'total_cost', 'unit_cost',
    'equipment_id', 'supervisor_id', 'worker_count', 'start_time', 'end_time'
  ],
  WORK_SESSION: [
    'user_id', 'work_type_id', 'start_time', 'end_time',
    'break_minutes', 'actual_work_minutes', 'hourly_rate', 'completed_time'
  ],
  MATERIAL_BATCH: [
    'batch_number', 'material_type_id', 'quantity', 'unit_price',
    'supplier_id', 'expiry_date', 'storage_location'
  ],
  QUALITY_INSPECTION: [
    'batch_id', 'inspector_id', 'inspection_type',
    'result', 'defect_count', 'defect_type'
  ],
  EQUIPMENT: [
    'equipment_name', 'equipment_type', 'operating_hours',
    'last_maintenance_date', 'status'
  ],
  // Restaurant POS entities (smartbi_db silver layer)
  POS_TRANSACTION: [
    'gross_amount', 'actual_receive', 'customer_count',
    'order_type', 'meal_period', 'store_id', 'staff_id',
  ],
  POS_ITEM: [
    'qty', 'unit_price', 'amount', 'return_qty',
  ],
}

// POS entity types — opt-in for restaurant tenants only.
const POS_ENTITY_TYPES = ['POS_TRANSACTION', 'POS_ITEM'] as const

const completenessData = ref<CompletenessItem[]>([])
const selectedModule = ref<CompletenessItem | null>(null)

// --- Feature 2A: Drill-to-Detail state ---
const drillDrawerVisible = ref(false)
const drillField = ref<{ field: string; fieldRaw: string; completeness: number } | null>(null)
const drillEntityType = ref('')

// --- Feature 2B: Custom Validation Rules state ---
const validationRules = ref<ValidationRule[]>([])
const ruleDialogVisible = ref(false)
const editingRule = ref<ValidationRule | null>(null)
const ruleForm = ref<{
  entityType: string
  fieldName: string
  ruleType: 'required' | 'numeric' | 'date_format' | 'min_length' | 'regex'
  ruleValue: string
  enabled: boolean
  label: string
}>({
  entityType: '',
  fieldName: '',
  ruleType: 'required',
  ruleValue: '',
  enabled: true,
  label: '',
})

const fieldTableData = computed(() => {
  if (!selectedModule.value?.fieldCompleteness) return []
  return Object.entries(selectedModule.value.fieldCompleteness)
    .map(([field, val]) => ({
      field: fieldLabel(field),
      fieldRaw: field,
      completeness: val as number
    }))
    .sort((a, b) => a.completeness - b.completeness)
})

// Overall average completeness across all modules
const overallAverage = computed(() => {
  if (completenessData.value.length === 0) return 0
  const sum = completenessData.value.reduce((acc, item) => acc + item.overallCompleteness, 0)
  return Math.round(sum / completenessData.value.length * 10) / 10
})

function entityLabel(type: string): string {
  const map: Record<string, string> = {
    PROCESSING_BATCH: '生产批次',
    WORK_SESSION: '工时记录',
    MATERIAL_BATCH: '物料批次',
    QUALITY_INSPECTION: '质量检验',
    EQUIPMENT: '设备管理',
    // POS entities (餐饮)
    POS_TRANSACTION: 'POS 订单',
    POS_ITEM: 'POS 菜品明细',
  }
  return map[type] || type
}

function fieldLabel(field: string): string {
  const map: Record<string, string> = {
    batch_number: '批次号',
    product_name: '产品名称',
    planned_quantity: '计划数量',
    actual_quantity: '实际数量',
    good_quantity: '合格数量',
    defect_quantity: '缺陷数量',
    yield_rate: '良品率',
    material_cost: '物料成本',
    labor_cost: '人工成本',
    equipment_cost: '设备成本',
    total_cost: '总成本',
    unit_cost: '单位成本',
    equipment_id: '设备编号',
    supervisor_id: '主管编号',
    worker_count: '工人数量',
    start_time: '开始时间',
    end_time: '结束时间',
    completed_time: '完成时间',
    user_id: '用户编号',
    work_type_id: '工种编号',
    break_minutes: '休息时长',
    actual_work_minutes: '实际工时',
    hourly_rate: '时薪',
    material_type_id: '物料类型',
    quantity: '数量',
    unit_price: '单价',
    supplier_id: '供应商',
    expiry_date: '过期日期',
    storage_location: '存储位置',
    batch_id: '批次编号',
    inspector_id: '检验员',
    inspection_type: '检验类型',
    result: '检验结果',
    defect_count: '缺陷数',
    defect_type: '缺陷类型',
    equipment_name: '设备名称',
    equipment_type: '设备类型',
    operating_hours: '运行时长',
    last_maintenance_date: '上次维护日期',
    status: '状态',
    // POS entity fields
    gross_amount: '总金额',
    actual_receive: '实收金额',
    customer_count: '人数',
    order_type: '订单类型',
    meal_period: '餐段',
    store_id: '门店',
    staff_id: '服务员',
    qty: '数量',
    amount: '金额',
    return_qty: '退菜数量',
  }
  return map[field] || field.replace(/_/g, ' ')
}

function getScoreClass(score: number): string {
  if (score >= 75) return 'score-good'
  if (score >= 50) return 'score-medium'
  return 'score-low'
}

/**
 * Format a POS entity's metadata into a short subtitle line shown under the
 * record count on each module card. Returns empty string for entities with
 * no metadata so the template can v-if it cleanly.
 */
function formatModuleMetadata(item: CompletenessItem): string {
  const md = item.metadata
  if (!md) return ''
  const parts: string[] = []
  if (md.minDate && md.maxDate) {
    // Both present — strip time component if it's a full ISO datetime.
    const fmt = (d: string) => d.length >= 10 ? d.slice(0, 10) : d
    parts.push(`${fmt(md.minDate)} ~ ${fmt(md.maxDate)}`)
  }
  if (typeof md.uniqueProducts === 'number' && md.uniqueProducts > 0) {
    parts.push(`${md.uniqueProducts} 个菜品`)
  }
  return parts.join(' · ')
}

// Centralized score color mapping — used by progress bars, score text, and module card borders
const SCORE_COLORS = { good: 'var(--el-color-success, #10b981)', medium: 'var(--el-color-warning, #f59e0b)', low: 'var(--el-color-danger, #ef4444)' } as const
function getScoreColor(score: number): string {
  if (score >= 75) return SCORE_COLORS.good
  if (score >= 50) return SCORE_COLORS.medium
  return SCORE_COLORS.low
}

function selectModule(item: CompletenessItem) {
  selectedModule.value = item
}

let abortController: AbortController | null = null;

async function loadData() {
  if (!factoryId.value) {
    errorMessage.value = '无法获取工厂信息，请重新登录'
    return
  }
  if (abortController) abortController.abort();
  abortController = new AbortController();
  loading.value = true
  errorMessage.value = ''
  serviceAvailable.value = true

  // Restaurant tenants: request only POS entity types (manufacturing tables
  // are empty for restaurants — sending them would clutter the UI with 0-row
  // cards). Factory tenants: omit entity_types so the backend computes the
  // full manufacturing set by default.
  const body: Record<string, unknown> = { factory_id: factoryId.value }
  if (isRestaurantTenant.value) {
    body.entity_types = [...POS_ENTITY_TYPES]
  }

  try {
    const json = await pythonFetch('/api/client-requirement/completeness/compute', {
      method: 'POST',
      body: JSON.stringify(body),
      signal: abortController.signal
    }) as { success?: boolean; data?: CompletenessItem[] }

    if (json.success) {
      completenessData.value = json.data || []
      if (completenessData.value.length > 0 && !selectedModule.value) {
        selectedModule.value = completenessData.value[0]
      }
    } else {
      completenessData.value = []
      ElMessage.error('加载完整度数据失败')
    }
  } catch (e: unknown) {
    console.warn('Load completeness failed:', e)
    const errMsg = e instanceof Error ? e.message : String(e)

    // Check if this is a 404 or connection error (service not deployed)
    if (errMsg.includes('404') || errMsg.includes('Not Found') ||
        errMsg.includes('timed out') || errMsg.includes('Failed to fetch') ||
        errMsg.includes('NetworkError') || errMsg.includes('ECONNREFUSED')) {
      serviceAvailable.value = false
      errorMessage.value = ''
    } else {
      errorMessage.value = '加载完整度数据时发生错误'
    }
  } finally {
    loading.value = false
  }
}

// --- Feature 2A: Drill-to-Detail ---
const drillMissingCount = computed(() => {
  if (!drillField.value || !selectedModule.value) return 0
  const total = selectedModule.value.totalRecords
  return Math.round(total * (100 - drillField.value.completeness) / 100)
})

const drillFilledCount = computed(() => {
  if (!selectedModule.value) return 0
  return selectedModule.value.totalRecords - drillMissingCount.value
})

function handleFieldRowClick(row: { field: string; fieldRaw: string; completeness: number }) {
  if (row.completeness >= 100) return
  if (!selectedModule.value) return
  drillField.value = row
  drillEntityType.value = selectedModule.value.entityType
  drillDrawerVisible.value = true
}

function isFieldClickable(row: { completeness: number }): boolean {
  return row.completeness < 100
}

// --- Feature 2B: Custom Validation Rules ---
function rulesStorageKey(): string {
  return `cretas_completeness_rules_${factoryId.value || 'default'}`
}

function loadRules() {
  try {
    const raw = localStorage.getItem(rulesStorageKey())
    if (raw) {
      validationRules.value = JSON.parse(raw)
    }
  } catch (e) {
    console.warn('Failed to load validation rules from localStorage:', e)
    validationRules.value = []
  }
}

function saveRules() {
  try {
    localStorage.setItem(rulesStorageKey(), JSON.stringify(validationRules.value))
  } catch (e) {
    console.warn('Failed to save validation rules to localStorage:', e)
  }
}

const ruleFieldOptions = computed(() => {
  const entityType = ruleForm.value.entityType
  if (!entityType || !ENTITY_FIELD_OPTIONS[entityType]) return []
  return ENTITY_FIELD_OPTIONS[entityType].map(f => ({
    value: f,
    label: fieldLabel(f),
  }))
})

const showRuleValue = computed(() => {
  return ruleForm.value.ruleType === 'min_length' || ruleForm.value.ruleType === 'regex'
})

function generateRuleLabel(): string {
  const entity = entityLabel(ruleForm.value.entityType)
  const field = fieldLabel(ruleForm.value.fieldName)
  const ruleTypeName = RULE_TYPE_OPTIONS.find(r => r.value === ruleForm.value.ruleType)?.label || ruleForm.value.ruleType
  let label = `${entity} - ${field}: ${ruleTypeName}`
  if (showRuleValue.value && ruleForm.value.ruleValue) {
    label += `(${ruleForm.value.ruleValue})`
  }
  return label
}

function resetRuleForm() {
  ruleForm.value = {
    entityType: '',
    fieldName: '',
    ruleType: 'required',
    ruleValue: '',
    enabled: true,
    label: '',
  }
  editingRule.value = null
}

function handleAddRule() {
  resetRuleForm()
  ruleDialogVisible.value = true
}

function handleEditRule(rule: ValidationRule) {
  editingRule.value = rule
  ruleForm.value = {
    entityType: rule.entityType,
    fieldName: rule.fieldName,
    ruleType: rule.ruleType,
    ruleValue: rule.ruleValue || '',
    enabled: rule.enabled,
    label: rule.label,
  }
  ruleDialogVisible.value = true
}

function handleDeleteRule(rule: ValidationRule) {
  ElMessageBox.confirm(`确定删除规则 "${rule.label}" ?`, '确认删除', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    type: 'warning',
  }).then(() => {
    validationRules.value = validationRules.value.filter(r => r.id !== rule.id)
    saveRules()
    ElMessage.success('规则已删除')
  }).catch(() => {})
}

function handleToggleRule(rule: ValidationRule) {
  const idx = validationRules.value.findIndex(r => r.id === rule.id)
  if (idx !== -1) {
    validationRules.value[idx].enabled = rule.enabled
    saveRules()
  }
}

function handleSaveRule() {
  if (!ruleForm.value.entityType || !ruleForm.value.fieldName || !ruleForm.value.ruleType) {
    ElMessage.warning('请填写完整的规则信息')
    return
  }
  if (showRuleValue.value && !ruleForm.value.ruleValue) {
    ElMessage.warning('请输入约束值')
    return
  }

  const label = generateRuleLabel()

  if (editingRule.value) {
    // Update existing rule
    const idx = validationRules.value.findIndex(r => r.id === editingRule.value!.id)
    if (idx !== -1) {
      validationRules.value[idx] = {
        ...validationRules.value[idx],
        entityType: ruleForm.value.entityType,
        fieldName: ruleForm.value.fieldName,
        ruleType: ruleForm.value.ruleType,
        ruleValue: showRuleValue.value ? ruleForm.value.ruleValue : undefined,
        enabled: ruleForm.value.enabled,
        label,
      }
    }
  } else {
    // Add new rule
    validationRules.value.push({
      id: `rule_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
      entityType: ruleForm.value.entityType,
      fieldName: ruleForm.value.fieldName,
      ruleType: ruleForm.value.ruleType,
      ruleValue: showRuleValue.value ? ruleForm.value.ruleValue : undefined,
      enabled: ruleForm.value.enabled,
      label,
    })
  }

  const isEdit = !!editingRule.value
  saveRules()
  ruleDialogVisible.value = false
  resetRuleForm()
  ElMessage.success(isEdit ? '规则已更新' : '规则已添加')
}

/** Check if a field (by raw name) has any custom rules defined */
function fieldHasRule(fieldRaw: string): boolean {
  if (!selectedModule.value) return false
  return validationRules.value.some(
    r => r.entityType === selectedModule.value!.entityType && r.fieldName === fieldRaw
  )
}

/** Get rule type labels for a field */
function fieldRuleLabels(fieldRaw: string): string[] {
  if (!selectedModule.value) return []
  return validationRules.value
    .filter(r => r.entityType === selectedModule.value!.entityType && r.fieldName === fieldRaw && r.enabled)
    .map(r => RULE_TYPE_OPTIONS.find(opt => opt.value === r.ruleType)?.label || r.ruleType)
}

const enabledRuleCount = computed(() => validationRules.value.filter(r => r.enabled).length)

/**
 * Service-unavailable notice feature list — tenant-aware so restaurants see
 * POS entities and factories see manufacturing modules. Mirrors the entity
 * types that will be queried by loadData() for each tenant type.
 */
interface NoticeFeature { label: string; fieldCount: number }
const noticeFeatures = computed<NoticeFeature[]>(() => {
  const types = isRestaurantTenant.value
    ? [...POS_ENTITY_TYPES]
    : ['PROCESSING_BATCH', 'WORK_SESSION', 'MATERIAL_BATCH', 'QUALITY_INSPECTION', 'EQUIPMENT']
  return types.map(t => ({
    label: entityLabel(t),
    fieldCount: ENTITY_FIELD_OPTIONS[t]?.length ?? 0,
  }))
})

const ENTITY_TYPE_OPTIONS = computed(() => {
  // Filter by tenant type — restaurants only see POS entities, factories
  // only see manufacturing entities. Keeps the custom-validation-rule dialog
  // free of options that won't match any data row.
  const posSet = new Set<string>(POS_ENTITY_TYPES)
  const allowed = Object.keys(ENTITY_FIELD_OPTIONS).filter(key => {
    const isPos = posSet.has(key)
    return isRestaurantTenant.value ? isPos : !isPos
  })
  return allowed.map(key => ({
    value: key,
    label: entityLabel(key),
  }))
})

onMounted(() => {
  loadRules()
  loadData()
})

onUnmounted(() => {
  if (abortController) abortController.abort();
})
</script>

<template>
  <div class="data-completeness">
    <div class="page-header">
      <div class="header-left">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/smart-bi/dashboard' }">Smart BI</el-breadcrumb-item>
          <el-breadcrumb-item>数据完整度</el-breadcrumb-item>
        </el-breadcrumb>
        <h2>数据完整度</h2>
      </div>
      <el-button @click="loadData" :loading="loading" :icon="Refresh">刷新</el-button>
    </div>

    <!-- Service unavailable notice (friendly, non-error) -->
    <div v-if="!serviceAvailable && !loading" class="service-notice">
      <el-card class="notice-card">
        <div class="notice-content">
          <el-icon class="notice-icon" :size="48"><DataAnalysis /></el-icon>
          <h3>数据完整度分析</h3>
          <p class="notice-desc">
            此功能需要数据分析服务支持。当数据分析服务部署并连接数据库后，将自动计算各业务模块的数据填充率。
          </p>
          <div class="notice-features">
            <div
              v-for="feat in noticeFeatures"
              :key="feat.label"
              class="feature-item"
            >
              <span class="feature-dot good"></span>
              <span>{{ feat.label }} - {{ feat.fieldCount }}个字段完整度检测</span>
            </div>
          </div>
          <el-button type="primary" plain @click="loadData" :loading="loading" style="margin-top: 16px">
            重新检测
          </el-button>
        </div>
      </el-card>
    </div>

    <!-- Error state -->
    <el-alert
      v-if="errorMessage && !loading"
      type="warning"
      :title="errorMessage"
      show-icon
      closable
      style="margin-bottom: 16px"
      @close="errorMessage = ''"
    >
      <el-button size="small" type="primary" @click="loadData" style="margin-top: 8px">重试</el-button>
    </el-alert>

    <!-- Overall summary bar -->
    <div v-if="completenessData.length > 0" class="summary-bar">
      <div class="summary-item">
        <span class="summary-label">综合完整度</span>
        <span class="summary-value" :style="{ color: getScoreColor(overallAverage) }">
          {{ overallAverage }}%
        </span>
      </div>
      <div class="summary-item">
        <span class="summary-label">已检测模块</span>
        <span class="summary-value">{{ completenessData.length }}</span>
      </div>
      <div class="summary-item">
        <span class="summary-label">总数据量</span>
        <span class="summary-value">
          {{ completenessData.reduce((sum, item) => sum + item.totalRecords, 0) }} 条
        </span>
      </div>
    </div>

    <!-- Feature 2B: Custom Validation Rules (collapsible section) -->
    <div v-if="completenessData.length > 0" class="rules-section">
      <el-collapse>
        <el-collapse-item>
          <template #title>
            <div class="rules-collapse-title">
              <span>自定义校验规则</span>
              <el-badge :value="enabledRuleCount" :hidden="enabledRuleCount === 0" class="rules-badge" />
            </div>
          </template>
          <div class="rules-content">
            <div class="rules-toolbar">
              <el-button type="primary" :icon="Plus" size="small" @click="handleAddRule">添加规则</el-button>
              <span class="rules-hint">校验规则仅保存在本地浏览器中，不影响后端数据计算</span>
            </div>
            <el-table
              v-if="validationRules.length > 0"
              :data="validationRules"
              size="small"
              stripe
              :max-height="320"
            >
              <el-table-column label="模块" width="120">
                <template #default="{ row }">{{ entityLabel(row.entityType) }}</template>
              </el-table-column>
              <el-table-column label="字段" width="140">
                <template #default="{ row }">{{ fieldLabel(row.fieldName) }}</template>
              </el-table-column>
              <el-table-column label="规则类型" width="120">
                <template #default="{ row }">
                  <el-tag size="small" type="info">
                    {{ RULE_TYPE_OPTIONS.find(r => r.value === row.ruleType)?.label || row.ruleType }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="约束值" width="140">
                <template #default="{ row }">
                  <span v-if="row.ruleValue">{{ row.ruleValue }}</span>
                  <span v-else class="text-muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="启用" width="80" align="center">
                <template #default="{ row }">
                  <el-switch
                    v-model="row.enabled"
                    size="small"
                    @change="handleToggleRule(row)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" align="center">
                <template #default="{ row }">
                  <el-button link type="primary" :icon="Edit" size="small" @click="handleEditRule(row)" />
                  <el-button link type="danger" :icon="Delete" size="small" @click="handleDeleteRule(row)" />
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else description="暂无自定义规则" :image-size="60" />
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- Feature 2B: Add Rule Dialog -->
    <el-dialog
      v-model="ruleDialogVisible"
      :title="editingRule ? '编辑校验规则' : '添加校验规则'"
      width="520px"
      destroy-on-close
    >
      <el-form label-width="90px" :model="ruleForm">
        <el-form-item label="模块" required>
          <el-select v-model="ruleForm.entityType" placeholder="选择模块" style="width: 100%" @change="ruleForm.fieldName = ''">
            <el-option
              v-for="opt in ENTITY_TYPE_OPTIONS"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="字段" required>
          <el-select v-model="ruleForm.fieldName" placeholder="选择字段" style="width: 100%" :disabled="!ruleForm.entityType">
            <el-option
              v-for="opt in ruleFieldOptions"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="规则类型" required>
          <el-select v-model="ruleForm.ruleType" placeholder="选择规则类型" style="width: 100%">
            <el-option
              v-for="opt in RULE_TYPE_OPTIONS"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="showRuleValue" label="约束值" required>
          <el-input
            v-model="ruleForm.ruleValue"
            :placeholder="ruleForm.ruleType === 'min_length' ? '输入最小长度 (如: 3)' : '输入正则表达式 (如: ^\\d{4}-\\d{2}-\\d{2}$)'"
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="ruleForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSaveRule">{{ editingRule ? '更新' : '添加' }}</el-button>
      </template>
    </el-dialog>

    <!-- Module Cards -->
    <div v-if="serviceAvailable" class="module-grid" v-loading="loading">
      <div
        v-for="item in completenessData"
        :key="item.entityType"
        class="module-card"
        :class="[getScoreClass(item.overallCompleteness), { selected: selectedModule?.entityType === item.entityType }]"
        @click="selectModule(item)"
      >
        <div class="module-score" :style="{ color: getScoreColor(item.overallCompleteness) }">
          {{ item.overallCompleteness.toFixed(1) }}%
        </div>
        <div class="module-name">{{ entityLabel(item.entityType) }}</div>
        <div class="module-records">{{ item.totalRecords }} 条记录</div>
        <div v-if="formatModuleMetadata(item)" class="module-metadata">
          {{ formatModuleMetadata(item) }}
        </div>
        <el-progress
          :percentage="item.overallCompleteness"
          :stroke-width="6"
          :color="getScoreColor(item.overallCompleteness)"
          :show-text="false"
        />
      </div>
    </div>

    <!-- Empty State (service available but no data) -->
    <el-empty
      v-if="serviceAvailable && !loading && completenessData.length === 0 && !errorMessage"
      description="当前工厂暂无可检测的数据记录"
    />

    <!-- Field Detail Table -->
    <div class="detail-section" v-if="selectedModule">
      <h3>{{ entityLabel(selectedModule.entityType) }} - 字段完整度</h3>
      <el-table
        :data="fieldTableData"
        stripe
        :max-height="480"
        :row-class-name="({ row }: { row: { completeness: number } }) => isFieldClickable(row) ? 'clickable-row' : ''"
        @row-click="handleFieldRowClick"
      >
        <el-table-column prop="field" label="字段" min-width="180">
          <template #default="{ row }">
            <div class="field-name-cell">
              <span>{{ row.field }}</span>
              <template v-if="fieldHasRule(row.fieldRaw)">
                <el-tag
                  v-for="rl in fieldRuleLabels(row.fieldRaw)"
                  :key="rl"
                  size="small"
                  type="warning"
                  class="field-rule-tag"
                >{{ rl }}</el-tag>
              </template>
              <el-tooltip v-if="isFieldClickable(row)" content="点击查看" placement="top">
                <el-icon class="drill-hint-icon"><Search /></el-icon>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="completeness" label="完整度" width="120">
          <template #default="{ row }">
            <span :style="{ color: getScoreColor(row.completeness), fontWeight: 600 }">
              {{ row.completeness.toFixed(1) }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="200">
          <template #default="{ row }">
            <el-progress
              :percentage="row.completeness"
              :stroke-width="8"
              :color="getScoreColor(row.completeness)"
              :show-text="false"
            />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag
              :type="row.completeness >= 75 ? 'success' : row.completeness >= 50 ? 'warning' : 'danger'"
              size="small"
            >
              {{ row.completeness >= 75 ? '良好' : row.completeness >= 50 ? '一般' : '不足' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- Feature 2A: Drill-to-Detail Drawer -->
    <el-drawer
      v-model="drillDrawerVisible"
      :title="`字段详情 — ${drillField?.field || ''}`"
      size="480px"
      direction="rtl"
    >
      <div v-if="drillField" class="drill-content">
        <div class="drill-summary-cards">
          <div class="drill-card">
            <div class="drill-card-label">所属模块</div>
            <div class="drill-card-value">{{ entityLabel(drillEntityType) }}</div>
          </div>
          <div class="drill-card">
            <div class="drill-card-label">字段名称</div>
            <div class="drill-card-value">{{ drillField.field }}</div>
          </div>
          <div class="drill-card">
            <div class="drill-card-label">总记录数</div>
            <div class="drill-card-value">{{ selectedModule?.totalRecords ?? 0 }}</div>
          </div>
          <div class="drill-card">
            <div class="drill-card-label">缺失记录数</div>
            <div class="drill-card-value" style="color: var(--el-color-danger, #ef4444)">{{ drillMissingCount }}</div>
          </div>
        </div>

        <div class="drill-completeness-bar-section">
          <h4>完整度分布</h4>
          <div class="drill-bar-container">
            <div
              class="drill-bar-filled"
              :style="{ width: drillField.completeness + '%', backgroundColor: getScoreColor(drillField.completeness) }"
            >
              <span v-if="drillField.completeness >= 15">{{ drillFilledCount }} 条已填</span>
            </div>
            <div
              class="drill-bar-missing"
              :style="{ width: (100 - drillField.completeness) + '%' }"
            >
              <span v-if="(100 - drillField.completeness) >= 15">{{ drillMissingCount }} 条缺失</span>
            </div>
          </div>
          <div class="drill-bar-legend">
            <span class="legend-item">
              <span class="legend-dot" :style="{ background: getScoreColor(drillField.completeness) }"></span>
              已填充 {{ drillField.completeness.toFixed(1) }}%
            </span>
            <span class="legend-item">
              <span class="legend-dot" style="background: #e5e7eb"></span>
              缺失 {{ (100 - drillField.completeness).toFixed(1) }}%
            </span>
          </div>
        </div>

        <div class="drill-explanation">
          <h4>缺失说明</h4>
          <p>
            在「{{ entityLabel(drillEntityType) }}」模块中，字段「{{ drillField.field }}」的数据填充率为
            <strong :style="{ color: getScoreColor(drillField.completeness) }">{{ drillField.completeness.toFixed(1) }}%</strong>。
            共有 <strong>{{ drillMissingCount }}</strong> 条记录的该字段值为空或未填写。
          </p>
          <p>建议排查数据录入流程，确保该字段在业务操作中被正确填写。</p>
        </div>

        <el-alert
          type="info"
          :closable="false"
          style="margin-top: 16px"
        >
          <template #title>
            <span style="font-weight: 500">详细缺失记录列表需要后端支持，请联系管理员开启</span>
          </template>
          <p style="margin: 4px 0 0; font-size: 12px; color: var(--el-text-color-secondary)">
            后端接口就绪后，此处将展示每条缺失记录的 ID 及关联信息。
          </p>
        </el-alert>
      </div>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.data-completeness {
  padding: var(--page-padding, 20px);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;

  .header-left {
    h2 {
      margin: 12px 0 0;
      font-size: 20px;
      color: var(--el-text-color-primary, #303133);
    }
  }
}

// Service unavailable notice
.service-notice {
  margin-bottom: 24px;
}

.notice-card {
  border-radius: 12px;
  border-left: 4px solid #2D8B57;

  :deep(.el-card__body) {
    padding: 32px;
  }
}

.notice-content {
  text-align: center;
  max-width: 600px;
  margin: 0 auto;

  .notice-icon {
    color: var(--el-color-primary, #2D8B57);
    margin-bottom: 16px;
  }

  h3 {
    font-size: 18px;
    font-weight: 600;
    color: var(--el-text-color-primary, #303133);
    margin: 0 0 12px;
  }

  .notice-desc {
    font-size: 14px;
    color: var(--el-text-color-regular, #606266);
    line-height: 1.6;
    margin: 0 0 20px;
  }
}

.notice-features {
  text-align: left;
  display: inline-block;

  .feature-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 0;
    font-size: 13px;
    color: var(--el-text-color-regular, #606266);

    .feature-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      flex-shrink: 0;

      &.good {
        background: var(--el-color-success, #10b981);
      }
    }
  }
}

// Summary bar
.summary-bar {
  display: flex;
  gap: 24px;
  padding: 16px 20px;
  background: #f5f7fa;
  border-radius: 8px;
  margin-bottom: 20px;

  .summary-item {
    display: flex;
    align-items: center;
    gap: 8px;

    .summary-label {
      font-size: 13px;
      color: var(--el-text-color-secondary, #909399);
    }

    .summary-value {
      font-size: 18px;
      font-weight: 600;
      color: var(--el-text-color-primary, #303133);
      font-variant-numeric: tabular-nums;
    }
  }
}

.module-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

.module-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  cursor: pointer;
  border: 2px solid transparent;
  transition: all 0.2s;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  }

  &.selected {
    border-color: var(--el-color-primary, #2D8B57);
  }

  &.score-good {
    border-left: 4px solid var(--el-color-success, #10b981);
  }

  &.score-medium {
    border-left: 4px solid var(--el-color-warning, #f59e0b);
  }

  &.score-low {
    border-left: 4px solid var(--el-color-danger, #ef4444);
  }
}

.module-score {
  font-size: 24px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.module-name {
  font-size: 15px;
  font-weight: 600;
  margin: 4px 0;
  color: var(--el-text-color-primary, #303133);
}

.module-records {
  font-size: 13px;
  color: var(--el-text-color-secondary, #909399);
  margin-bottom: 4px;
}

.module-metadata {
  font-size: 12px;
  color: var(--el-text-color-placeholder, #c0c4cc);
  margin-bottom: 8px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.detail-section {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);

  h3 {
    margin: 0 0 12px 0;
    color: var(--el-text-color-primary, #303133);
  }

  // Feature 2A: Clickable rows
  :deep(.clickable-row) {
    cursor: pointer;

    &:hover td {
      background-color: var(--el-color-primary-light-9, #ecf5ff) !important;
    }
  }
}

// Feature 2A: Field name cell with rule tags and drill hint
.field-name-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;

  .field-rule-tag {
    font-size: 11px;
    padding: 0 4px;
    height: 18px;
    line-height: 18px;
  }

  .drill-hint-icon {
    color: var(--el-text-color-placeholder, #c0c4cc);
    font-size: 14px;
    margin-left: auto;
    flex-shrink: 0;
    transition: color 0.2s;
  }
}

.clickable-row:hover .drill-hint-icon {
  color: var(--el-color-primary, #2D8B57);
}

// Feature 2A: Drill drawer content
.drill-content {
  padding: 0 4px;

  h4 {
    font-size: 14px;
    font-weight: 600;
    color: var(--el-text-color-primary, #303133);
    margin: 0 0 12px;
  }
}

.drill-summary-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 24px;
}

.drill-card {
  background: #f5f7fa;
  border-radius: 8px;
  padding: 14px 16px;

  .drill-card-label {
    font-size: 12px;
    color: var(--el-text-color-secondary, #909399);
    margin-bottom: 4px;
  }

  .drill-card-value {
    font-size: 18px;
    font-weight: 600;
    color: var(--el-text-color-primary, #303133);
    font-variant-numeric: tabular-nums;
  }
}

.drill-completeness-bar-section {
  margin-bottom: 24px;
}

.drill-bar-container {
  display: flex;
  height: 32px;
  border-radius: 6px;
  overflow: hidden;
  background: #e5e7eb;
}

.drill-bar-filled {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 12px;
  font-weight: 500;
  transition: width 0.3s ease;
  min-width: 0;
}

.drill-bar-missing {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #e5e7eb;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
  font-weight: 500;
  min-width: 0;
}

.drill-bar-legend {
  display: flex;
  gap: 16px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-regular, #606266);

  .legend-item {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .legend-dot {
    width: 10px;
    height: 10px;
    border-radius: 2px;
    flex-shrink: 0;
  }
}

.drill-explanation {
  margin-top: 20px;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
  border-left: 3px solid var(--el-color-primary, #2D8B57);

  p {
    font-size: 13px;
    line-height: 1.7;
    color: var(--el-text-color-regular, #606266);
    margin: 0 0 8px;

    &:last-child {
      margin-bottom: 0;
    }
  }
}

// Feature 2B: Validation rules section
.rules-section {
  margin-bottom: 20px;

  :deep(.el-collapse) {
    border: 1px solid var(--el-border-color-lighter, #ebeef5);
    border-radius: 8px;
    overflow: hidden;
  }

  :deep(.el-collapse-item__header) {
    padding: 0 16px;
    height: 48px;
    font-size: 14px;
    font-weight: 500;
    background: #fafafa;
  }

  :deep(.el-collapse-item__content) {
    padding: 0;
  }
}

.rules-collapse-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rules-badge {
  :deep(.el-badge__content) {
    font-size: 11px;
  }
}

.rules-content {
  padding: 16px;
}

.rules-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;

  .rules-hint {
    font-size: 12px;
    color: var(--el-text-color-placeholder, #c0c4cc);
  }
}

.text-muted {
  color: var(--el-text-color-placeholder, #c0c4cc);
}
</style>
