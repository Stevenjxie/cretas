<template>
  <div class="page-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2 class="page-title">销售付款申请</h2>
      <p class="page-subtitle">对客户的 outbound 付款（退款/返利/销售费用） — 销售方向</p>
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
        <el-col :span="6">
          <el-input
            v-model="filterKeyword"
            placeholder="搜索客户名/申请单号"
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
          <!-- Rule 4: 仅销售员可发起 sales:read_write -->
          <el-button
            v-if="canSalesWrite"
            type="success"
            @click="openCreateDialog"
          >
            + 新建销售付款申请
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
        <el-table-column label="申请单号" prop="requestNumber" width="170" />
        <!-- 销售方向: 显示销售订单号和客户名 -->
        <el-table-column label="销售订单" prop="salesOrderId" width="160">
          <template #default="{ row }">
            {{ row.salesOrderId || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="客户" prop="customerName" min-width="120">
          <template #default="{ row }">
            {{ row.customerName || row.customerId || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="付款类型">
          <template #default>
            <el-tag type="warning" size="small">销售付款</el-tag>
          </template>
        </el-table-column>
        <!-- @PriceSensitive: amount 字段后端对无权限角色返回 null -->
        <el-table-column label="申请金额" prop="amount" width="120">
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
        <el-table-column label="状态" prop="status" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createdAt" width="155">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <!-- 提交审核：PENDING → FINANCE_REVIEW (sales:read_write) -->
            <el-button
              v-if="row.status === 'PENDING' && canSalesWrite"
              type="primary"
              link
              size="small"
              @click="openSubmitDialog(row)"
            >
              提交审核
            </el-button>
            <!-- 财务审批：FINANCE_REVIEW (finance:read_write) -->
            <el-button
              v-if="row.status === 'FINANCE_REVIEW' && isFinanceManager"
              type="success"
              link
              size="small"
              @click="openApproveDialog(row)"
            >
              审批
            </el-button>
            <!-- 出纳付款：APPROVED → PAID (finance:read_write / cashier) -->
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

    <!-- ===== 新建销售付款申请 Dialog ===== -->
    <!-- Rule 2: 含销售订单号、客户、金额上下文 -->
    <el-dialog
      v-model="createDialogVisible"
      title="新建销售付款申请"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="info"
        :closable="false"
        style="margin-bottom: 16px"
      >
        销售方向付款：对客户的 outbound 退款/返利/销售费用。
        销售订单号可空（无单退款/费用场景）；客户必填。
        同一销售订单已有活跃销售付款申请时系统自动返回 409。
      </el-alert>

      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-width="110px"
      >
        <el-form-item label="客户" prop="customerId">
          <el-select
            v-model="createForm.customerId"
            filterable
            placeholder="搜索客户名称（必填）"
            style="width: 100%"
          >
            <el-option v-for="c in customerOptions" :key="c.id" :label="c.name" :value="c.id" />
            <template #empty>
              <div style="padding:8px 12px;color:#909399">暂无客户，请先在客户管理创建</div>
            </template>
          </el-select>
        </el-form-item>
        <el-form-item label="销售订单" prop="salesOrderId">
          <el-select
            v-model="createForm.salesOrderId"
            filterable
            clearable
            placeholder="搜索订单号（选填，无单付款可不填）"
            style="width: 100%"
          >
            <el-option
              v-for="o in filteredSalesOrderOptions"
              :key="o.id"
              :value="o.id"
              :label="o.customerName ? `${o.orderNumber} · ${o.customerName}` : o.orderNumber"
            />
            <template #empty>
              <div style="padding:8px 12px;color:#909399">暂无销售订单</div>
            </template>
          </el-select>
        </el-form-item>
        <el-form-item label="申请金额" prop="amount">
          <el-input-number
            v-model="createForm.amount"
            :min="0.01"
            :precision="2"
            :step="100"
            style="width: 100%"
            placeholder="申请金额（元）"
          />
        </el-form-item>
        <!-- Rule 3: 付款方式 dropdown -->
        <el-form-item label="付款方式" prop="paymentMethod">
          <el-select
            v-model="createForm.paymentMethod"
            placeholder="请选择付款方式"
            style="width: 100%"
          >
            <el-option label="银行转账" value="BANK_TRANSFER" />
            <el-option label="现金" value="CASH" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="支付宝" value="ALIPAY" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="createForm.remark"
            type="textarea"
            :rows="2"
            placeholder="付款用途说明（建议填写，便于财务审批）"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="doCreate">
          提交申请
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== 提交审核 Dialog ===== -->
    <el-dialog
      v-model="submitDialogVisible"
      :title="`提交审核 — ${currentRow?.customerName || currentRow?.customerId || ''} (${currentRow?.requestNumber || ''})`"
      width="440px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="dialog-context">
        <el-alert type="info" :closable="false" style="margin-bottom: 16px">
          申请金额：<strong>{{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}</strong>
          &nbsp;| 销售订单：{{ currentRow.salesOrderId || '无单付款' }}
        </el-alert>
        <p style="color: #606266; font-size: 14px; margin: 0">提交后将进入财务审核流程，是否确认？</p>
      </div>
      <template #footer>
        <el-button @click="submitDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doSubmit">确认提交</el-button>
      </template>
    </el-dialog>

    <!-- ===== 财务审批 Dialog ===== -->
    <!-- Rule 2: 含客户+金额+单号; Rule 3: 拒绝原因 dropdown -->
    <el-dialog
      v-model="approveDialogVisible"
      :title="`财务审批 — ${currentRow?.customerName || currentRow?.customerId || ''} (${currentRow?.requestNumber || ''})`"
      width="520px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="dialog-context">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="客户">{{ currentRow.customerName || currentRow.customerId }}</el-descriptions-item>
          <el-descriptions-item label="销售订单">{{ currentRow.salesOrderId || '无单付款' }}</el-descriptions-item>
          <el-descriptions-item label="申请金额">
            {{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="付款方式">{{ currentRow.paymentMethod || '—' }}</el-descriptions-item>
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
        <!-- Rule 3: 拒绝原因 dropdown -->
        <el-form-item
          v-if="approveForm.action === 'reject'"
          label="拒绝原因"
          prop="rejectReason"
        >
          <el-select v-model="approveForm.rejectReason" placeholder="请选择拒绝原因" style="width: 100%">
            <el-option label="金额不符" value="金额不符" />
            <el-option label="客户信息有误" value="客户信息有误" />
            <el-option label="缺少凭证" value="缺少凭证" />
            <el-option label="超出预算" value="超出预算" />
            <el-option label="不符合付款条件" value="不符合付款条件" />
            <el-option label="其他原因" value="_other" />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="approveForm.action === 'reject' && approveForm.rejectReason === '_other'"
          label="补充说明"
          prop="rejectNote"
        >
          <el-input v-model="approveForm.rejectNote" type="textarea" :rows="2" placeholder="请说明具体原因" />
        </el-form-item>
        <el-form-item label="审批意见" prop="reviewNote">
          <el-input v-model="approveForm.reviewNote" type="textarea" :rows="2" placeholder="审批意见（可选）" />
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

    <!-- ===== 确认付款 Dialog ===== -->
    <!-- Rule 1: 金额上限显示; Rule 2: 含客户+金额+单号 -->
    <el-dialog
      v-model="markPaidDialogVisible"
      :title="`确认付款 — ${currentRow?.customerName || currentRow?.customerId || ''} (${currentRow?.requestNumber || ''})`"
      width="480px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="dialog-context">
        <el-alert type="warning" :closable="false" style="margin-bottom: 16px">
          <strong>付款金额：{{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}</strong>
          &nbsp; 客户：{{ currentRow.customerName || currentRow.customerId }}
        </el-alert>
        <el-alert type="info" :closable="false" style="margin-bottom: 16px">
          确认后将自动记录到应收账款并更新客户余额，此操作不可撤销。
        </el-alert>
      </div>

      <el-form ref="markPaidFormRef" :model="markPaidForm" :rules="markPaidRules" label-width="90px">
        <el-form-item label="付款凭证" prop="evidence">
          <el-input v-model="markPaidForm.evidence" placeholder="请填写付款凭证号或说明" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="markPaidDialogVisible = false">取消</el-button>
        <el-button type="warning" :loading="submitting" @click="doMarkPaid">
          确认已付款
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== 详情 Dialog ===== -->
    <el-dialog
      v-model="detailDialogVisible"
      :title="`销售付款申请详情 — ${currentRow?.requestNumber || ''}`"
      width="620px"
    >
      <div v-if="currentRow">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="申请单号">{{ currentRow.requestNumber }}</el-descriptions-item>
          <el-descriptions-item label="付款方向">
            <el-tag type="warning" size="small">销售付款</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="客户">{{ currentRow.customerName || currentRow.customerId }}</el-descriptions-item>
          <el-descriptions-item label="销售订单">{{ currentRow.salesOrderId || '无单付款' }}</el-descriptions-item>
          <el-descriptions-item label="申请金额">
            {{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="付款方式">{{ currentRow.paymentMethod || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(currentRow.status)" size="small">{{ statusLabel(currentRow.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDate(currentRow.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="申请备注" :span="2">{{ currentRow.remark || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审批意见" :span="2">{{ currentRow.financeReviewNote || '—' }}</el-descriptions-item>
          <el-descriptions-item label="拒绝原因" :span="2">{{ currentRow.rejectReason || '—' }}</el-descriptions-item>
          <el-descriptions-item label="付款凭证" :span="2">{{ currentRow.arapTransactionId || '—' }}</el-descriptions-item>
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

// ─── 类型定义 ────────────────────────────────────────────────
type RequestStatus = 'PENDING' | 'FINANCE_REVIEW' | 'APPROVED' | 'PAID' | 'REJECTED'

interface SalesPaymentRequestRow {
  id: string
  requestNumber: string
  sourceType: 'SALES' | 'PURCHASE'
  salesOrderId: string | null
  customerId: string | null
  customerName: string | null   // enriched by backend (if available)
  amount: number | null          // @PriceSensitive — null when role lacks price:view
  paymentMethod: string | null
  status: RequestStatus
  remark: string | null
  financeReviewNote: string | null
  rejectReason: string | null
  arapTransactionId: string | null
  createdAt: string | null
}

// ─── Store ────────────────────────────────────────────────────
const authStore = useAuthStore()
const permStore = usePermissionStore()
const factoryId = computed(() => authStore.factoryId)
// 销售付款由 sales:read_write 发起
const canSalesWrite = computed(() => permStore.canWrite('sales'))

const userRole = computed(() => authStore.currentRole)
const isFinanceManager = computed(() =>
  ['factory_super_admin', 'platform_admin', 'finance_manager'].includes(userRole.value || '')
)
const isCashier = computed(() =>
  ['factory_super_admin', 'platform_admin', 'finance_manager', 'cashier'].includes(userRole.value || '')
)

// ─── 列表状态 ────────────────────────────────────────────────
const loading = ref(false)
const submitting = ref(false)
const tableData = ref<SalesPaymentRequestRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const filterStatus = ref('')
const filterKeyword = ref('')

// ─── Dialog 状态 ─────────────────────────────────────────────
const createDialogVisible = ref(false)
const createSubmitting = ref(false)
const submitDialogVisible = ref(false)
const approveDialogVisible = ref(false)
const markPaidDialogVisible = ref(false)
const detailDialogVisible = ref(false)
const currentRow = ref<SalesPaymentRequestRow | null>(null)

// ─── 表单定义 ────────────────────────────────────────────────
const createFormRef = ref<FormInstance>()
const createForm = ref({
  customerId: '',
  salesOrderId: '',
  amount: undefined as number | undefined,
  paymentMethod: '',
  remark: ''
})
const createRules: FormRules = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
  amount: [{ required: true, message: '请输入申请金额', trigger: 'blur' }],
  paymentMethod: [{ required: true, message: '请选择付款方式', trigger: 'change' }]
}

// fool-proof-design Rule 2 (身份而非裸 UUID): 客户 / 销售订单 由裸 UUID 输入改为按名称/单号
// 搜索的 el-select — mirror views/sales/shipments/list.vue (customerOptions) 和
// views/finance/payments/list.vue (salesOrderOptions) 已有的同款 pattern。
interface CustomerOption { id: string; name: string }
interface SalesOrderOption { id: string; orderNumber: string; customerId?: string; customerName?: string }
const customerOptions = ref<CustomerOption[]>([])
const salesOrderOptions = ref<SalesOrderOption[]>([])

async function loadCustomerOptions() {
  if (!factoryId.value) return
  try {
    const res = await get(`/${factoryId.value}/customers`, { params: { page: 1, size: 200 } })
    if (res.success && res.data) {
      const list = (res.data.content || []) as Array<{ id?: string; name?: string }>
      customerOptions.value = list
        .filter((c) => c.id && c.name)
        .map((c) => ({ id: String(c.id), name: String(c.name) }))
    }
  } catch { /* fall back to empty select */ }
}

async function loadSalesOrderOptions() {
  if (!factoryId.value) return
  try {
    // 端点为 /sales/orders (SalesController), 不是 /sales-orders.
    const res = await get(`/${factoryId.value}/sales/orders`, { params: { page: 1, size: 200 } })
    if (res.success && res.data) {
      const list = (res.data.content || []) as Array<{
        id?: string; orderNumber?: string; customerId?: string; customerName?: string
      }>
      salesOrderOptions.value = list
        .filter((o) => o.id && o.orderNumber)
        .map((o) => ({
          id: String(o.id),
          orderNumber: String(o.orderNumber),
          customerId: o.customerId ? String(o.customerId) : undefined,
          customerName: o.customerName ? String(o.customerName) : undefined
        }))
    }
  } catch { /* fall back to empty select */ }
}

// 选客户后, 销售订单下拉只显示该客户名下的订单 (仍保留全量作为 fallback, 防止 customerId
// 关联缺失时误藏订单).
const filteredSalesOrderOptions = computed(() => {
  if (!createForm.value.customerId) return salesOrderOptions.value
  const matched = salesOrderOptions.value.filter((o) => o.customerId === createForm.value.customerId)
  return matched.length > 0 ? matched : salesOrderOptions.value
})

const approveFormRef = ref<FormInstance>()
const approveForm = ref({ action: '', rejectReason: '', rejectNote: '', reviewNote: '' })
const approveRules: FormRules = {
  action: [{ required: true, message: '请选择审批结果', trigger: 'change' }]
}

const markPaidFormRef = ref<FormInstance>()
const markPaidForm = ref({ evidence: '' })
const markPaidRules: FormRules = {
  evidence: [{ required: true, message: '请填写付款凭证', trigger: 'blur' }]
}

// ─── 标签映射 ────────────────────────────────────────────────
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
    // 后端端点: GET /api/mobile/{factoryId}/payment-requests
    //   ?status=&keyword= (全状态列表，前端再用 sourceType=SALES 过滤)
    //   后端 listByFactory 返回当前工厂全部 PaymentRequest，我们在前端按 sourceType 过滤
    const params: Record<string, unknown> = {}
    if (filterStatus.value) params.status = filterStatus.value
    if (filterKeyword.value) params.keyword = filterKeyword.value
    // 明确告诉后端只取 SALES 方向 (若后端支持; 否则前端过滤)
    params.sourceType = 'SALES'

    const res = await get(`/${factoryId.value}/payment-requests`, params)
    if (res.success) {
      const raw = Array.isArray(res.data)
        ? res.data
        : (res.data as { content?: SalesPaymentRequestRow[] })?.content ?? []
      // 前端二次过滤确保只展示 SALES 方向（防止后端未支持 sourceType 参数）
      tableData.value = (raw as SalesPaymentRequestRow[]).filter(
        (r) => r.sourceType === 'SALES'
      )
      total.value = tableData.value.length
    }
  } catch (err) {
    console.error('获取销售付款申请列表失败', err)
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

// ─── 新建销售付款申请 ─────────────────────────────────────────
function openCreateDialog() {
  createForm.value = {
    customerId: '',
    salesOrderId: '',
    amount: undefined,
    paymentMethod: '',
    remark: ''
  }
  createFormRef.value?.resetFields()
  createDialogVisible.value = true
  if (customerOptions.value.length === 0) loadCustomerOptions()
  if (salesOrderOptions.value.length === 0) loadSalesOrderOptions()
}

async function doCreate() {
  if (!createFormRef.value || !factoryId.value) return
  await createFormRef.value.validate(async (valid) => {
    if (!valid) return
    createSubmitting.value = true
    try {
      // 后端端点: POST /api/mobile/{factoryId}/payment-requests/sales
      const body: Record<string, unknown> = {
        customerId: createForm.value.customerId,
        amount: createForm.value.amount,
        paymentMethod: createForm.value.paymentMethod
      }
      if (createForm.value.salesOrderId) body.salesOrderId = createForm.value.salesOrderId
      if (createForm.value.remark) body.remark = createForm.value.remark

      const res = await post(`/${factoryId.value}/payment-requests/sales`, body)
      if (res.success) {
        ElMessage({ message: '销售付款申请已创建', type: 'success', duration: 3000 })
        createDialogVisible.value = false
        fetchData()
      } else {
        ElMessage({ message: res.message || '创建失败', type: 'error', duration: 0, showClose: true })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '创建失败'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      createSubmitting.value = false
    }
  })
}

// ─── 提交审核 ────────────────────────────────────────────────
function openSubmitDialog(row: SalesPaymentRequestRow) {
  currentRow.value = row
  submitDialogVisible.value = true
}

async function doSubmit() {
  if (!currentRow.value || !factoryId.value) return
  submitting.value = true
  try {
    // 后端端点: PUT /api/mobile/{factoryId}/payment-requests/{id}/submit
    const res = await put(`/${factoryId.value}/payment-requests/${currentRow.value.id}/submit`, {})
    if (res.success) {
      ElMessage({ message: '已提交财务审核', type: 'success', duration: 3000 })
      submitDialogVisible.value = false
      fetchData()
    } else {
      ElMessage({ message: res.message || '提交失败', type: 'error', duration: 0, showClose: true })
    }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '提交失败'
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
  } finally {
    submitting.value = false
  }
}

// ─── 财务审批 ────────────────────────────────────────────────
function openApproveDialog(row: SalesPaymentRequestRow) {
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
        // 后端端点: PUT /api/mobile/{factoryId}/payment-requests/{id}/finance-approve
        res = await put(
          `/${factoryId.value}/payment-requests/${currentRow.value!.id}/finance-approve`,
          { reviewNote: approveForm.value.reviewNote || null }
        )
      } else {
        const reason = approveForm.value.rejectReason === '_other'
          ? approveForm.value.rejectNote
          : approveForm.value.rejectReason
        // 后端端点: PUT /api/mobile/{factoryId}/payment-requests/{id}/reject
        res = await put(
          `/${factoryId.value}/payment-requests/${currentRow.value!.id}/reject`,
          { rejectReason: reason || null }
        )
      }
      if (res.success) {
        ElMessage({
          message: approveForm.value.action === 'approve' ? '已批准销售付款申请' : '已拒绝销售付款申请',
          type: 'success',
          duration: 3000
        })
        approveDialogVisible.value = false
        fetchData()
      } else {
        ElMessage({ message: res.message || '操作失败', type: 'error', duration: 0, showClose: true })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '操作失败'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      submitting.value = false
    }
  })
}

// ─── 确认付款 ────────────────────────────────────────────────
function openMarkPaidDialog(row: SalesPaymentRequestRow) {
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
      // 后端端点: PUT /api/mobile/{factoryId}/payment-requests/{id}/mark-paid
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
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '付款确认失败'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      submitting.value = false
    }
  })
}

// ─── 详情 ────────────────────────────────────────────────────
function openDetailDialog(row: SalesPaymentRequestRow) {
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
