<script setup lang="ts">
/**
 * Issue #761 (P3 follow-up #752): 成品库存批次详情页.
 *
 * PR #756 把 list.vue 的 view-detail dead-stub 接成 ElMessageBox.alert,
 * 客户反馈仍想要独立 detail route. 本页提供:
 *   - 基础字段 (batchNumber / product / 数量 / 库位 / 日期)
 *   - 价格信息 (canViewPrice 网控)
 *   - 价格历史 占位 table (后端 /price-history endpoint 待接, P3 follow-up)
 *
 * #809 (2026-05-17): 切换到 PR #791 引入的 GET /sales/finished-goods/{id} endpoint,
 * 取消之前的 list-filter fallback. 大工厂 ( >200 batches) 之前会假报"未找到".
 *
 * T126 (Phase 1): 新增 调整库存 / 作废批次 操作 + 调整历史 section.
 * 注: GET /sales/finished-goods/{id}/adjustments 端点在后端 T126-A 未包含; Phase 1
 * 只展示批次当前状态 (可用/发货/预留). 调整历史 section 保留结构待后续补接.
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft, Refresh } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';
import { ApiError } from '@/types/api';
import {
  adjustFgBatch,
  voidFgBatch,
  type FgAdjustRequest,
  type FgAdjustmentReferenceType,
} from '@/api/finishedGoods';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const batchId = computed(() => String(route.params.id || ''));
const canViewPrice = computed(() => permissionStore.canViewPrice);
// T126: writes need production:read_write OR sales:read_write
const canEdit = computed(
  () => permissionStore.canWrite('production') || permissionStore.canWrite('sales')
);

const loading = ref(false);
const batch = ref<TableRow | null>(null);
// Issue #761 价格历史 — 后端 endpoint 待接, 当前 fallback 显示 batch 自身的 unitPrice
const priceHistory = ref<Array<{ date: string; price: number; source: string }>>([]);
const priceHistoryLoading = ref(false);

onMounted(() => {
  loadBatch();
});

async function loadBatch() {
  if (!factoryId.value || !batchId.value) return;
  loading.value = true;
  try {
    // #809 (2026-05-17): 切到 PR #791 的 GET /sales/finished-goods/{id} endpoint.
    const res = await get<TableRow>(`/${factoryId.value}/sales/finished-goods/${batchId.value}`);
    if (res.success && res.data) {
      batch.value = res.data;
      seedPriceHistory();
    } else {
      batch.value = null;
      ElMessage.warning('未找到该成品批次 (可能已被删除)');
    }
  } catch (e) {
    batch.value = null;
    // 404 → 友好提示 (interceptor 已抛 ApiError, NOT_FOUND code)
    if (e instanceof ApiError && e.status === 404) {
      ElMessage.warning('未找到该成品批次 (可能已被删除)');
    }
    /* 其他错误已由 interceptor 处理 */
  } finally {
    loading.value = false;
  }
}

function seedPriceHistory() {
  if (!batch.value) return;
  const cur = batch.value.unitPrice;
  if (cur != null) {
    priceHistory.value = [
      {
        date: String(batch.value.productionDate || batch.value.createdAt || ''),
        price: Number(cur),
        source: '当前成本单价',
      },
    ];
  } else {
    priceHistory.value = [];
  }
}

function goBack() {
  router.push('/sales/finished-goods');
}

function availableQty(row: TableRow) {
  return (
    (Number(row.producedQuantity) || 0) -
    (Number(row.shippedQuantity) || 0) -
    (Number(row.reservedQuantity) || 0)
  );
}

function statusType(row: TableRow): 'success' | 'warning' | 'danger' {
  const avail = availableQty(row);
  if (avail <= 0) return 'danger';
  if (avail < (Number(row.producedQuantity) || 1) * 0.2) return 'warning';
  return 'success';
}

function statusText(row: TableRow): string {
  const avail = availableQty(row);
  if (avail <= 0) return '已售罄';
  if (avail < (Number(row.producedQuantity) || 1) * 0.2) return '库存低';
  return '充足';
}

// ---------- 调整库存 dialog ----------
const adjustDialogVisible = ref(false);
const adjustSubmitting = ref(false);

const ADJUST_REF_TYPES: Array<{ value: FgAdjustmentReferenceType; label: string }> = [
  { value: 'OPENING_CORRECTION', label: '期初修正' },
  { value: 'STOCKTAKE', label: '盘点差异' },
  { value: 'SCRAP', label: '报废损耗' },
  { value: 'OTHER', label: '其他' },
];

const adjustForm = ref<FgAdjustRequest>({
  adjustmentQuantity: 0,
  reason: '',
  referenceType: 'OPENING_CORRECTION',
});

function openAdjustDialog() {
  adjustForm.value = {
    adjustmentQuantity: 0,
    reason: '',
    referenceType: 'OPENING_CORRECTION',
  };
  adjustDialogVisible.value = true;
}

