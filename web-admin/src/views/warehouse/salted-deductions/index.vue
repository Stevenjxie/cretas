<template>
  <div class="page-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2 class="page-title">盐化仓管理</h2>
      <p class="page-subtitle">盐化(代加工)独立扣量记录与独立报表 — 不混入销售报表口径</p>
    </div>

    <!-- Tab 切换 -->
    <el-tabs v-model="activeTab" class="main-tabs" @tab-change="handleTabChange">
      <el-tab-pane label="扣量记录" name="records" />
      <el-tab-pane label="盐化报表" name="report" />
    </el-tabs>

    <!-- ===== TAB: 扣量记录 ===== -->
    <div v-if="activeTab === 'records'">
      <!-- 过滤栏 -->
      <el-card class="filter-card" shadow="never">
        <el-row :gutter="16" align="middle">
          <el-col :span="6">
            <el-input
              v-model="listFilter.warehouseId"
              placeholder="盐化仓 ID（必填）"
              clearable
              @clear="handleListFilterChange"
            />
          </el-col>
          <el-col :span="5">
            <el-date-picker
              v-model="listFilter.startDate"
              type="date"
              placeholder="开始日期"
              value-format="YYYY-MM-DD"
              style="width: 100%"
              @change="handleListFilterChange"
            />
          </el-col>
          <el-col :span="5">
            <el-date-picker
              v-model="listFilter.endDate"
              type="date"
              placeholder="结束日期"
              value-format="YYYY-MM-DD"
              style="width: 100%"
              @change="handleListFilterChange"
            />
          </el-col>
          <el-col :span="5">
            <el-button type="primary" @click="handleListFilterChange">查询</el-button>
            <el-button @click="resetListFilter">重置</el-button>
          </el-col>
          <el-col :span="3" style="text-align: right">
            <el-button v-if="canWrite" type="success" @click="openCreateDialog">
              + 录入扣量
            </el-button>
          </el-col>
        </el-row>
        <div v-if="!listFilter.warehouseId" class="filter-hint">
          <el-text type="warning" size="small">请先输入盐化仓 ID 再查询</el-text>
        </div>
      </el-card>

      <!-- 扣量记录表格 -->
      <el-card class="data-card" shadow="never">
        <el-table
          v-loading="listLoading"
          :data="listData"
          stripe
          row-key="id"
          style="width: 100%"
          empty-text="暂无数据，请输入盐化仓 ID 并查询"
        >
          <el-table-column label="扣量单号" prop="orderNumber" width="170" />
          <el-table-column label="扣量日期" prop="deductionDate" width="110">
            <template #default="{ row }">{{ row.deductionDate || '—' }}</template>
          </el-table-column>
          <el-table-column label="客户名称" prop="customerName" min-width="120">
            <template #default="{ row }">{{ row.customerName || '—' }}</template>
          </el-table-column>
          <el-table-column label="原料批次 ID" prop="materialBatchId" width="160">
            <template #default="{ row }">
              <el-text size="small" type="info">{{ row.materialBatchId }}</el-text>
            </template>
          </el-table-column>
          <el-table-column label="扣量" prop="quantity" width="110">
            <template #default="{ row }">
              {{ row.quantity }} {{ row.quantityUnit }}
            </template>
          </el-table-column>
          <!-- @PriceSensitive: unitPrice/totalAmount 无价格权限时返回 null -->
          <el-table-column label="单价" prop="unitPrice" width="100">
            <template #default="{ row }">
              <span v-if="row.unitPrice !== null && row.unitPrice !== undefined">
                ¥{{ formatAmount(row.unitPrice) }}
              </span>
              <span v-else class="price-hidden">—</span>
            </template>
          </el-table-column>
          <el-table-column label="总金额" prop="totalAmount" width="110">
            <template #default="{ row }">
              <span v-if="row.totalAmount !== null && row.totalAmount !== undefined">
                ¥{{ formatAmount(row.totalAmount) }}
              </span>
              <span v-else class="price-hidden">—</span>
            </template>
          </el-table-column>
          <el-table-column label="备注" prop="remarks" min-width="120">
            <template #default="{ row }">{{ row.remarks || '—' }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- ===== TAB: 盐化报表 ===== -->
    <div v-if="activeTab === 'report'">
      <!-- 报表查询条件 -->
      <el-card class="filter-card" shadow="never">
        <el-row :gutter="16" align="middle">
          <el-col :span="6">
            <el-input
              v-model="reportFilter.warehouseId"
              placeholder="盐化仓 ID（空 = 全厂汇总）"
              clearable
            />
          </el-col>
          <el-col :span="5">
            <el-date-picker
              v-model="reportFilter.startDate"
              type="date"
              placeholder="开始日期"
              value-format="YYYY-MM-DD"
              style="width: 100%"
            />
          </el-col>
          <el-col :span="5">
            <el-date-picker
              v-model="reportFilter.endDate"
              type="date"
              placeholder="结束日期"
              value-format="YYYY-MM-DD"
              style="width: 100%"
            />
          </el-col>
          <el-col :span="8">
            <el-button type="primary" :loading="reportLoading" @click="fetchReport">查询报表</el-button>
            <el-button @click="resetReportFilter">重置</el-button>
          </el-col>
        </el-row>
        <div class="filter-hint">
          <el-text type="info" size="small">
            盐化报表独立口径 — 不混入销售报表。空仓库 ID 汇总工厂下全部盐化仓。
          </el-text>
        </div>
      </el-card>

      <!-- 报表汇总 -->
      <el-row v-if="report" :gutter="16" class="summary-row">
        <el-col :span="6">
          <el-card class="summary-card" shadow="never">
            <div class="summary-label">报表期间</div>
            <div class="summary-value">
              {{ report.startDate }} ~ {{ report.endDate }}
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card class="summary-card" shadow="never">
            <div class="summary-label">盐化仓</div>
            <div class="summary-value">
              {{ report.warehouseName || report.warehouseId || '全厂汇总' }}
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card class="summary-card" shadow="never">
            <div class="summary-label">盐化总出量</div>
            <div class="summary-value summary-value--large">
              {{ report.totalQuantity }} kg
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card class="summary-card" shadow="never">
            <div class="summary-label">盐化总金额</div>
            <div class="summary-value summary-value--large">
              <span v-if="report.totalAmount !== null && report.totalAmount !== undefined">
                ¥{{ formatAmount(report.totalAmount) }}
              </span>
              <span v-else class="price-hidden">— (含无单价行)</span>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 报表明细行 -->
      <el-card v-if="report" class="data-card" shadow="never">
        <template #header>
          <span>扣量明细 (共 {{ report.lineCount }} 行)</span>
        </template>
        <el-table
          :data="report.lines"
          stripe
          row-key="id"
          style="width: 100%"
        >
          <el-table-column label="扣量单号" prop="orderNumber" width="170" />
          <el-table-column label="扣量日期" prop="deductionDate" width="110" />
          <el-table-column label="客户名称" prop="customerName" min-width="120">
            <template #default="{ row }">{{ row.customerName || '—' }}</template>
          </el-table-column>
          <el-table-column label="原料名称" prop="materialName" min-width="120">
            <template #default="{ row }">{{ row.materialName || '—' }}</template>
          </el-table-column>
          <el-table-column label="批次号" prop="batchNumber" width="130">
            <template #default="{ row }">{{ row.batchNumber || '—' }}</template>
          </el-table-column>
          <el-table-column label="扣量" width="110">
            <template #default="{ row }">
              {{ row.quantity }} {{ row.quantityUnit }}
            </template>
          </el-table-column>
          <el-table-column label="单价" prop="unitPrice" width="100">
            <template #default="{ row }">
              <span v-if="row.unitPrice !== null && row.unitPrice !== undefined">
                ¥{{ formatAmount(row.unitPrice) }}
              </span>
              <span v-else class="price-hidden">—</span>
            </template>
          </el-table-column>
          <el-table-column label="总金额" prop="totalAmount" width="110">
            <template #default="{ row }">
              <span v-if="row.totalAmount !== null && row.totalAmount !== undefined">
                ¥{{ formatAmount(row.totalAmount) }}
              </span>
              <span v-else class="price-hidden">—</span>
            </template>
          </el-table-column>
          <el-table-column label="备注" prop="remarks" min-width="100">
            <template #default="{ row }">{{ row.remarks || '—' }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-empty v-else-if="!reportLoading" description="请选择日期区间后点击「查询报表」" />
    </div>

    <!-- ===== 录入扣量 Dialog ===== -->
    <el-dialog
      v-model="createDialogVisible"
      title="录入盐化扣量"
      width="560px"
      :close-on-click-modal="false"
    >
      <!-- Rule 1: 先显示可扣上限提示 -->
      <el-alert
        type="info"
        :closable="false"
        style="margin-bottom: 16px"
      >
        防呆提示：请先在「分仓库存查询」确认盐化仓当前可用库存，
        扣量数量不得超出批次可用量。盐化扣量走独立口径，不混入销售报表。
      </el-alert>

      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-width="110px"
      >
        <el-form-item label="盐化仓 ID" prop="warehouseId">
          <el-input
            v-model="createForm.warehouseId"
            placeholder="盐化仓 ID (type=SALTED)"
          />
        </el-form-item>
        <el-form-item label="原料批次 ID" prop="materialBatchId">
          <el-input
            v-model="createForm.materialBatchId"
            placeholder="被扣减的原料批次 ID"
          />
        </el-form-item>
        <!-- Rule 2: 含单号/幂等提示 -->
        <el-form-item label="盐化供单号" prop="orderNumber">
          <el-input
            v-model="createForm.orderNumber"
            placeholder="如 SD-20261020-001 (幂等去重 key)"
          />
        </el-form-item>
        <el-form-item label="扣量日期" prop="deductionDate">
          <el-date-picker
            v-model="createForm.deductionDate"
            type="date"
            placeholder="扣量日期"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="扣减数量" prop="quantity">
          <el-input-number
            v-model="createForm.quantity"
            :min="0.001"
            :precision="3"
            :step="0.1"
            style="width: 100%"
            placeholder="扣减数量 (kg)"
          />
        </el-form-item>
        <el-form-item label="单价（选填）" prop="unitPrice">
          <el-input-number
            v-model="createForm.unitPrice"
            :min="0"
            :precision="4"
            :step="0.01"
            style="width: 100%"
            placeholder="用于期中盘账勾稽（可不填）"
          />
        </el-form-item>
        <el-form-item label="客户名称（选填）" prop="customerName">
          <el-input
            v-model="createForm.customerName"
            placeholder="外商/代加工客户名称"
          />
        </el-form-item>
        <el-form-item label="备注" prop="remarks">
          <el-input
            v-model="createForm.remarks"
            type="textarea"
            :rows="2"
            placeholder="备注（可选）"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="doCreate">
          确认录入
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { get, post } from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'
import { usePermissionStore } from '@/store/modules/permission'

