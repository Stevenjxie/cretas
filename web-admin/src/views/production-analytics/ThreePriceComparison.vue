<!--
  三价对比看板 (per-SKU) — 标准BOM成本 vs 销售价 vs 实际成本。
  复用超支报警引擎同款口径 (OrderCostAlarmListener 用的 StandardCostService + CostVarianceService)，
  只是把已在跑的推送口径以看板形式按 SKU 逐一展示，让财审/销售主管主动查看，不必只靠被动推送。
  GET /api/mobile/{factoryId}/cost/three-price-comparison?overBudgetOnly&category
  价格按 procurement:price:view 权限脱敏 (无权限显 —，与 M67CostSummary 同一模式)。
-->
<template>
  <div class="tp-page">
    <div class="tp-header">
      <div>
        <h2>三价对比看板</h2>
        <p class="sub">每 SKU 一行: 标准BOM成本 · 销售价 · 实际成本 — 与超支报警引擎同一口径</p>
      </div>
      <div class="ctrls">
        <el-checkbox v-model="overBudgetOnly" @change="load">只看超支</el-checkbox>
        <el-select
          v-model="category"
          clearable
          placeholder="按分类过滤 (全部)"
          style="width: 160px"
          @change="load"
        >
          <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
        </el-select>
        <el-button type="primary" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mb" />

    <el-alert
      v-if="!canViewPrice"
      type="warning"
      :closable="false"
      title="您的角色无成本查看权限，标准/销售/实际三价及偏差百分比已脱敏 (显示 —)；超支标记仍可见。"
      class="mb"
    />

    <el-alert
      v-if="rows.length"
      type="info"
      :closable="false"
      class="mb"
      :title="summaryText"
    />

    <el-table
      v-if="rows.length"
      :data="rows"
      border
      stripe
      size="default"
      :row-class-name="rowClass"
    >
      <el-table-column label="产品" min-width="200">
        <template #default="{ row }">
          <div class="prod-cell">
            <span class="prod-name">{{ row.productName }}</span>
            <span class="prod-meta">{{ row.productCode }}<template v-if="row.productCategory"> · {{ row.productCategory }}</template></span>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="标准BOM成本" width="140" align="right">
        <template #default="{ row }">
          <span class="price-std">{{ money(row.standardCost, row.unit) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="销售价" width="140" align="right">
        <template #default="{ row }">
          <span class="price-sales">{{ money(row.salesPrice, row.unit) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="实际成本" width="150" align="right">
        <template #default="{ row }">
          <span class="price-actual">{{ money(row.actualCost, row.unit) }}</span>
          <div v-if="row.actualCostAsOfBatchNumber" class="as-of">批次 {{ row.actualCostAsOfBatchNumber }}</div>
        </template>
      </el-table-column>

      <el-table-column label="毛利率" width="100" align="right">
        <template #default="{ row }">
          <span :class="marginClass(row.grossMargin)">{{ pct(row.grossMargin) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="偏差%" width="110" align="right">
        <template #default="{ row }">
          <span :class="row.overBudget ? 'variance-bad' : 'variance-ok'">{{ pct(row.variancePct) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="120" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.overBudget" type="danger" effect="dark" size="small">超支</el-tag>
          <el-tag v-else-if="row.variancePct != null" type="success" size="small">正常</el-tag>
          <el-tooltip v-else :content="row.caliberHint || '标准成本口径不全，未参与超支判定'" placement="top">
            <el-tag type="info" size="small">口径不全</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-else-if="!loading && !error" :description="emptyText" />

    <div v-if="rows.length" class="footnote">
      超阈值 SKU 已在生产完工时自动推送 App 通知给销售主管/工厂总监；本页可主动查看全量 SKU 当前状态。
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';

interface ThreePriceRow {
  productTypeId: string;
  productName?: string;
  productCode?: string;
  productCategory?: string;
  unit?: string;
  standardCost?: number | null;
  salesPrice?: number | null;
  taxIncludedSalesPrice?: number | null;
  actualCost?: number | null;
  variancePct?: number | null;
  threshold?: number | null;
  grossMargin?: number | null;
  overBudget?: boolean | null;
  caliberHint?: string | null;
  actualCostAsOfBatchNumber?: string | null;
  actualCostAsOf?: string | null;
}

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const error = ref('');
const rows = ref<ThreePriceRow[]>([]);
const overBudgetOnly = ref(false);
const category = ref<string | undefined>(undefined);

const categoryOptions = computed(() => {
  const set = new Set<string>();
  rows.value.forEach((r) => { if (r.productCategory) set.add(r.productCategory); });
  return Array.from(set).sort();
});

const overBudgetCount = computed(() => rows.value.filter((r) => r.overBudget).length);
const summaryText = computed(() => {
  const n = overBudgetCount.value;
  const total = rows.value.length;
  return n > 0
    ? `共 ${total} 个 SKU，其中 ${n} 个超支 (偏差 > 阈值)`
    : `共 ${total} 个 SKU，均未超支`;
});
const emptyText = computed(() => (
  overBudgetOnly.value ? '当前无超支 SKU' : '暂无产品数据'
));

function money(v?: number | null, unit?: string): string {
  if (v == null) return '—';
  return `¥${Number(v).toFixed(2)}${unit ? '/' + unit : ''}`;
}
function pct(v?: number | null): string {
  if (v == null) return '—';
  return `${Number(v).toFixed(2)}%`;
}
function marginClass(v?: number | null): string {
  if (v == null) return '';
  return Number(v) < 0 ? 'variance-bad' : '';
}
function rowClass({ row }: { row: ThreePriceRow }): string {
  return row.overBudget ? 'over-budget-row' : '';
}

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  error.value = '';
  try {
    const params = new URLSearchParams();
    if (overBudgetOnly.value) params.set('overBudgetOnly', 'true');
    if (category.value) params.set('category', category.value);
    const qs = params.toString();
    const resp = await get<ThreePriceRow[]>(
      `/${factoryId.value}/cost/three-price-comparison${qs ? `?${qs}` : ''}`,
    );
    if (resp.success && Array.isArray(resp.data)) {
      rows.value = resp.data;
    } else {
      error.value = resp.message || '加载失败';
    }
  } catch (e: any) {
    error.value = e?.response?.data?.message || e?.message || '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.tp-page { padding: 16px; }
.tp-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 16px; flex-wrap: wrap; gap: 12px; }
.tp-header h2 { margin: 0; }
.sub { color: #909399; margin: 4px 0 0; font-size: 13px; }
.ctrls { display: flex; align-items: center; gap: 8px; }
.mb { margin-bottom: 16px; }

.prod-cell { display: flex; flex-direction: column; }
.prod-name { font-weight: 600; }
.prod-meta { font-size: 12px; color: #909399; }

.price-std { color: #409eff; font-weight: 600; }
.price-sales { color: #e6a23c; font-weight: 600; }
.price-actual { color: #67c23a; font-weight: 600; }
.as-of { font-size: 11px; color: #c0c4cc; }

.variance-ok { color: #67c23a; }
.variance-bad { color: #f56c6c; font-weight: 700; }
.footnote { margin-top: 12px; font-size: 12px; color: #909399; }

:deep(.over-budget-row) { background-color: #fef0f0; }
</style>