function adjustAvailableQty(): number {
  if (!batch.value) return 0;
  return availableQty(batch.value);
}

async function submitAdjust() {
  if (!factoryId.value || !batch.value) return;
  if (adjustForm.value.adjustmentQuantity === 0) { ElMessage.warning('调整数量不能为 0'); return; }
  if (!adjustForm.value.reason.trim()) { ElMessage.warning('请填写调整原因'); return; }

  adjustSubmitting.value = true;
  try {
    const res = await adjustFgBatch(
      factoryId.value,
      batchId.value,
      {
        ...adjustForm.value,
        reason: adjustForm.value.reason.trim(),
      }
    );
    if (res.success) {
      ElMessage.success('库存调整成功');
      adjustDialogVisible.value = false;
      void loadBatch();
    } else {
      ElMessage({ message: res.message || '库存调整失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const err = e as {
      response?: { status?: number; data?: { message?: string; actionHint?: string } };
      actionHint?: string;
    };
    const msg = err?.response?.data?.message;
    const hint = err?.response?.data?.actionHint;
    if (!err?.actionHint) {
      const fullMsg = msg
        ? `${msg}${hint ? `\n提示: ${hint}` : ''}`
        : '库存调整失败';
      ElMessage({ message: fullMsg, type: 'error', duration: 0, showClose: true });
    }
  } finally {
    adjustSubmitting.value = false;
  }
}

// ---------- 作废批次 ----------
async function handleVoidBatch() {
  if (!factoryId.value || !batch.value) return;
  const batchLabel = String(batch.value.batchNumber || batchId.value || '-');
  const productLabel = String(
    batch.value.productName
    || (batch.value as TableRow & { productType?: { name?: string } }).productType?.name
    || (batch.value as TableRow & { productTypeId?: string }).productTypeId
    || '-'
  );

  try {
    // fool-proof Rule 2: context header
    await ElMessageBox.confirm(
      '作废后该批次将不可恢复，且无法再出库或预留。请确认操作。',
      `作废批次 — ${batchLabel} (${productLabel})`,
      {
        confirmButtonText: '确认作废',
        cancelButtonText: '取消',
        type: 'warning',
        confirmButtonClass: 'el-button--danger',
      }
    );
  } catch {
    return;
  }

  try {
    const res = await voidFgBatch(factoryId.value, batchId.value);
    if (res === undefined || res === null || (res as { success?: boolean }).success !== false) {
      ElMessage.success(`批次 ${batchLabel} 已作废`);
      // Navigate back to list after void
      router.push('/sales/finished-goods');
    } else {
      const msg = (res as { message?: string }).message;
      ElMessage({ message: msg || '作废失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const err = e as {
      response?: { status?: number; data?: { message?: string; actionHint?: string } };
      actionHint?: string;
    };
    const status = err?.response?.status;
    const msg = err?.response?.data?.message;
    const hint = err?.response?.data?.actionHint;

    if (status === 409) {
      const fullMsg = msg
        ? `${msg}${hint ? `\n提示: ${hint}` : ''}`
        : `批次 ${batchLabel} 已有出库或预留记录，无法作废`;
      ElMessage({ message: fullMsg, type: 'error', duration: 0, showClose: true });
    } else if (!err?.actionHint) {
      ElMessage({ message: msg || '作废失败', type: 'error', duration: 0, showClose: true });
    }
  }
}
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card v-if="!loading && !batch" shadow="never">
      <el-empty description="成品批次数据不存在">
        <el-button @click="goBack">返回列表</el-button>
      </el-empty>
    </el-card>

    <template v-if="batch">
      <div class="detail-header">
        <div class="header-left">
          <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
          <h2 class="page-title">{{ batch.batchNumber || batch.id }}</h2>
          <el-tag :type="statusType(batch)" size="large">{{ statusText(batch) }}</el-tag>
        </div>
        <div class="header-actions">
          <el-button :icon="Refresh" @click="loadBatch">刷新</el-button>
          <el-button
            v-if="canEdit"
            type="primary"
            @click="openAdjustDialog"
          >调整库存</el-button>
          <el-button
            v-if="canEdit"
            type="danger"
            @click="handleVoidBatch"
          >作废批次</el-button>
        </div>
      </div>

      <!-- 基础信息 -->
      <el-card class="section-card" shadow="never">
        <template #header>
          <span class="section-title">基本信息</span>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="批次号">{{ batch.batchNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="产品">
            {{ batch.productName || batch.productType?.name || batch.productTypeId || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="生产数量">
            {{ batch.producedQuantity ?? '-' }} {{ batch.unit || '' }}
          </el-descriptions-item>
          <el-descriptions-item label="已发货">
            {{ batch.shippedQuantity ?? 0 }} {{ batch.unit || '' }}
          </el-descriptions-item>
          <el-descriptions-item label="已预留">
            {{ batch.reservedQuantity ?? 0 }} {{ batch.unit || '' }}
          </el-descriptions-item>
          <el-descriptions-item label="可用库存">
            <span :style="{ fontWeight: 600, color: availableQty(batch) <= 0 ? '#f56c6c' : '#303133' }">
              {{ availableQty(batch) }} {{ batch.unit || '' }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="生产日期">{{ batch.productionDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="过期日期">{{ batch.expireDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="库位">{{ batch.storageLocation || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="成本单价">
            {{ batch.unitPrice != null ? formatAmount(batch.unitPrice) : '-' }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 调整历史 -->
      <!-- T126 Phase 1 note: GET /sales/finished-goods/{id}/adjustments endpoint not
           included in T126-A backend. Section shows placeholder pending Phase 2 wiring. -->
      <el-card class="section-card" shadow="never">
        <template #header>
          <div class="section-header">
            <span class="section-title">调整历史</span>
            <el-tag size="small" type="info">待接后端端点</el-tag>
          </div>
        </template>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          title="调整历史待接"
          description="GET /sales/finished-goods/{id}/adjustments 端点在 T126 Phase 1 后端未包含。调整记录将在后续 Phase 补接后显示。"
        />
      </el-card>

      <!-- 价格历史 (canViewPrice gated) -->
      <el-card v-if="canViewPrice" class="section-card" shadow="never">
        <template #header>
          <div class="section-header">
            <span class="section-title">价格历史</span>
            <el-tag size="small" type="info">{{ priceHistory.length }} 条</el-tag>
          </div>
        </template>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          title="价格历史 API 待接"
          description="当前只显示批次记录的单价。完整价格变更轨迹 (生产记账 / 调价 / 退货返还) 接 /finance/price-history/{batchId} 后呈现。"
        />
        <el-table
          v-loading="priceHistoryLoading"
          :data="priceHistory"
          empty-text="暂无价格历史"
          stripe
          border
          size="small"
          style="width: 100%"
        >
          <el-table-column prop="date" label="日期" width="160" />
          <el-table-column label="价格" width="140" align="right">
            <template #default="{ row }">{{ formatAmount(row.price) }}</template>
          </el-table-column>
          <el-table-column prop="source" label="来源" min-width="160" />
        </el-table>
      </el-card>
    </template>
  </div>

  <!-- ===== 调整库存 Dialog ===== -->
  <el-dialog
    v-if="batch"
    v-model="adjustDialogVisible"
    :title="`调整库存 — ${batch.batchNumber || batchId} (${batch.productName || batch.productTypeId || '-'})`"
    width="480px"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <!-- fool-proof Rule 1: 预先显示当前库存边界 -->
    <el-descriptions :column="3" border size="small" style="margin-bottom: 16px;">
      <el-descriptions-item label="可用库存">
        <span :style="{ fontWeight: 600, color: adjustAvailableQty() <= 0 ? '#f56c6c' : '#303133' }">
          {{ adjustAvailableQty() }} {{ batch.unit || '' }}
        </span>
      </el-descriptions-item>
      <el-descriptions-item label="已发货">
        {{ Number(batch.shippedQuantity) || 0 }} {{ batch.unit || '' }}
      </el-descriptions-item>
      <el-descriptions-item label="已预留">
        {{ Number(batch.reservedQuantity) || 0 }} {{ batch.unit || '' }}
      </el-descriptions-item>
    </el-descriptions>

    <el-form label-width="90px">
      <el-form-item label="调整数量" required>
        <el-input-number
          v-model="adjustForm.adjustmentQuantity"
          :precision="2"
          placeholder="正数增加，负数减少"
          style="width: 180px;"
        />
        <span style="margin-left: 8px; font-size: 12px; color: #909399;">
          调整后可用:
          <strong :style="{ color: (adjustAvailableQty() + adjustForm.adjustmentQuantity) < 0 ? '#f56c6c' : '#303133' }">
            {{ (adjustAvailableQty() + adjustForm.adjustmentQuantity).toFixed(2) }}
          </strong>
          {{ batch.unit || '' }}
        </span>
      </el-form-item>
      <el-form-item label="原因类型" required>
        <el-select v-model="adjustForm.referenceType" style="width: 100%">
          <el-option
            v-for="opt in ADJUST_REF_TYPES"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="调整原因" required>
        <el-input
          v-model="adjustForm.reason"
          type="textarea"
          :rows="2"
          placeholder="请说明调整原因"
          clearable
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="adjustDialogVisible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="adjustSubmitting"
        :disabled="adjustForm.adjustmentQuantity === 0"
        @click="submitAdjust"
      >确认调整</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.page-wrapper {
  padding: 16px 20px;
}
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.page-title {
  font-size: 18px;
  font-weight: 600;
  margin: 0;
  color: #303133;
}
.section-card {
  margin-bottom: 16px;
}
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
