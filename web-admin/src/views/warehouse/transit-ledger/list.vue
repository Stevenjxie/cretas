<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { Search, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import {
  listTransitLedgers,
  type ProductionTransitLedgerItem,
} from '@/api/productionPlan';

// ────────────────────────────────────────────────────────────────────────────
// Auth
// ────────────────────────────────────────────────────────────────────────────
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

// ────────────────────────────────────────────────────────────────────────────
// Label maps
// ────────────────────────────────────────────────────────────────────────────
const statusMap: Record<string, { label: string; type: 'success' | 'warning' | 'info' | 'danger' }> = {
  OPEN: { label: '待处理', type: 'warning' },
  RESOLVED: { label: '已清账', type: 'success' },
  VOIDED: { label: '已作废', type: 'info' },
};

const responsibilitySideMap: Record<string, string> = {
  PENDING: '待判责',
  PRODUCTION: '生产侧',
  WAREHOUSE: '仓库侧',
  WEIGHING_ERROR: '称重误差',
};

const varianceReasonMap: Record<string, string> = {
  WITHIN_TOLERANCE: '容差范围内',
  PRODUCTION_SHORTAGE: '生产少产',
  WAREHOUSE_SHORTAGE: '仓库少掉锅',
  TRANSIT_LOSS: '在途损耗',
  WEIGHING_DIFF: '称重差异',
  MANUAL_ADJUSTMENT: '手动调整',
  OTHER: '其他',
};

const ledgerTypeMap: Record<string, string> = {
  FINISHED_GOODS_RECEIPT: '成品入库',
  RAW_MATERIAL_ISSUE: '原料发料',
  SEMI_FINISHED_ISSUE: '半成品发料',
};

function statusLabel(val: string) { return statusMap[val]?.label ?? val; }
function statusType(val: string) { return statusMap[val]?.type ?? 'info'; }
function responsibilityLabel(val: string) { return responsibilitySideMap[val] ?? val; }
function varianceReasonLabel(val: string) { return varianceReasonMap[val] ?? val; }
function ledgerTypeLabel(val: string) { return ledgerTypeMap[val] ?? val; }

// ────────────────────────────────────────────────────────────────────────────
// Filters
// ────────────────────────────────────────────────────────────────────────────
const statusFilter = ref('');
const planNumberSearch = ref('');

const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'OPEN', label: '待处理' },
  { value: 'RESOLVED', label: '已清账' },
  { value: 'VOIDED', label: '已作废' },
];

// ────────────────────────────────────────────────────────────────────────────
// Data
// ────────────────────────────────────────────────────────────────────────────
const loading = ref(false);
const allLedgers = ref<ProductionTransitLedgerItem[]>([]);