// ─── 类型定义 ────────────────────────────────────────────────
interface SaltedDeduction {
  id: string
  orderNumber: string
  warehouseId: string
  materialBatchId: string
  deductionDate: string
  quantity: number
  quantityUnit: string
  unitPrice: number | null
  totalAmount: number | null
  customerName: string | null
  operatorId: number | null
  remarks: string | null
  createdAt: string | null
}

interface SaltedReportLine {
  id: string
  orderNumber: string
  deductionDate: string
  quantity: number
  quantityUnit: string
  unitPrice: number | null
  totalAmount: number | null
  customerName: string | null
  materialBatchId: string
  batchNumber: string | null
  materialName: string | null
  remarks: string | null
}

interface SaltedReport {
  factoryId: string
  warehouseId: string | null
  warehouseName: string | null
  startDate: string
  endDate: string
  totalQuantity: number
  totalAmount: number | null
  lines: SaltedReportLine[]
  lineCount: number
}

// ─── Store ────────────────────────────────────────────────────
const authStore = useAuthStore()
const permStore = usePermissionStore()
const factoryId = computed(() => authStore.factoryId)
const canWrite = computed(() => permStore.canWrite('warehouse'))

// ─── Tab ─────────────────────────────────────────────────────
const activeTab = ref<'records' | 'report'>('records')

