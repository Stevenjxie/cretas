<template>
  <div class="profit-detail-page">
    <div class="page-header">
      <el-button :icon="ArrowLeft" link @click="goBack">返回订单详情</el-button>
      <h2>产品级利润详情</h2>
      <div class="header-meta" v-if="orderNumber">
        <span class="meta-label">订单号：</span>
        <span class="meta-value">{{ orderNumber }}</span>
      </div>
    </div>

    <el-card class="filter-card" shadow="never">
      <el-form inline>
        <el-form-item label="历史均价回溯">
          <el-select v-model="lookbackDays" style="width: 160px" @change="loadProfitDetail">
            <el-option label="近 30 天" :value="30" />
            <el-option label="近 60 天" :value="60" />
            <el-option label="近 90 天" :value="90" />
            <el-option label="近 180 天" :value="180" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="loadProfitDetail">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never" v-loading="loading">
      <el-table
        :data="rows"
        border
        stripe
        empty-text="无数据"
        :header-cell-style="{ background: '#f5f7fa', fontWeight: 600 }"
      >
        <el-table-column prop="productName" label="产品" min-width="160" fixed="left">
          <template #default="{ row }">
            <span>{{ row.productName || '—' }}</span>
            <div class="product-meta" v-if="row.unit">{{ displayUnit(row.unit) }}</div>
          </template>
        </el-table-column>

        <el-table-column prop="quantity" label="数量" width="100" align="right">
          <template #default="{ row }">{{ fmtQty(row.quantity) }}</template>
        </el-table-column>

        <el-table-column v-if="canViewPrice" prop="unitPrice" label="单价" width="110" align="right">
          <template #default="{ row }">{{ formatAmount(row.unitPrice) }}</template>
        </el-table-column>

        <el-table-column v-if="canViewPrice" prop="costUnitPrice" label="成本" width="110" align="right">
          <template #default="{ row }">{{ formatAmount(row.costUnitPrice) }}</template>
        </el-table-column>

        <el-table-column v-if="canViewPrice" prop="grossProfit" label="毛利" width="110" align="right">
          <template #default="{ row }">
            <span :class="profitClass(row.grossProfit)">{{ formatAmount(row.grossProfit) }}</span>
          </template>
        </el-table-column>

        <el-table-column v-if="canViewPrice" prop="grossMarginPct" label="毛利率" width="100" align="right">
          <template #default="{ row }">
            <span :class="marginClass(row.grossMarginPct)">{{ fmtPct(row.grossMarginPct) }}</span>
          </template>
        </el-table-column>

        <el-table-column v-if="canViewPrice" prop="discountAmount" label="折让" width="100" align="right">
          <template #default="{ row }">{{ formatAmount(row.discountAmount) }}</template>
        </el-table-column>

        <el-table-column v-if="canViewPrice" prop="taxAmount" label="税额" width="100" align="right">
          <template #default="{ row }">{{ formatAmount(row.taxAmount) }}</template>
        </el-table-column>

        <el-table-column v-if="canViewPrice" prop="netProfit" label="净利润" width="120" align="right">
          <template #default="{ row }">
            <strong :class="profitClass(row.netProfit)">{{ formatAmount(row.netProfit) }}</strong>
          </template>
        </el-table-column>

        <el-table-column v-if="canViewPrice" prop="historicalAvgPrice" label="历史均价" width="110" align="right">
          <template #default="{ row }">{{ formatAmount(row.historicalAvgPrice) }}</template>
        </el-table-column>

        <el-table-column v-if="canViewPrice" label="趋势" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.priceTrend === 'UP'" type="success" size="small">↑ 上涨</el-tag>
            <el-tag v-else-if="row.priceTrend === 'DOWN'" type="danger" size="small">↓ 下跌</el-tag>
            <el-tag v-else-if="row.priceTrend === 'FLAT'" type="info" size="small">— 持平</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-alert
        v-if="!canViewPrice"
        type="info"
        :closable="false"
        show-icon
        title="价格敏感信息已隐藏"
        description="当前角色无权查看单价、成本、利润等敏感字段。如需查看请联系管理员调整权限。"
        style="margin-top: 16px"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { ArrowLeft } from '@element-plus/icons-vue';
import { formatAmount, fmtQty } from '@/utils/tableFormatters';
import { displayUnit } from '@/utils/unitPricing';
import { handleCatchError } from '@/utils/errorToast';

interface SalesProductProfitRow {
  productTypeId: string;
  productName: string | null;
  unit: string | null;
  quantity: number | null;
  unitPrice: number | null;
  costUnitPrice: number | null;
  grossProfit: number | null;
  grossMarginPct: number | null;
  discountAmount: number | null;
  taxAmount: number | null;
  netProfit: number | null;
  historicalAvgPrice: number | null;
  priceTrend: 'UP' | 'DOWN' | 'FLAT' | null;
}

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
const orderId = computed(() => route.params.id as string);

const loading = ref(false);
const lookbackDays = ref(90);
const rows = ref<SalesProductProfitRow[]>([]);
const orderNumber = ref<string | null>(null);

async function loadProfitDetail() {
  if (!factoryId.value || !orderId.value) return;
  loading.value = true;
  try {
    const data = await get<SalesProductProfitRow[]>(
      `/${factoryId.value}/reports/sales/profit-detail/${orderId.value}?lookbackDays=${lookbackDays.value}`
    );
    rows.value = Array.isArray(data) ? data : [];
  } catch (err) {
    handleCatchError(err, '加载利润详情失败');
  } finally {
    loading.value = false;
  }
}

async function loadOrderNumber() {
  if (!factoryId.value || !orderId.value) return;
  try {
    const resp = await get<{ orderNumber?: string }>(`/${factoryId.value}/sales/orders/${orderId.value}`);
    orderNumber.value = resp.data?.orderNumber ?? null;
  } catch {
    // 非关键路径 — 主表格已能渲染. 静默失败.
  }
}

function goBack() {
  router.push(`/sales/orders/${orderId.value}`);
}

function fmtPct(val: number | null | undefined): string {
  if (val === null || val === undefined) return '—';
  return `${Number(val).toFixed(2)}%`;
}

function profitClass(val: number | null | undefined): string {
  if (val === null || val === undefined) return '';
  if (val > 0) return 'text-positive';
  if (val < 0) return 'text-negative';
  return '';
}

function marginClass(val: number | null | undefined): string {
  if (val === null || val === undefined) return '';
  if (val >= 30) return 'text-positive';
  if (val < 10) return 'text-negative';
  return 'text-neutral';
}

onMounted(() => {
  loadOrderNumber();
  loadProfitDetail();
});
</script>

<style scoped>
.profit-detail-page {
  padding: 16px;
}
.page-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}
.page-header h2 {
  margin: 0;
  font-size: 18px;
}
.header-meta {
  margin-left: auto;
  color: #606266;
  font-size: 14px;
}
.meta-label {
  color: #909399;
}
.meta-value {
  font-weight: 600;
}
.filter-card {
  margin-bottom: 12px;
}
.product-meta {
  color: #909399;
  font-size: 12px;
  margin-top: 2px;
}
.text-positive {
  color: #67c23a;
  font-weight: 600;
}
.text-negative {
  color: #f56c6c;
  font-weight: 600;
}
.text-neutral {
  color: #e6a23c;
}
</style>