const tableData = computed(() => {
  let rows = allLedgers.value;
  if (planNumberSearch.value.trim()) {
    const kw = planNumberSearch.value.trim().toLowerCase();
    rows = rows.filter((r) => r.planNumber.toLowerCase().includes(kw));
  }
  return rows;
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listTransitLedgers(factoryId.value, statusFilter.value || undefined);
    if (res.success && Array.isArray(res.data)) {
      allLedgers.value = res.data;
    } else {
      allLedgers.value = [];
    }
  } catch (e) {
    ElMessage({
      message: String((e as Error).message || '加载中转挂账失败'),
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    loading.value = false;
  }
}

function handleFilterChange() {
  planNumberSearch.value = '';
  loadData();
}

function handleRefresh() {
  loadData();
}

// ────────────────────────────────────────────────────────────────────────────
// Summary counts
// ────────────────────────────────────────────────────────────────────────────
const openCount = computed(() => allLedgers.value.filter((r) => r.status === 'OPEN').length);
const resolvedCount = computed(() => allLedgers.value.filter((r) => r.status === 'RESOLVED').length);

// ────────────────────────────────────────────────────────────────────────────
// Format helpers
// ────────────────────────────────────────────────────────────────────────────
function formatQty(qty: number | null | undefined, unit: string | null) {
  if (qty == null) return '—';
  return unit ? `${qty} ${unit}` : String(qty);
}

function formatDateTime(dt: string | null | undefined) {
  if (!dt) return '—';
  return dt.replace('T', ' ').substring(0, 16);
}

// ────────────────────────────────────────────────────────────────────────────
// Init
// ────────────────────────────────────────────────────────────────────────────
onMounted(() => {
  loadData();
});

watch(factoryId, (v) => {
  if (v) loadData();
});
</script>

<template>
  <div class="transit-ledger-list">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2 class="page-title">中转挂账对账</h2>
      <p class="page-subtitle">查询生产→仓库成品入库确认后产生的差异挂账记录，追踪仓库/生产责任判定与清账状态。</p>
    </div>

    <!-- 汇总卡片 -->
    <el-row :gutter="16" class="summary-row" style="margin-bottom: 16px;">
      <el-col :span="8">
        <el-card shadow="never" class="summary-card">
          <div class="summary-item">
            <span class="summary-label">待处理挂账</span>
            <span class="summary-value warning">{{ openCount }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="summary-card">
          <div class="summary-item">
            <span class="summary-label">已清账</span>
            <span class="summary-value success">{{ resolvedCount }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="summary-card">
          <div class="summary-item">
            <span class="summary-label">合计记录</span>
            <span class="summary-value">{{ allLedgers.length }}</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 筛选栏 -->
    <el-card shadow="never" class="filter-card" style="margin-bottom: 12px;">
      <el-row :gutter="12" align="middle">
        <el-col :span="6">
          <el-select
            v-model="statusFilter"
            placeholder="全部状态"
            clearable
            @change="handleFilterChange"
            style="width: 100%;"
          >
            <el-option
              v-for="opt in statusOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-col>
        <el-col :span="8">
          <el-input
            v-model="planNumberSearch"
            placeholder="按计划单号搜索"
            clearable
            :prefix-icon="Search"
          />
        </el-col>
        <el-col :span="4">
          <el-button :icon="Refresh" @click="handleRefresh" :loading="loading">刷新</el-button>
        </el-col>
      </el-row>
    </el-card>

    <!-- 数据表格 -->
    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="tableData"
        stripe
        border
        row-key="id"
        empty-text="暂无中转挂账记录"
        style="width: 100%;"
      >
        <!-- 计划单号 -->
        <el-table-column label="计划单号" prop="planNumber" min-width="140" fixed="left">
          <template #default="{ row }">
            <span class="plan-number">{{ row.planNumber }}</span>
          </template>
        </el-table-column>

        <!-- 挂账类型 -->
        <el-table-column label="挂账类型" prop="ledgerType" width="120">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ ledgerTypeLabel(row.ledgerType) }}</el-tag>
          </template>
        </el-table-column>

        <!-- 状态 -->
        <el-table-column label="状态" prop="status" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>

        <!-- 报工数量 -->
        <el-table-column label="报工数量" width="120" align="right">
          <template #default="{ row }">{{ formatQty(row.reportedQuantity, row.quantityUnit) }}</template>
        </el-table-column>

        <!-- 仓库确认数量 -->
        <el-table-column label="仓库确认量" width="120" align="right">
          <template #default="{ row }">{{ formatQty(row.confirmedQuantity, row.quantityUnit) }}</template>
        </el-table-column>

        <!-- 差异量 -->
        <el-table-column label="差异量" width="120" align="right">
          <template #default="{ row }">
            <span :class="row.varianceQuantity > row.toleranceQuantity ? 'text-danger' : ''">
              {{ formatQty(row.varianceQuantity, row.quantityUnit) }}
            </span>
          </template>
        </el-table-column>

        <!-- 容差量 -->
        <el-table-column label="容差量" width="110" align="right">
          <template #default="{ row }">{{ formatQty(row.toleranceQuantity, row.quantityUnit) }}</template>
        </el-table-column>

        <!-- 差异原因 -->
        <el-table-column label="差异原因" prop="varianceReason" min-width="130">
          <template #default="{ row }">{{ varianceReasonLabel(row.varianceReason) }}</template>
        </el-table-column>

        <!-- 责任侧 -->
        <el-table-column label="责任侧" prop="responsibilitySide" width="110">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.responsibilitySide === 'PENDING' ? 'warning' : row.responsibilitySide === 'PRODUCTION' ? 'danger' : 'info'"
            >
              {{ responsibilityLabel(row.responsibilitySide) }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 备注 -->
        <el-table-column label="备注" prop="note" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.note || '—' }}</template>
        </el-table-column>

        <!-- 确认时间 -->
        <el-table-column label="确认时间" width="140">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>

        <!-- 更新时间 -->
        <el-table-column label="更新时间" width="140">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>

        <!-- 操作列：跳转到对应生产计划 -->
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              text
              type="primary"
              @click="$router.push(`/production/plans?planId=${row.productionPlanId}`)"
            >
              查看计划
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空状态说明 -->
      <div v-if="!loading && tableData.length === 0 && !planNumberSearch" class="empty-hint">
        <el-empty description="暂无中转挂账记录">
          <template #description>
            <p>当生产报工与仓库实收数量出现超容差差异时，系统将在此生成挂账记录。</p>
            <p style="color: #909399; font-size: 12px;">容差范围（默认 10kg）内的差异不产生挂账。</p>
          </template>
        </el-empty>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.transit-ledger-list {
  padding: 16px;
}

.page-header {
  margin-bottom: 16px;
}

.page-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.page-subtitle {
  margin: 0;
  font-size: 13px;
  color: #606266;
}

.summary-card .summary-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.summary-label {
  font-size: 13px;
  color: #606266;
}

.summary-value {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
}

.summary-value.warning { color: #e6a23c; }
.summary-value.success { color: #67c23a; }

.filter-card :deep(.el-card__body) {
  padding: 12px 16px;
}

.plan-number {
  font-family: monospace;
  font-size: 13px;
}

.text-danger {
  color: #f56c6c;
  font-weight: 600;
}

.empty-hint {
  margin-top: 8px;
}
</style>