function handleTabChange() {
  // reset on tab switch
}

// ─── 扣量记录 ────────────────────────────────────────────────
const listLoading = ref(false)
const listData = ref<SaltedDeduction[]>([])

const listFilter = ref({
  warehouseId: '',
  startDate: '',
  endDate: ''
})

function handleListFilterChange() {
  if (!listFilter.value.warehouseId) return
  fetchList()
}

function resetListFilter() {
  listFilter.value = { warehouseId: '', startDate: '', endDate: '' }
  listData.value = []
}

async function fetchList() {
  if (!factoryId.value || !listFilter.value.warehouseId) return
  if (!listFilter.value.startDate || !listFilter.value.endDate) {
    ElMessage({ message: '请选择开始日期和结束日期', type: 'warning', duration: 3000 })
    return
  }
  listLoading.value = true
  try {
    // 后端端点: GET /api/mobile/{factoryId}/factory/salted-deductions
    //   ?warehouseId=&startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
    const res = await get(`/${factoryId.value}/factory/salted-deductions`, {
      warehouseId: listFilter.value.warehouseId,
      startDate: listFilter.value.startDate,
      endDate: listFilter.value.endDate
    })
    if (res.success) {
      listData.value = Array.isArray(res.data) ? res.data : []
    } else {
      ElMessage({ message: res.message || '查询失败', type: 'error', duration: 0, showClose: true })
    }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '查询失败'
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
  } finally {
    listLoading.value = false
  }
}

// ─── 盐化报表 ────────────────────────────────────────────────
const reportLoading = ref(false)
const report = ref<SaltedReport | null>(null)

const reportFilter = ref({
  warehouseId: '',
  startDate: '',
  endDate: ''
})

function resetReportFilter() {
  reportFilter.value = { warehouseId: '', startDate: '', endDate: '' }
  report.value = null
}

