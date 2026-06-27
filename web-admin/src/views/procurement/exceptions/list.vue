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
        <el-table-column label="采购单ID" prop="purchaseOrderId" width="160" />
        <el-table-column label="供应商ID" prop="supplierId" min-width="120" />
        <el-table-column label="物料名称" prop="materialName" min-width="120" />
        <el-table-column label="异常类型" prop="exceptionType" width="120">
          <template #default="{ row }">
            <el-tag :type="exceptionTypeTag(row.exceptionType)" size="small">
              {{ exceptionTypeLabel(row.exceptionType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="异常数量" prop="exceptionQty" width="100">
          <template #default="{ row }">
            {{ row.exceptionQty }} {{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="责任人" prop="ownerName" width="110">
          <template #default="{ row }">
            <span v-if="row.ownerName">{{ row.ownerName }}</span>
            <span v-else class="text-muted">—</span>
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
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <!-- D-6: 处理按钮仅责任人本人或主管角色可点（Fool-proof Rule 2 + Rule 5） -->
            <el-tooltip
              v-if="row.status === 'PENDING' && canWrite && !isOwnerOrSupervisor(row)"
              :content="`仅责任人 ${row.ownerName || '（未知）'} 或主管可处理`"
              placement="top"
            >
              <span>
                <el-button type="primary" link size="small" disabled>处理</el-button>
              </span>
            </el-tooltip>
            <el-button
              v-else-if="row.status === 'PENDING' && canWrite"
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
      :title="`处理异常 — ${currentRow?.materialName || ''} (${currentRow?.purchaseOrderId || ''})`"
      width="520px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="decision-context">
        <el-descriptions :column="2" border size="small" class="mb-16">
          <el-descriptions-item label="供应商ID">{{ currentRow.supplierId }}</el-descriptions-item>
          <el-descriptions-item label="异常类型">{{ exceptionTypeLabel(currentRow.exceptionType) }}</el-descriptions-item>
          <el-descriptions-item label="异常数量">{{ currentRow.exceptionQty }} {{ currentRow.unit }}</el-descriptions-item>
          <el-descriptions-item label="物料名称">{{ currentRow.materialName || '—' }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <el-form ref="decisionFormRef" :model="decisionForm" :rules="decisionRules" label-width="100px">
        <el-form-item label="处理决定" prop="decision">
          <!-- Rule 3: 约束选择 dropdown 而非自由文本 -->
          <!-- ExceptionDecision enum: ACCEPT_OVER / RETURN_OVER / ACCEPT_SHORT / REQUEST_RESUPPLY -->
          <el-select v-model="decisionForm.decision" placeholder="请选择处理方式" style="width: 100%">
            <el-option label="接收（数量偏差，全部入库）" value="ACCEPT_OVER" />
            <el-option label="退货（拒收，退回供应商）" value="RETURN_OVER" />
            <el-option label="按实收（短少，按实际数量入库）" value="ACCEPT_SHORT" />
            <el-option label="补货（缺货，要求供应商补发）" value="REQUEST_RESUPPLY" />
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
          <el-descriptions-item label="采购单ID">{{ currentRow.purchaseOrderId }}</el-descriptions-item>
          <el-descriptions-item label="供应商ID">{{ currentRow.supplierId }}</el-descriptions-item>
          <el-descriptions-item label="物料名称">{{ currentRow.materialName }}</el-descriptions-item>
          <el-descriptions-item label="异常类型">{{ exceptionTypeLabel(currentRow.exceptionType) }}</el-descriptions-item>
          <el-descriptions-item label="异常数量">{{ currentRow.exceptionQty }} {{ currentRow.unit }}</el-descriptions-item>
          <el-descriptions-item label="责任人">
            {{ currentRow.ownerName || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(currentRow.status)" size="small">
              {{ statusLabel(currentRow.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="处理决定">
            {{ currentRow.decision ? decisionLabel(currentRow.decision) : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="关联退货单" :span="2">
            <template v-if="currentRow.returnOrderId">
              <el-link type="primary" underline="hover" @click="goToReturns">采购退货单（点击前往审批）</el-link>
              <span class="text-muted" style="margin-left: 8px">待财务审批</span>
            </template>
            <span v-else class="text-muted">—</span>
          </el-descriptions-item>
          <el-descriptions-item label="处理备注" :span="2">{{ currentRow.decisionNotes || '—' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDate(currentRow.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="处理时间">{{ formatDate(currentRow.decisionAt) }}</el-descriptions-item>
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
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { get, post } from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'
import { usePermissionStore } from '@/store/modules/permission'

// ─── 类型定义 ───────────────────────────────────────────────
// Field names mirror the PurchaseException entity returned directly by the backend (SP6).
// Entity fields: exceptionNumber, purchaseOrderId, supplierId, materialName,
//                exceptionType (OVER_RECEIVE/UNDER_RECEIVE), exceptionQty, unit,
//                decision, decisionNotes, decisionAt, status (PENDING/RESOLVED), createdAt
//                ownerUserId, ownerName  — D-6 责任绑定
interface ExceptionRow {
  id: string
  exceptionNumber: string
  purchaseOrderId: string   // raw ID (no denormalized purchaseOrderNumber in entity)
  supplierId: string        // raw ID (no denormalized supplierName in entity)
  materialName: string
  exceptionType: 'OVER_RECEIVE' | 'UNDER_RECEIVE'
  exceptionQty: number      // was 'quantity' — entity field is exceptionQty
  unit: string
  status: 'PENDING' | 'RESOLVED'
  decision: 'ACCEPT_OVER' | 'RETURN_OVER' | 'ACCEPT_SHORT' | 'REQUEST_RESUPPLY' | null
  decisionNotes: string | null   // was 'notes' — entity field is decisionNotes
  decisionAt: string | null      // was 'resolvedAt' — entity field is decisionAt
  createdAt: string | null
  // D-6 责任绑定字段
  ownerUserId: number | null
  ownerName: string | null
  // D-BUG3: RETURN_OVER 决策生成的采购退货单 ID (待财务审批)
  returnOrderId: string | null
}

interface DecisionForm {
  decision: string
  notes: string
}

// ─── Store ────────────────────────────────────────────────────
const router = useRouter()
const authStore = useAuthStore()
const permStore = usePermissionStore()
const factoryId = computed(() => authStore.factoryId)
const canWrite = computed(() => permStore.canWrite('procurement'))

// D-6 鉴权：当前用户 ID 和角色（用于"处理"按钮禁用判断）
const currentUserId = computed(() => authStore.user?.id ?? null)
const currentRole = computed(() => authStore.currentRole)
const SUPERVISOR_ROLES = ['factory_super_admin', 'procurement_manager']

/**
 * D-6: 当前用户是否可处理此异常单
 * 条件：ownerUserId 本人，或主管角色（factory_super_admin / procurement_manager）
 */
function isOwnerOrSupervisor(row: ExceptionRow): boolean {
  if (SUPERVISOR_ROLES.includes(currentRole.value)) return true
  if (row.ownerUserId != null && currentUserId.value != null) {
    return Number(row.ownerUserId) === Number(currentUserId.value)
  }
  // ownerUserId 未设置（旧数据兼容）：允许任何有 write 权限的用户处理
  return row.ownerUserId == null
}

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
  // Matches ReceiveExceptionType enum: OVER_RECEIVE / UNDER_RECEIVE
  const map: Record<string, string> = {
    OVER_RECEIVE: '数量超收',
    UNDER_RECEIVE: '数量短少',
  }
  return map[t] ?? t
}

function exceptionTypeTag(t: string): '' | 'warning' | 'danger' | 'info' {
  const map: Record<string, '' | 'warning' | 'danger' | 'info'> = {
    OVER_RECEIVE: 'warning',
    UNDER_RECEIVE: 'warning',
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
  // Must match ExceptionDecision enum: ACCEPT_OVER / RETURN_OVER / ACCEPT_SHORT / REQUEST_RESUPPLY
  const map: Record<string, string> = {
    ACCEPT_OVER: '接收入库',
    RETURN_OVER: '退货',
    ACCEPT_SHORT: '按实收',
    REQUEST_RESUPPLY: '补货'
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
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })
      ?.response?.data?.message || '加载入库异常列表失败，请稍后重试'
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
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

function goToReturns() {
  detailDialogVisible.value = false
  router.push('/procurement/returns')
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
        decisionDialogVisible.value = false
        // D-BUG3: RETURN_OVER 决策 → 后端已生成采购退货单 (DRAFT, 待财务审批)。
        // 提示用户并提供跳转到采购退货页继续走 提交→审批→财务审批 链。
        const updated = res.data as ExceptionRow | null
        if (decisionForm.value.decision === 'RETURN_OVER' && updated?.returnOrderId) {
          fetchData()
          ElMessageBox.confirm(
            '已生成采购退货单，退货跟资金相关，需走「提交 → 业务审批 → 财务审批」后才能完成出货。是否前往采购退货页查看？',
            '已生成采购退货单',
            { confirmButtonText: '前往采购退货', cancelButtonText: '留在本页', type: 'success' },
          ).then(() => {
            router.push('/procurement/returns')
          }).catch(() => { /* 留在本页 */ })
        } else {
          ElMessage({ message: '处理成功', type: 'success', duration: 3000 })
          fetchData()
        }
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
