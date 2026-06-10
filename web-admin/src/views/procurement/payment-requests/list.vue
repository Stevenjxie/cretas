<template>
  <div class="page-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2 class="page-title">付款申请</h2>
      <p class="page-subtitle">管理采购付款申请的审批与付款流程</p>
    </div>

    <!-- 过滤栏 -->
    <el-card class="filter-card" shadow="never">
      <el-row :gutter="16" align="middle">
        <el-col :span="5">
          <el-select
            v-model="filterStatus"
            placeholder="状态"
            clearable
            style="width: 100%"
            @change="handleFilterChange"
          >
            <el-option label="全部" value="" />
            <el-option label="待提交" value="PENDING" />
            <el-option label="财务审核中" value="FINANCE_REVIEW" />
            <el-option label="已批准" value="APPROVED" />
            <el-option label="已付款" value="PAID" />
            <el-option label="已拒绝" value="REJECTED" />
          </el-select>
        </el-col>
        <el-col :span="5">
          <el-select
            v-model="filterSettlementType"
            placeholder="结算方式"
            clearable
            style="width: 100%"
            @change="handleFilterChange"
          >
            <el-option label="预付款" value="PREPAID" />
            <el-option label="先货后款" value="CREDIT_FIRST" />
            <el-option label="无票结算" value="NO_INVOICE" />
            <el-option label="月结" value="MONTHLY" />
            <el-option label="信用期" value="CREDIT_PERIOD" />
            <el-option label="即时付款" value="IMMEDIATE" />
          </el-select>
        </el-col>
        <el-col :span="5">
          <el-input
            v-model="filterKeyword"
            placeholder="搜索供应商/申请单号"
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
        <el-table-column label="申请单号" prop="requestNumber" width="160" />
        <el-table-column label="采购单号" prop="purchaseOrderNumber" width="160" />
        <el-table-column label="供应商" prop="supplierName" min-width="120" />
        <el-table-column label="结算方式" prop="settlementType" width="110">
          <template #default="{ row }">
            <el-tag type="info" size="small">{{ settlementTypeLabel(row.settlementType) }}</el-tag>
          </template>
        </el-table-column>
        <!-- @PriceSensitive: amount 字段后端对无权限角色返回 null，显示 "—" 不做前端计算 -->
        <el-table-column label="申请金额" prop="amount" width="110">
          <template #default="{ row }">
            <span v-if="row.amount !== null && row.amount !== undefined">
              ¥{{ formatAmount(row.amount) }}
            </span>
            <span v-else class="price-hidden">—</span>
          </template>
        </el-table-column>
        <el-table-column label="付款方式" prop="paymentMethod" width="100">
          <template #default="{ row }">
            {{ row.paymentMethod || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" prop="status" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createdAt" width="150">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <!-- 提交审核：采购员提交 PENDING→FINANCE_REVIEW -->
            <el-button
              v-if="row.status === 'PENDING' && canWrite"
              type="primary"
              link
              size="small"
              @click="openSubmitDialog(row)"
            >
              提交审核
            </el-button>
            <!-- 财务审批：finance_manager 审批 FINANCE_REVIEW -->
            <el-button
              v-if="row.status === 'FINANCE_REVIEW' && isFinanceManager"
              type="success"
              link
              size="small"
              @click="openApproveDialog(row)"
            >
              审批
            </el-button>
            <!-- 出纳付款：cashier 付款 APPROVED→PAID -->
            <el-button
              v-if="row.status === 'APPROVED' && isCashier"
              type="warning"
              link
              size="small"
              @click="openMarkPaidDialog(row)"
            >
              确认付款
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

    <!-- 提交审核 Dialog -->
    <el-dialog
      v-model="submitDialogVisible"
      :title="`提交审核 — ${currentRow?.supplierName || ''} (${currentRow?.requestNumber || ''})`"
      width="440px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="dialog-context">
        <el-alert type="info" :closable="false" style="margin-bottom: 16px">
          申请金额：<strong>{{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}</strong>
          &nbsp;| 采购单：{{ currentRow.purchaseOrderNumber }}
        </el-alert>
        <p style="color: #606266; font-size: 14px; margin: 0">提交后将进入财务审核流程，是否确认？</p>
      </div>
      <template #footer>
        <el-button @click="submitDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doSubmit">确认提交</el-button>
      </template>
    </el-dialog>

    <!-- 财务审批 Dialog（Rule 2: 含供应商+金额+单号；Rule 3: 意见选项） -->
    <el-dialog
      v-model="approveDialogVisible"
      :title="`财务审批 — ${currentRow?.supplierName || ''} (${currentRow?.requestNumber || ''})`"
      width="520px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="dialog-context">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="供应商">{{ currentRow.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="采购单号">{{ currentRow.purchaseOrderNumber }}</el-descriptions-item>
          <el-descriptions-item label="申请金额">
            {{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="结算方式">{{ settlementTypeLabel(currentRow.settlementType) }}</el-descriptions-item>
          <el-descriptions-item label="付款方式" :span="2">{{ currentRow.paymentMethod || '—' }}</el-descriptions-item>
          <el-descriptions-item label="申请备注" :span="2">{{ currentRow.remark || '—' }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <el-form ref="approveFormRef" :model="approveForm" :rules="approveRules" label-width="90px">
        <el-form-item label="审批结果" prop="action" required>
          <el-radio-group v-model="approveForm.action">
            <el-radio value="approve">批准</el-radio>
            <el-radio value="reject">拒绝</el-radio>
          </el-radio-group>
        </el-form-item>
        <!-- Rule 3: 拒绝原因 dropdown，而非自由文本 -->
        <el-form-item
          v-if="approveForm.action === 'reject'"
          label="拒绝原因"
          prop="rejectReason"
        >
          <el-select v-model="approveForm.rejectReason" placeholder="请选择拒绝原因" style="width: 100%">
            <el-option label="金额超出预算" value="金额超出预算" />
            <el-option label="缺少发票凭证" value="缺少发票凭证" />
            <el-option label="供应商信息不符" value="供应商信息不符" />
            <el-option label="付款条件不符合协议" value="付款条件不符合协议" />
            <el-option label="采购单状态异常" value="采购单状态异常" />
            <el-option label="其他原因" value="_other" />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="approveForm.action === 'reject' && approveForm.rejectReason === '_other'"
          label="补充说明"
          prop="rejectNote"
        >
          <el-input
            v-model="approveForm.rejectNote"
            type="textarea"
            :rows="2"
            placeholder="请说明具体原因"
          />
        </el-form-item>
        <el-form-item label="审批意见" prop="reviewNote">
          <el-input
            v-model="approveForm.reviewNote"
            type="textarea"
            :rows="2"
            placeholder="审批意见（可选）"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="approveDialogVisible = false">取消</el-button>
        <el-button
          :type="approveForm.action === 'reject' ? 'danger' : 'success'"
          :loading="submitting"
          :disabled="!approveForm.action"
          @click="doApprove"
        >
          {{ approveForm.action === 'reject' ? '确认拒绝' : '确认批准' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 确认付款 Dialog（出纳：Rule 1 显示金额，Rule 2 含单号+供应商） -->
    <el-dialog
      v-model="markPaidDialogVisible"
      :title="`确认付款 — ${currentRow?.supplierName || ''} (${currentRow?.requestNumber || ''})`"
      width="480px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="dialog-context">
        <el-alert
          type="warning"
          :closable="false"
          style="margin-bottom: 16px"
        >
          <strong>付款金额：{{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}</strong>
          &nbsp; 供应商：{{ currentRow.supplierName }}
        </el-alert>
        <el-alert type="info" :closable="false" style="margin-bottom: 16px">
          确认付款后将自动记录到应付账款并扣减供应商余额，此操作不可撤销。
        </el-alert>
      </div>

      <el-form ref="markPaidFormRef" :model="markPaidForm" :rules="markPaidRules" label-width="90px">
        <el-form-item label="付款凭证" prop="evidence">
          <el-input
            v-model="markPaidForm.evidence"
            placeholder="请填写付款凭证号或说明"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="markPaidDialogVisible = false">取消</el-button>
        <el-button
          type="warning"
          :loading="submitting"
          @click="doMarkPaid"
        >
          确认已付款
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情 Dialog -->
    <el-dialog
      v-model="detailDialogVisible"
      :title="`付款申请详情 — ${currentRow?.requestNumber || ''}`"
      width="600px"
    >
      <div v-if="currentRow">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="申请单号">{{ currentRow.requestNumber }}</el-descriptions-item>
          <el-descriptions-item label="采购单号">{{ currentRow.purchaseOrderNumber }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ currentRow.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="结算方式">{{ settlementTypeLabel(currentRow.settlementType) }}</el-descriptions-item>
          <el-descriptions-item label="申请金额">
            {{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="付款方式">{{ currentRow.paymentMethod || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(currentRow.status)" size="small">
              {{ statusLabel(currentRow.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDate(currentRow.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="申请备注" :span="2">{{ currentRow.remark || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审批意见" :span="2">{{ currentRow.reviewNote || '—' }}</el-descriptions-item>
          <el-descriptions-item label="拒绝原因" :span="2">{{ currentRow.rejectReason || '—' }}</el-descriptions-item>
          <el-descriptions-item label="付款凭证" :span="2">{{ currentRow.evidence || '—' }}</el-descriptions-item>
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
type SettlementType = 'PREPAID' | 'CREDIT_FIRST' | 'NO_INVOICE' | 'MONTHLY' | 'CREDIT_PERIOD' | 'IMMEDIATE'
type RequestStatus = 'PENDING' | 'FINANCE_REVIEW' | 'APPROVED' | 'PAID' | 'REJECTED'

interface PaymentRequestRow {
  id: string
  requestNumber: string
  purchaseOrderId: string
  purchaseOrderNumber: string
  supplierId: string
  supplierName: string
  settlementType: SettlementType
  amount: number | null   // @PriceSensitive — null when role lacks price:view
  paymentMethod: string | null
  status: RequestStatus
  remark: string | null
  reviewNote: string | null
  rejectReason: string | null
  evidence: string | null
  createdAt: string | null
  updatedAt: string | null
}

// ─── Store ────────────────────────────────────────────────────
const authStore = useAuthStore()
const permStore = usePermissionStore()
const factoryId = computed(() => authStore.factoryId)
const canWrite = computed(() => permStore.canWrite('procurement'))

// 角色判断：财务审批和出纳付款需要特定角色
const userRole = computed(() => authStore.role)
const isFinanceManager = computed(() =>
  ['factory_super_admin', 'platform_admin', 'finance_manager'].includes(userRole.value || '')
)
const isCashier = computed(() =>
  ['factory_super_admin', 'platform_admin', 'finance_manager', 'cashier'].includes(userRole.value || '')
)

// ─── 状态 ────────────────────────────────────────────────────
const loading = ref(false)
const submitting = ref(false)
const tableData = ref<PaymentRequestRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const filterStatus = ref('')
const filterSettlementType = ref('')
const filterKeyword = ref('')

const submitDialogVisible = ref(false)
const approveDialogVisible = ref(false)
const markPaidDialogVisible = ref(false)
const detailDialogVisible = ref(false)
const currentRow = ref<PaymentRequestRow | null>(null)

// 审批表单
const approveFormRef = ref<FormInstance>()
const approveForm = ref({ action: '', rejectReason: '', rejectNote: '', reviewNote: '' })
const approveRules: FormRules = {
  action: [{ required: true, message: '请选择审批结果', trigger: 'change' }]
}

// 付款表单
const markPaidFormRef = ref<FormInstance>()
const markPaidForm = ref({ evidence: '' })
const markPaidRules: FormRules = {
  evidence: [{ required: true, message: '请填写付款凭证', trigger: 'blur' }]
}

// ─── 标签映射 ────────────────────────────────────────────────
function settlementTypeLabel(t: string): string {
  const map: Record<string, string> = {
    PREPAID: '预付款',
    CREDIT_FIRST: '先货后款',
    NO_INVOICE: '无票结算',
    MONTHLY: '月结',
    CREDIT_PERIOD: '信用期',
    IMMEDIATE: '即时付款'
  }
  return map[t] ?? t
}

function statusLabel(s: string): string {
  const map: Record<string, string> = {
    PENDING: '待提交',
    FINANCE_REVIEW: '财务审核中',
    APPROVED: '已批准',
    PAID: '已付款',
    REJECTED: '已拒绝'
  }
  return map[s] ?? s
}

function statusTag(s: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  const map: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
    PENDING: 'info',
    FINANCE_REVIEW: 'warning',
    APPROVED: '',
    PAID: 'success',
    REJECTED: 'danger'
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
    if (filterSettlementType.value) params.settlementType = filterSettlementType.value
    if (filterKeyword.value) params.keyword = filterKeyword.value

    const res = await get(`/${factoryId.value}/payment-requests`, params)
    if (res.success) {
      const data = res.data
      if (data && typeof data === 'object' && 'content' in data) {
        tableData.value = (data as { content: PaymentRequestRow[]; totalElements: number }).content
        total.value = (data as { content: PaymentRequestRow[]; totalElements: number }).totalElements
      } else if (Array.isArray(data)) {
        tableData.value = data as PaymentRequestRow[]
        total.value = data.length
      }
    }
  } catch (err) {
    console.error('获取付款申请列表失败', err)
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
  filterSettlementType.value = ''
  filterKeyword.value = ''
  handleFilterChange()
}

// ─── 提交审核 ────────────────────────────────────────────────
function openSubmitDialog(row: PaymentRequestRow) {
  currentRow.value = row
  submitDialogVisible.value = true
}

async function doSubmit() {
  if (!currentRow.value || !factoryId.value) return
  submitting.value = true
  try {
    const res = await put(`/${factoryId.value}/payment-requests/${currentRow.value.id}/submit`, {})
    if (res.success) {
      ElMessage({ message: '已提交财务审核', type: 'success', duration: 3000 })
      submitDialogVisible.value = false
      fetchData()
    } else {
      ElMessage({ message: res.message || '提交失败', type: 'error', duration: 0, showClose: true })
    }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '提交失败，请检查网络'
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
  } finally {
    submitting.value = false
  }
}

// ─── 财务审批 ────────────────────────────────────────────────
function openApproveDialog(row: PaymentRequestRow) {
  currentRow.value = row
  approveForm.value = { action: '', rejectReason: '', rejectNote: '', reviewNote: '' }
  approveFormRef.value?.resetFields()
  approveDialogVisible.value = true
}

async function doApprove() {
  if (!approveFormRef.value || !currentRow.value || !factoryId.value) return
  await approveFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      let res
      if (approveForm.value.action === 'approve') {
        res = await put(
          `/${factoryId.value}/payment-requests/${currentRow.value!.id}/finance-approve`,
          { reviewNote: approveForm.value.reviewNote || null }
        )
      } else {
        const reason = approveForm.value.rejectReason === '_other'
          ? approveForm.value.rejectNote
          : approveForm.value.rejectReason
        res = await put(
          `/${factoryId.value}/payment-requests/${currentRow.value!.id}/reject`,
          { rejectReason: reason || null }
        )
      }
      if (res.success) {
        ElMessage({
          message: approveForm.value.action === 'approve' ? '已批准付款申请' : '已拒绝付款申请',
          type: 'success',
          duration: 3000
        })
        approveDialogVisible.value = false
        fetchData()
      } else {
        ElMessage({ message: res.message || '操作失败', type: 'error', duration: 0, showClose: true })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '操作失败，请检查网络'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      submitting.value = false
    }
  })
}

// ─── 确认付款 ────────────────────────────────────────────────
function openMarkPaidDialog(row: PaymentRequestRow) {
  currentRow.value = row
  markPaidForm.value = { evidence: '' }
  markPaidFormRef.value?.resetFields()
  markPaidDialogVisible.value = true
}

async function doMarkPaid() {
  if (!markPaidFormRef.value || !currentRow.value || !factoryId.value) return
  await markPaidFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const res = await put(
        `/${factoryId.value}/payment-requests/${currentRow.value!.id}/mark-paid`,
        { evidence: markPaidForm.value.evidence }
      )
      if (res.success) {
        ElMessage({ message: '付款已确认，账款已更新', type: 'success', duration: 3000 })
        markPaidDialogVisible.value = false
        fetchData()
      } else {
        ElMessage({ message: res.message || '付款确认失败', type: 'error', duration: 0, showClose: true })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '付款确认失败，请检查网络'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      submitting.value = false
    }
  })
}

// ─── 详情 ────────────────────────────────────────────────────
function openDetailDialog(row: PaymentRequestRow) {
  currentRow.value = row
  detailDialogVisible.value = true
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

.price-hidden {
  color: #C0C4CC;
  font-style: italic;
}

.dialog-context {
  margin-bottom: 4px;
}
</style>