async function fetchReport() {
  if (!factoryId.value) return
  if (!reportFilter.value.startDate || !reportFilter.value.endDate) {
    ElMessage({ message: '请选择开始日期和结束日期', type: 'warning', duration: 3000 })
    return
  }
  reportLoading.value = true
  try {
    // 后端端点: GET /api/mobile/{factoryId}/factory/salted-deductions/report
    //   ?warehouseId=&startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
    const params: Record<string, string> = {
      startDate: reportFilter.value.startDate,
      endDate: reportFilter.value.endDate
    }
    if (reportFilter.value.warehouseId) {
      params.warehouseId = reportFilter.value.warehouseId
    }
    const res = await get(`/${factoryId.value}/factory/salted-deductions/report`, params)
    if (res.success) {
      report.value = res.data as SaltedReport
    } else {
      ElMessage({ message: res.message || '报表查询失败', type: 'error', duration: 0, showClose: true })
    }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '报表查询失败'
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
  } finally {
    reportLoading.value = false
  }
}

// ─── 创建扣量 ────────────────────────────────────────────────
const createDialogVisible = ref(false)
const createSubmitting = ref(false)
const createFormRef = ref<FormInstance>()

const createForm = ref({
  warehouseId: '',
  materialBatchId: '',
  orderNumber: '',
  deductionDate: '',
  quantity: undefined as number | undefined,
  unitPrice: undefined as number | undefined,
  customerName: '',
  remarks: ''
})

const createRules: FormRules = {
  warehouseId: [{ required: true, message: '请输入盐化仓 ID', trigger: 'blur' }],
  materialBatchId: [{ required: true, message: '请输入原料批次 ID', trigger: 'blur' }],
  orderNumber: [{ required: true, message: '请输入盐化供单号', trigger: 'blur' }],
  deductionDate: [{ required: true, message: '请选择扣量日期', trigger: 'change' }],
  quantity: [{ required: true, message: '请输入扣减数量', trigger: 'blur' }]
}

function openCreateDialog() {
  createForm.value = {
    warehouseId: listFilter.value.warehouseId || '',
    materialBatchId: '',
    orderNumber: '',
    deductionDate: '',
    quantity: undefined,
    unitPrice: undefined,
    customerName: '',
    remarks: ''
  }
  createFormRef.value?.resetFields()
  createDialogVisible.value = true
}

async function doCreate() {
  if (!createFormRef.value || !factoryId.value) return
  await createFormRef.value.validate(async (valid) => {
    if (!valid) return
    createSubmitting.value = true
    try {
      // 后端端点: POST /api/mobile/{factoryId}/factory/salted-deductions
      const body: Record<string, unknown> = {
        warehouseId: createForm.value.warehouseId,
        materialBatchId: createForm.value.materialBatchId,
        orderNumber: createForm.value.orderNumber,
        deductionDate: createForm.value.deductionDate,
        quantity: createForm.value.quantity
      }
      if (createForm.value.unitPrice !== undefined && createForm.value.unitPrice !== null) {
        body.unitPrice = createForm.value.unitPrice
      }
      if (createForm.value.customerName) body.customerName = createForm.value.customerName
      if (createForm.value.remarks) body.remarks = createForm.value.remarks

      const res = await post(`/${factoryId.value}/factory/salted-deductions`, body)
      if (res.success) {
        ElMessage({ message: '盐化扣量录入成功', type: 'success', duration: 3000 })
        createDialogVisible.value = false
        // 自动刷新列表（如仓库 ID 已填且日期已选）
        if (listFilter.value.warehouseId && listFilter.value.startDate && listFilter.value.endDate) {
          fetchList()
        }
      } else {
        ElMessage({ message: res.message || '录入失败', type: 'error', duration: 0, showClose: true })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '录入失败'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      createSubmitting.value = false
    }
  })
}

// ─── 工具函数 ────────────────────────────────────────────────
function formatAmount(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// ─── 生命周期 ────────────────────────────────────────────────
onMounted(() => {
  // 不自动加载，需用户先填盐化仓 ID
})
</script>

<style scoped>
.page-container {
  padding: 20px;
  background: #F4F6F9;
  min-height: 100%;
}

.page-header {
  margin-bottom: 16px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #1B65A8;
  margin: 0 0 4px;
}

.page-subtitle {
  font-size: 13px;
  color: #909399;
  margin: 0;
}

.main-tabs {
  margin-bottom: 16px;
}

.filter-card {
  border-radius: 10px;
  margin-bottom: 16px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.filter-hint {
  margin-top: 8px;
}

.data-card {
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.summary-row {
  margin-bottom: 16px;
}

.summary-card {
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.summary-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.summary-value {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
}

.summary-value--large {
  font-size: 18px;
  color: #1B65A8;
  font-weight: 700;
}

.price-hidden {
  color: #C0C4CC;
  font-style: italic;
}
</style>
