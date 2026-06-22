<script setup lang="ts">
/**
 * Sprint2-J P-FIN-1 follow-up (Chat 6 Vue) — 财审详情页 (PC).
 *
 * 镜像 RN PurchaseOrderFinanceReviewScreen.tsx 的业务流程:
 *   - 加载订单 + 三价对比
 *   - priceAlert=true 行 #FFE4E1 红底 + #C62828 红色偏差
 *   - approve (notes 可选) / reject (notes 必填)
 *   - 非 PENDING_FINANCE_REVIEW 状态显示历史 financeReviewNotes
 *
 * RBAC: v-if="canFinanceWrite" 隐藏 approve/reject 按钮.
 * 后端 @RequirePermission("finance:read_write") 强制 — UI 仅是友好遮挡.
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft } from '@element-plus/icons-vue';
import {
  getOrderDetail,
  getOrderPriceComparison,
  financeApprove,
  financeReject,
  type PurchaseOrderSummary,
  type MaterialPriceComparison,
} from '@/api/purchaseFinanceReview';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const orderId = computed(() => String(route.params.id || ''));

/** 后端 @RequirePermission("finance:read_write") — UI 镜像. */
const canFinanceWrite = computed(() => permissionStore.canWrite('finance'));

const order = ref<PurchaseOrderSummary | null>(null);
const priceComparisons = ref<MaterialPriceComparison[]>([]);
const notes = ref('');
const loading = ref(false);
const submitting = ref(false);

// Rule 3: 驳回原因改 dropdown，与 payment-requests/list.vue 对齐
const FINANCE_REJECT_REASONS = [
  { value: '金额超出预算', label: '金额超出预算' },
  { value: '缺少发票凭证', label: '缺少发票凭证' },
  { value: '供应商信息不符', label: '供应商信息不符' },
  { value: '付款条件不符合协议', label: '付款条件不符合协议' },
  { value: '采购单状态异常', label: '采购单状态异常' },
  { value: '_other', label: '其他原因' },
];
const rejectReasonSelect = ref('');
const rejectOtherText = ref('');

const alertCount = computed(() =>
  priceComparisons.value.filter((p) => p.priceAlert).length,
);

const canReview = computed(
  () => order.value?.status === 'PENDING_FINANCE_REVIEW',
);

async function load() {
  if (!factoryId.value || !orderId.value) return;
  loading.value = true;
  try {
    const [orderRes, priceRes] = await Promise.all([
      getOrderDetail(factoryId.value, orderId.value),
      getOrderPriceComparison(factoryId.value, orderId.value),
    ]);
    if (orderRes.success && orderRes.data) order.value = orderRes.data;
    if (priceRes.success && priceRes.data) priceComparisons.value = priceRes.data;
  } finally {
    loading.value = false;
  }
}

