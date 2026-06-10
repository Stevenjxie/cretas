<template>
  <div class="page-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2 class="page-title">采购发票</h2>
      <p class="page-subtitle">管理采购发票上传、核对与逾期提醒</p>
    </div>

    <!-- 逾期提醒卡 (Fool-proof Rule 5: 有待办就导航) -->
    <el-card
      v-if="reminderPoIds.length > 0"
      class="reminder-card"
      shadow="never"
    >
      <el-alert
        type="warning"
        :closable="false"
        title=""
        style="border: none; padding: 0; background: transparent;"
      >
        <template #default>
          <span>
            <strong>{{ reminderPoIds.length }} 张采购单</strong>
            逾期未收票，请及时催收：
          </span>
          <el-tag
            v-for="poId in reminderPoIds.slice(0, 5)"
            :key="poId"
            type="warning"
            size="small"
            style="margin-left: 6px; cursor: pointer"
            @click="filterByPoId(poId)"
          >
            {{ poId }}
          </el-tag>
          <span v-if="reminderPoIds.length > 5" style="margin-left: 6px; color: #E6A23C">
            ...等 {{ reminderPoIds.length }} 张
          </span>
        </template>
      </el-alert>
    </el-card>

    <!-- 过滤栏 -->
    <el-card class="filter-card" shadow="never">
      <el-row :gutter="16" align="middle">
        <el-col :span="5">
          <el-select
            v-model="filterStatus"
            placeholder="发票状态"
            clearable
            style="width: 100%"
            @change="handleFilterChange"
          >
            <el-option label="全部" value="" />
            <el-option label="待收票" value="PENDING" />
            <el-option label="已收票" value="RECEIVED" />
            <el-option label="已核对" value="RECONCILED" />
            <el-option label="已逾期" value="OVERDUE" />
          </el-select>
        </el-col>
        <el-col :span="6">
          <el-input
            v-model="filterPoId"
            placeholder="按采购单号查询"
            clearable
            prefix-icon="Search"
            @clear="handleFilterChange"
            @keyup.enter="handleFilterChange"
          />
        </el-col>
        <el-col :span="4">
          <el-button type="primary" @click="handleFilterChange">查询</el-button>
          <el-button @click="resetFilter">重置</el-button>
        </el-col>
        <el-col :span="9" style="text-align: right">
          <el-button
            v-if="canWrite"
            type="primary"
            @click="openUploadDialog"
          >
            上传发票
          </el-button>
        </el-col>
      </el-row>
    </el-card>

    <!-- 数据表格 -->
    <el-card class="data-card" shadow="never">
      <el-table
        v-loading="loading"
        :data="tableData"
        stripe
        row-key="id"
        style="width: 100%"
      >
        <el-table-column label="发票号" prop="invoiceNumber" width="150" />
        <el-table-column label="采购单号" prop="purchaseOrderNumber" width="160" />
        <el-table-column label="供应商" prop="supplierName" min-width="120" />
        <!-- @PriceSensitive -->
        <el-table-column label="发票金额" prop="amount" width="120">
          <template #default="{ row }">
            <span v-if="row.amount !== null && row.amount !== undefined">
              ¥{{ formatAmount(row.amount) }}
            </span>
            <span v-else class="price-hidden">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" prop="reconcileStatus" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.reconcileStatus)" size="small">
              {{ statusLabel(row.reconcileStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="核对结果" prop="reconcileResult" width="100">
          <template #default="{ row }">
            <el-tag
              v-if="row.reconcileResult"
              :type="row.reconcileResult === 'MATCHED' ? 'success' : 'danger'"
              size="small"
            >
              {{ row.reconcileResult === 'MATCHED' ? '金额相符' : '金额不符' }}
            </el-tag>
            <span v-else style="color: #C0C4CC">—</span>
          </template>
        </el-table-column>
        <el-table-column label="核对备注" prop="reconcileNote" min-width="120">
          <template #default="{ row }">
            {{ row.reconcileNote || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="上传时间" prop="createdAt" width="150">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canWrite && row.reconcileStatus === 'RECEIVED'"
              type="primary"
              link
              size="small"
              @click="openReconcileDialog(row)"
            >
              核对
            </el-button>
            <el-button type="info" link size="small" @click="openDetailDialog(row)">
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="fetchData"
          @size-change="fetchData"
        />
      </div>
    </el-card>

    <!-- 上传发票 Dialog (Rule 2: 上传前需填采购单 ID，提供上下文) -->
    <el-dialog
      v-model="uploadDialogVisible"
      title="上传采购发票"
      width="540px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="uploadFormRef"
        :model="uploadForm"
        :rules="uploadRules"
        label-width="100px"
      >
        <el-form-item label="采购单号" prop="purchaseOrderId">
          <el-input
            v-model="uploadForm.purchaseOrderId"
            placeholder="请填写采购单 ID"
          />
        </el-form-item>
        <el-form-item label="供应商 ID" prop="supplierId">
          <el-input
            v-model="uploadForm.supplierId"
            placeholder="请填写供应商 ID"
          />
        </el-form-item>
        <el-form-item label="发票号" prop="invoiceNumber">
          <el-input
            v-model="uploadForm.invoiceNumber"
            placeholder="请填写发票号码"
          />
        </el-form-item>
        <el-form-item label="发票金额" prop="amount">
          <el-input-number
            v-model="uploadForm.amount"
            :precision="2"
            :min="0"
            style="width: 100%"
            placeholder="发票金额"
          />
        </el-form-item>
        <el-form-item label="OCR识别结果" prop="ocrRawResult">
          <el-input
            v-model="uploadForm.ocrRawResult"
            type="textarea"
            :rows="3"
            placeholder="可选：粘贴 OCR 识别原文"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doUpload">上传</el-button>
      </template>
    </el-dialog>

    <!-- 核对 Dialog (Rule 2: 含采购单号+供应商; Rule 3: 结果 dropdown) -->
    <el-dialog
      v-model="reconcileDialogVisible"
      :title="`发票核对 — ${currentRow?.supplierName || ''} (${currentRow?.purchaseOrderNumber || ''})`"
      width="500px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="dialog-context">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="发票号">{{ currentRow.invoiceNumber }}</el-descriptions-item>
          <el-descriptions-item label="采购单号">{{ currentRow.purchaseOrderNumber }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ currentRow.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="发票金额">
            {{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <el-form
        ref="reconcileFormRef"
        :model="reconcileForm"
        :rules="reconcileRules"
        label-width="90px"
      >
        <!-- Rule 3: dropdown 而非自由文本 -->
        <el-form-item label="核对结果" prop="reconcileStatus">
          <el-select v-model="reconcileForm.reconcileStatus" placeholder="请选择核对结果" style="width: 100%">
            <el-option label="金额相符 — 可结账" value="MATCHED" />
            <el-option label="金额不符 — 需复查" value="MISMATCHED" />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="reconcileForm.reconcileStatus === 'MISMATCHED'"
          label="差异原因"
          prop="note"
        >
          <el-select v-model="reconcileForm.note" placeholder="请选择差异原因" style="width: 100%" allow-create filterable>
            <el-option label="发票金额与采购单不符" value="发票金额与采购单不符" />
            <el-option label="数量不符" value="数量不符" />
            <el-option label="税率差异" value="税率差异" />
            <el-option label="供应商开票错误" value="供应商开票错误" />
            <el-option label="其他原因" value="其他原因" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" prop="note" v-if="reconcileForm.reconcileStatus !== 'MISMATCHED'">
          <el-input
            v-model="reconcileForm.note"
            type="textarea"
            :rows="2"
            placeholder="核对备注（可选）"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="reconcileDialogVisible = false">取消</el-button>
        <el-button
          :type="reconcileForm.reconcileStatus === 'MISMATCHED' ? 'danger' : 'success'"
          :loading="submitting"
          :disabled="!reconcileForm.reconcileStatus"
          @click="doReconcile"
        >
          确认核对
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情 Dialog -->
    <el-dialog
      v-model="detailDialogVisible"
      :title="`发票详情 — ${currentRow?.invoiceNumber || ''}`"
      width="560px"
    >
      <div v-if="currentRow">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="发票号">{{ currentRow.invoiceNumber }}</el-descriptions-item>
          <el-descriptions-item label="采购单号">{{ currentRow.purchaseOrderNumber }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ currentRow.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="发票金额">
            {{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(currentRow.reconcileStatus)" size="small">
              {{ statusLabel(currentRow.reconcileStatus) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="核对结果">
            <el-tag
              v-if="currentRow.reconcileResult"
              :type="currentRow.reconcileResult === 'MATCHED' ? 'success' : 'danger'"
              size="small"
            >
              {{ currentRow.reconcileResult === 'MATCHED' ? '金额相符' : '金额不符' }}
            </el-tag>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="核对备注" :span="2">{{ currentRow.reconcileNote || '—' }}</el-descriptions-item>
          <el-descriptions-item label="上传时间">{{ formatDate(currentRow.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatDate(currentRow.updatedAt) }}</el-descriptions-item>
          <el-descriptions-item label="OCR识别" :span="2">
            <el-text v-if="currentRow.ocrRawResult" type="info" size="small" truncated>
              {{ currentRow.ocrRawResult }}
            </el-text>
            <span v-else>—</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { get, post, put } from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'
import { usePermissionStore } from '@/store/modules/permission'

// ─── 类型定义 ───────────────────────────────────────────────
type InvoiceStatus = 'PENDING' | 'RECEIVED' | 'RECONCILED' | 'OVERDUE'
type ReconcileResult = 'MATCHED' | 'MISMATCHED'

interface InvoiceRow {
  id: string
  purchaseOrderId: string
  purchaseOrderNumber: string
  supplierId: string
  supplierName: string
  invoiceNumber: string
  amount: number | null   // @PriceSensitive
  reconcileStatus: InvoiceStatus
  reconcileResult: ReconcileResult | null
  reconcileNote: string | null
  ocrRawResult: string | null
  createdAt: string | null
  updatedAt: string | null
}

// ─── Store ────────────────────────────────────────────────────
const authStore = useAuthStore()
const permStore = usePermissionStore()
const factoryId = computed(() => authStore.factoryId)
const canWrite = computed(() => permStore.canWrite('procurement'))

// ─── 状态 ────────────────────────────────────────────────────
const loading = ref(false)
const submitting = ref(false)
const tableData = ref<InvoiceRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const filterStatus = ref('')
const filterPoId = ref('')

const reminderPoIds = ref<string[]>([])

const uploadDialogVisible = ref(false)
const reconcileDialogVisible = ref(false)
const detailDialogVisible = ref(false)
const currentRow = ref<InvoiceRow | null>(null)

// 上传表单
const uploadFormRef = ref<FormInstance>()
const uploadForm = ref({
  purchaseOrderId: '',
  supplierId: '',
  invoiceNumber: '',
  amount: 0,
  ocrRawResult: ''
})
const uploadRules: FormRules = {
  purchaseOrderId: [{ required: true, message: '请填写采购单 ID', trigger: 'blur' }],
  supplierId: [{ required: true, message: '请填写供应商 ID', trigger: 'blur' }],
  invoiceNumber: [{ required: true, message: '请填写发票号', trigger: 'blur' }],
  amount: [{ required: true, type: 'number', message: '请填写发票金额', trigger: 'change' }]
}

// 核对表单
const reconcileFormRef = ref<FormInstance>()
const reconcileForm = ref({ reconcileStatus: '', note: '' })
const reconcileRules: FormRules = {
  reconcileStatus: [{ required: true, message: '请选择核对结果', trigger: 'change' }]
}

// ─── 标签映射 ────────────────────────────────────────────────
function statusLabel(s: string): string {
  const map: Record<string, string> = {
    PENDING: '待收票',
    RECEIVED: '已收票',
    RECONCILED: '已核对',
    OVERDUE: '已逾期'
  }
  return map[s] ?? s
}

function statusTag(s: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  const map: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
    PENDING: 'info',
    RECEIVED: '',
    RECONCILED: 'success',
    OVERDUE: 'danger'
  }
  return map[s] ?? 'info'
}

function formatAmount(v: number | null): string {
  if (v === null || v === undefined) return '—'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function formatDate(dt: string | null): string {
  if (!dt) return '—'
  return new Date(dt).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  })
}

// ─── 逾期提醒 ────────────────────────────────────────────────
async function fetchReminders() {
  if (!factoryId.value) return
  try {
    const res = await get(`/${factoryId.value}/invoices/reminder`, {})
    if (res.success && Array.isArray(res.data)) {
      reminderPoIds.value = res.data as string[]
    }
  } catch {
    // 提醒失败不阻塞主流程
  }
}

function filterByPoId(poId: string) {
  filterPoId.value = poId
  handleFilterChange()
}

// ─── 数据加载 ────────────────────────────────────────────────
async function fetchData() {
  if (!factoryId.value) return
  loading.value = true
  try {
    const params: Record<string, unknown> = {
      page: page.value - 1,
      size: pageSize.value
    }
    // 后端 GET /invoices 要求 poId，如未传则使用空字符串列全量
    if (filterPoId.value) params.poId = filterPoId.value
    if (filterStatus.value) params.status = filterStatus.value

    const res = await get(`/${factoryId.value}/invoices`, params)
    if (res.success) {
      const data = res.data
      if (data && typeof data === 'object' && 'content' in data) {
        tableData.value = (data as { content: InvoiceRow[]; totalElements: number }).content
        total.value = (data as { content: InvoiceRow[]; totalElements: number }).totalElements
      } else if (Array.isArray(data)) {
        tableData.value = data as InvoiceRow[]
        total.value = data.length
      }
    }
  } catch (err) {
    console.error('获取发票列表失败', err)
  } finally {
    loading.value = false
  }
}

function handleFilterChange() {
  page.value = 1
  fetchData()
}

function resetFilter() {
  filterStatus.value = ''
  filterPoId.value = ''
  handleFilterChange()
}

// ─── 上传发票 ────────────────────────────────────────────────
function openUploadDialog() {
  uploadForm.value = { purchaseOrderId: '', supplierId: '', invoiceNumber: '', amount: 0, ocrRawResult: '' }
  uploadFormRef.value?.resetFields()
  uploadDialogVisible.value = true
}

async function doUpload() {
  if (!uploadFormRef.value || !factoryId.value) return
  await uploadFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const res = await post(`/${factoryId.value}/invoices/upload`, {
        purchaseOrderId: uploadForm.value.purchaseOrderId,
        supplierId: uploadForm.value.supplierId,
        invoiceNumber: uploadForm.value.invoiceNumber,
        amount: uploadForm.value.amount,
        ocrRawResult: uploadForm.value.ocrRawResult || null
      })
      if (res.success) {
        ElMessage({ message: '发票上传成功', type: 'success', duration: 3000 })
        uploadDialogVisible.value = false
        fetchData()
        fetchReminders()
      } else {
        ElMessage({ message: res.message || '上传失败', type: 'error', duration: 0, showClose: true })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '上传失败，请检查网络'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      submitting.value = false
    }
  })
}

// ─── 核对发票 ────────────────────────────────────────────────
function openReconcileDialog(row: InvoiceRow) {
  currentRow.value = row
  reconcileForm.value = { reconcileStatus: '', note: '' }
  reconcileFormRef.value?.resetFields()
  reconcileDialogVisible.value = true
}

async function doReconcile() {
  if (!reconcileFormRef.value || !currentRow.value || !factoryId.value) return
  await reconcileFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const res = await put(
        `/${factoryId.value}/invoices/${currentRow.value!.id}/reconcile`,
        {
          reconcileStatus: reconcileForm.value.reconcileStatus,
          note: reconcileForm.value.note || null
        }
      )
      if (res.success) {
        ElMessage({ message: '发票核对完成', type: 'success', duration: 3000 })
        reconcileDialogVisible.value = false
        fetchData()
      } else {
        ElMessage({ message: res.message || '核对失败', type: 'error', duration: 0, showClose: true })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '核对失败，请检查网络'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      submitting.value = false
    }
  })
}

// ─── 详情 ────────────────────────────────────────────────────
function openDetailDialog(row: InvoiceRow) {
  currentRow.value = row
  detailDialogVisible.value = true
}

// ─── 生命周期 ────────────────────────────────────────────────
onMounted(() => {
  fetchData()
  fetchReminders()
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

.reminder-card {
  border-radius: 10px;
  margin-bottom: 16px;
  border-color: #F0A030;
  box-shadow: 0 2px 12px rgba(240, 160, 48, 0.1);
}

.filter-card {
  border-radius: 10px;
  margin-bottom: 16px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.data-card {
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
}

.price-hidden {
  color: #C0C4CC;
  font-style: italic;
}

.dialog-context {
  margin-bottom: 4px;
}
</style>
