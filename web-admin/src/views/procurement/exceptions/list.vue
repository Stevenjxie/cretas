<template>
  <div class="page-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2 class="page-title">入库异常管理</h2>
      <p class="page-subtitle">处理采购入库时发现的数量/品质异常</p>
    </div>

    <!-- 过滤栏 -->
    <el-card class="filter-card" shadow="never">
      <el-row :gutter="16" align="middle">
        <el-col :span="6">
          <el-select
            v-model="filterStatus"
            placeholder="状态"
            clearable
            style="width: 100%"
            @change="handleFilterChange"
          >
            <el-option label="全部" value="" />
            <el-option label="待处理" value="PENDING" />
            <el-option label="已处理" value="RESOLVED" />
          </el-select>
        </el-col>
        <el-col :span="6">
          <el-input
            v-model="filterKeyword"
            placeholder="搜索供应商/PO单号"
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
        <el-table-column label="异常单号" prop="exceptionNumber" width="160" />
        <el-table-column label="采购单号" prop="purchaseOrderNumber" width="160" />
        <el-table-column label="供应商" prop="supplierName" min-width="120" />
        <el-table-column label="物料名称" prop="materialName" min-width="120" />
        <el-table-column label="异常类型" prop="exceptionType" width="120">
          <template #default="{ row }">
            <el-tag :type="exceptionTypeTag(row.exceptionType)" size="small">
              {{ exceptionTypeLabel(row.exceptionType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="异常数量" prop="quantity" width="100">
          <template #default="{ row }">
            {{ row.quantity }} {{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="状态" prop="status" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处理决定" prop="decision" width="120">
          <template #default="{ row }">
            <span v-if="row.decision">{{ decisionLabel(row.decision) }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createdAt" width="150">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING' && canWrite"
              type="primary"
              link
              size="small"
              @click="openDecisionDialog(row)"
            >
              处理
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

    <!-- 处理决定 Dialog（Fool-proof Rule 2: 标题含 PO 号+供应商; Rule 3: dropdown原因） -->
    <el-dialog
      v-model="decisionDialogVisible"
      :title="`处理异常 — ${currentRow?.materialName || ''} (${currentRow?.purchaseOrderNumber || ''})`"
      width="520px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="decision-context">
        <el-descriptions :column="2" border size="small" class="mb-16">
          <el-descriptions-item label="供应商">{{ currentRow.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="异常类型">{{ exceptionTypeLabel(currentRow.exceptionType) }}</el-descriptions-item>
          <el-descriptions-item label="异常数量">{{ currentRow.quantity }} {{ currentRow.unit }}</el-descriptions-item>
          <el-descriptions-item label="说明">{{ currentRow.description || '—' }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <el-form ref="decisionFormRef" :model="decisionForm" :rules="decisionRules" label-width="100px">
        <el-form-item label="处理决定" prop="decision">
          <!-- Rule 3: 约束选择 dropdown 而非自由文本 -->
          <el-select v-model="decisionForm.decision" placeholder="请选择处理方式" style="width: 100%">
            <el-option label="接收（数量偏差，全部入库）" value="ACCEPT_OVER" />
            <el-option label="退货（拒收，退回供应商）" value="RETURN_OVER" />
            <el-option label="按实收（短少，按实际数量入库）" value="ACCEPT_UNDER" />
            <el-option label="补货（缺货，要求供应商补发）" value="REORDER" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" prop="notes">
          <el-input
            v-model="decisionForm.notes"
            type="textarea"
            :rows="3"
            placeholder="请填写处理说明（可选）"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="decisionDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!decisionForm.decision"
          @click="submitDecision"
        >
          确认处理
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情 Dialog -->
    <el-dialog
      v-model="detailDialogVisible"
      :title="`异常详情 — ${currentRow?.exceptionNumber || ''}`"
      width="560px"
    >
      <div v-if="currentRow">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="异常单号">{{ currentRow.exceptionNumber }}</el-descriptions-item>
          <el-descriptions-item label="采购单号">{{ currentRow.purchaseOrderNumber }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ currentRow.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="物料名称">{{ currentRow.materialName }}</el-descriptions-item>
          <el-descriptions-item label="异常类型">{{ exceptionTypeLabel(currentRow.exceptionType) }}</el-descriptions-item>
          <el-descriptions-item label="异常数量">{{ currentRow.quantity }} {{ currentRow.unit }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(currentRow.status)" size="small">
              {{ statusLabel(currentRow.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="处理决定">
            {{ currentRow.decision ? decisionLabel(currentRow.decision) : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="异常说明" :span="2">{{ currentRow.description || '—' }}</el-descriptions-item>
          <el-descriptions-item label="处理备注" :span="2">{{ currentRow.notes || '—' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDate(currentRow.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="处理时间">{{ formatDate(currentRow.resolvedAt) }}</el-descriptions-item>
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
import { get, post } from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'
import { usePermissionStore } from '@/store/modules/permission'

// ─── 类型定义 ───────────────────────────────────────────────
interface ExceptionRow {
  id: string
  exceptionNumber: string
  purchaseOrderId: string
  purchaseOrderNumber: string
  supplierId: string
  supplierName: string
  materialName: string
  exceptionType: 'QUANTITY_OVER' | 'QUANTITY_UNDER' | 'QUALITY' | 'OTHER'
  quantity: number
  unit: string
  status: 'PENDING' | 'RESOLVED'
  decision: 'ACCEPT_OVER' | 'RETURN_OVER' | 'ACCEPT_UNDER' | 'REORDER' | null
  description: string | null
  notes: string | null
  createdAt: string | null
  resolvedAt: string | null
}

interface DecisionForm {
  decision: string
  notes: string
}

// ─── Store ────────────────────────────────────────────────────
const authStore = useAuthStore()
const permStore = usePermissionStore()
const factoryId = computed(() => authStore.factoryId)
const canWrite = computed(() => permStore.canWrite('procurement'))

// ─── 状态 ────────────────────────────────────────────────────
const loading = ref(false)
const submitting = ref(false)
const tableData = ref<ExceptionRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const filterStatus = ref('')
const filterKeyword = ref('')

const decisionDialogVisible = ref(false)
const detailDialogVisible = ref(false)
const currentRow = ref<ExceptionRow | null>(null)

const decisionFormRef = ref<FormInstance>()
const decisionForm = ref<DecisionForm>({ decision: '', notes: '' })
const decisionRules: FormRules = {
  decision: [{ required: true, message: '请选择处理决定', trigger: 'change' }]
}

// ─── 标签映射 ────────────────────────────────────────────────
function exceptionTypeLabel(t: string): string {
  const map: Record<string, string> = {
    QUANTITY_OVER: '数量超收',
    QUANTITY_UNDER: '数量短少',
    QUALITY: '品质异常',
    OTHER: '其他'
  }
  return map[t] ?? t
}

function exceptionTypeTag(t: string): '' | 'warning' | 'danger' | 'info' {
  const map: Record<string, '' | 'warning' | 'danger' | 'info'> = {
    QUANTITY_OVER: 'warning',
    QUANTITY_UNDER: 'warning',
    QUALITY: 'danger',
    OTHER: 'info'
  }
  return map[t] ?? 'info'
}

function statusLabel(s: string): string {
  return s === 'PENDING' ? '待处理' : '已处理'
}

function statusTag(s: string): '' | 'success' | 'warning' {
  return s === 'PENDING' ? 'warning' : 'success'
}

function decisionLabel(d: string): string {
  const map: Record<string, string> = {
    ACCEPT_OVER: '接收入库',
    RETURN_OVER: '退货',
    ACCEPT_UNDER: '按实收',
    REORDER: '补货'
  }
  return map[d] ?? d
}

function formatDate(dt: string | null): string {
  if (!dt) return '—'
  return new Date(dt).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  })
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
    if (filterStatus.value) params.status = filterStatus.value
    if (filterKeyword.value) params.keyword = filterKeyword.value

    const res = await get(`/${factoryId.value}/purchase-exceptions`, params)
    if (res.success) {
      const data = res.data
      if (data && typeof data === 'object' && 'content' in data) {
        tableData.value = (data as { content: ExceptionRow[]; totalElements: number }).content
        total.value = (data as { content: ExceptionRow[]; totalElements: number }).totalElements
      } else if (Array.isArray(data)) {
        tableData.value = data as ExceptionRow[]
        total.value = data.length
      }
    }
  } catch (err) {
    console.error('获取入库异常列表失败', err)
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
  filterKeyword.value = ''
  handleFilterChange()
}

// ─── Dialog 操作 ─────────────────────────────────────────────
function openDecisionDialog(row: ExceptionRow) {
  currentRow.value = row
  decisionForm.value = { decision: '', notes: '' }
  decisionFormRef.value?.resetFields()
  decisionDialogVisible.value = true
}

function openDetailDialog(row: ExceptionRow) {
  currentRow.value = row
  detailDialogVisible.value = true
}

async function submitDecision() {
  if (!decisionFormRef.value) return
  await decisionFormRef.value.validate(async (valid) => {
    if (!valid) return
    if (!currentRow.value || !factoryId.value) return

    submitting.value = true
    try {
      const res = await post(
        `/${factoryId.value}/purchase-exceptions/${currentRow.value.id}/decide`,
        {
          decision: decisionForm.value.decision,
          notes: decisionForm.value.notes || null
        }
      )
      if (res.success) {
        ElMessage({ message: '处理成功', type: 'success', duration: 3000 })
        decisionDialogVisible.value = false
        fetchData()
      } else {
        // 4-in-1 sticky error toast with backend message
        ElMessage({
          message: res.message || '处理失败，请重试',
          type: 'error',
          duration: 0,
          showClose: true
        })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message || '处理失败，请检查网络'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      submitting.value = false
    }
  })
}

// ─── 生命周期 ────────────────────────────────────────────────
onMounted(fetchData)
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

.decision-context {
  margin-bottom: 20px;
}

.mb-16 {
  margin-bottom: 16px;
}

.text-muted {
  color: #C0C4CC;
}
</style>