function formatAmount(v: number | null | undefined): string {
  if (v == null) return '-';
  return `¥${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatVariance(v: number | null): string {
  if (v == null) return '-';
  const sign = v >= 0 ? '+' : '';
  return `${sign}${v.toFixed(2)}%`;
}

function rowClassName({ row }: { row: MaterialPriceComparison }): string {
  return row.priceAlert ? 'price-alert-row' : '';
}

async function handleApprove() {
  try {
    await ElMessageBox.confirm(
      `确认通过采购单 ${order.value?.orderNumber} 的财务审核?`,
      '财务审核通过',
      { type: 'success', confirmButtonText: '确认通过', cancelButtonText: '取消' },
    );
  } catch {
    return; // user cancelled
  }
  submitting.value = true;
  try {
    const res = await financeApprove(factoryId.value, orderId.value, notes.value || undefined);
    if (res.success) {
      ElMessage.success('财务审核已通过');
      router.back();
    }
  } finally {
    submitting.value = false;
  }
}

async function handleReject() {
  if (!rejectReasonSelect.value) {
    ElMessage.warning('驳回必须选择原因');
    return;
  }
  if (rejectReasonSelect.value === '_other' && !rejectOtherText.value.trim()) {
    ElMessage.warning('请填写具体驳回原因');
    return;
  }
  const rejectNote =
    rejectReasonSelect.value === '_other'
      ? rejectOtherText.value.trim()
      : rejectReasonSelect.value;
  try {
    await ElMessageBox.confirm(
      `确认驳回采购单 ${order.value?.orderNumber}?\n驳回原因: ${rejectNote}`,
      '财务驳回',
      { type: 'warning', confirmButtonText: '确认驳回', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    const res = await financeReject(factoryId.value, orderId.value, rejectNote);
    if (res.success) {
      ElMessage.success('已驳回, 订单退回采购员');
      router.back();
    }
  } finally {
    submitting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="finance-review-detail">
    <div class="page-header">
      <el-button :icon="ArrowLeft" link @click="router.back()">返回</el-button>
      <h2 class="title">财务审核 · {{ order?.orderNumber || '—' }}</h2>
    </div>

    <!-- 摘要 -->
    <el-card v-if="order" shadow="never" class="summary-card">
      <div class="summary-grid">
        <div>
          <div class="label">订单号</div>
          <div class="value">{{ order.orderNumber }}</div>
        </div>
        <div>
          <div class="label">供应商</div>
          <div class="value">{{ order.supplierName || order.supplierId }}</div>
        </div>
        <div>
          <div class="label">总金额</div>
          <div class="value">{{ formatAmount(order.totalAmount) }}</div>
        </div>
        <div>
          <div class="label">下单日期</div>
          <div class="value">{{ order.orderDate }}</div>
        </div>
        <div>
          <div class="label">状态</div>
          <div class="value">
            <el-tag :type="canReview ? 'warning' : 'info'">{{ order.status }}</el-tag>
          </div>
        </div>
        <div>
          <div class="label">三价标红</div>
          <div class="value">
            <el-tag v-if="alertCount > 0" type="danger" effect="dark">
              {{ alertCount }} 项偏差超阈值
            </el-tag>
            <el-tag v-else type="success">价格正常</el-tag>
          </div>
        </div>
      </div>
      <el-alert
        v-if="!canReview"
        type="info"
        :closable="false"
        :title="`仅 PENDING_FINANCE_REVIEW 状态可审核 (当前: ${order.status})`"
        style="margin-top: 12px"
      />
    </el-card>

    <!-- 三价对比 -->
    <el-card shadow="never" class="comparison-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">三价对比</span>
          <span class="card-subtitle">
            {{ priceComparisons.length }} 项 · 标红 {{ alertCount }}
          </span>
        </div>
      </template>
      <el-table
        :data="priceComparisons"
        :row-class-name="rowClassName"
        stripe
        empty-text="无明细"
      >
        <el-table-column prop="materialName" label="物料" min-width="180">
          <template #default="{ row }">
            <span :class="{ 'alert-text': row.priceAlert }">
              {{ row.materialName || row.materialTypeId }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="BOM 标准价" align="right" min-width="130">
          <template #default="{ row }">{{ formatAmount(row.bomStandardPrice) }}</template>
        </el-table-column>
        <el-table-column label="移动均价" align="right" min-width="130">
          <template #default="{ row }">{{ formatAmount(row.movingAvgPrice) }}</template>
        </el-table-column>
        <el-table-column label="当前价" align="right" min-width="130">
          <template #default="{ row }">{{ formatAmount(row.currentPrice) }}</template>
        </el-table-column>
        <el-table-column label="vs BOM" align="right" min-width="110">
          <template #default="{ row }">
            <span :class="{ 'alert-text': row.priceAlert }">
              {{ formatVariance(row.varianceFromBom) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="vs 移动均" align="right" min-width="120">
          <template #default="{ row }">
            <span :class="{ 'alert-text': row.priceAlert }">
              {{ formatVariance(row.varianceFromAvg) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <div
        v-if="priceComparisons.some((p) => p.dataSourceHint)"
        class="hint-box"
      >
        ℹ 部分价格缺失见明细。BOM/移动均价基于历史 BOM 配置和入库累积。
      </div>
    </el-card>

    <!-- 审核操作 -->
    <el-card v-if="canReview && canFinanceWrite" shadow="never" class="action-card">
      <template #header>
        <span class="card-title">审核意见</span>
      </template>
      <!-- 通过: 可选备注 -->
      <el-form label-width="90px">
        <el-form-item label="通过备注">
          <el-input
            v-model="notes"
            type="textarea"
            :rows="2"
            placeholder="通过可留空"
            maxlength="500"
            show-word-limit
            :disabled="submitting"
          />
        </el-form-item>
        <!-- Rule 3: 驳回原因改 dropdown -->
        <el-form-item label="驳回原因" required>
          <el-select
            v-model="rejectReasonSelect"
            placeholder="请选择驳回原因"
            style="width: 100%"
            :disabled="submitting"
          >
            <el-option
              v-for="opt in FINANCE_REJECT_REASONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="rejectReasonSelect === '_other'" label="具体原因" required>
          <el-input
            v-model="rejectOtherText"
            type="textarea"
            :rows="2"
            placeholder="请填写具体驳回原因"
            maxlength="200"
            show-word-limit
            :disabled="submitting"
          />
        </el-form-item>
      </el-form>
      <div class="action-row">
        <el-button
          plain
          type="danger"
          :loading="submitting"
          @click="handleReject"
        >
          驳回
        </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          @click="handleApprove"
        >
          通过
        </el-button>
      </div>
    </el-card>

    <!-- 非审核状态: 显示历史审核 -->
    <el-card
      v-if="!canReview && order?.financeReviewNotes"
      shadow="never"
      class="action-card"
    >
      <template #header>
        <span class="card-title">历史审核意见</span>
      </template>
      <p class="history-notes">{{ order.financeReviewNotes }}</p>
      <p v-if="order.financeReviewedAt" class="history-meta">
        审核于 {{ order.financeReviewedAt }}
      </p>
    </el-card>

    <!-- 非审核可见但当前角色无权写: 提示 -->
    <el-alert
      v-if="canReview && !canFinanceWrite"
      type="warning"
      :closable="false"
      title="当前角色无财务写权限, 仅可查看"
      description="approve/reject 需要 finance:read_write (后端 @RequirePermission 强制)"
      style="margin-top: 12px"
    />
  </div>
</template>

<style scoped>
.finance-review-detail {
  padding: 20px;
}
.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
  color: #303133;
}
.summary-card,
.comparison-card,
.action-card {
  margin-bottom: 16px;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
}
.summary-grid .label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
.summary-grid .value {
  font-size: 15px;
  color: #303133;
  font-weight: 500;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.card-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.card-subtitle {
  font-size: 12px;
  color: #909399;
}
.action-row {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}
.hint-box {
  margin-top: 12px;
  padding: 8px 12px;
  background: #f9f9f9;
  border-radius: 4px;
  font-size: 12px;
  color: #666;
}
.history-notes {
  margin: 0;
  color: #303133;
  white-space: pre-wrap;
}
.history-meta {
  margin: 8px 0 0;
  font-size: 12px;
  color: #909399;
}

/* 三价标红 — 红行 + 红字, 匹配 RN screen 的 #FFE4E1 / #C62828 */
:deep(.price-alert-row) {
  background-color: #ffe4e1 !important;
}
:deep(.price-alert-row td) {
  background-color: #ffe4e1 !important;
}
.alert-text {
  color: #c62828;
  font-weight: 600;
}
</style>
