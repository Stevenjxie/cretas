<!--
  成本汇总 (对标客户 M67 Excel「汇总页」) — 区间内每张订单一行: 出成率/单盒成本/四拆。
  复用后端单一权威 GET /production/cost-summary?startDate&endDate。价格按权限脱敏(无权限显 —)。
-->
<template>
  <div class="sum-page">
    <div class="sum-header">
      <div>
        <h2>成本汇总</h2>
        <p class="sub">区间内每张订单按 SKU 口径展示出成率、总成本、元/kg 与元/SKU单位</p>
      </div>
      <div class="ctrls">
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="起始下单日" end-placeholder="截止下单日" :clearable="false" />
        <el-button type="primary" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mb" />

    <el-table v-if="rows.length" :data="rows" border stripe show-summary :summary-method="summary" size="default" :scrollbar-always-on="true" class="wide-table">
      <el-table-column prop="orderNumber" label="订单号" min-width="150" />
      <el-table-column prop="orderDate" label="下单日期" width="120" />
      <el-table-column prop="productName" label="产品" min-width="160"><template #default="{ row }">{{ row.productName || '—' }}</template></el-table-column>
      <el-table-column prop="skuCode" label="SKU" min-width="130"><template #default="{ row }">{{ row.skuCode || row.productTypeId || '—' }}</template></el-table-column>
      <el-table-column label="SKU产出" width="120" align="right"><template #default="{ row }">{{ skuOutput(row) }}</template></el-table-column>
      <el-table-column label="整批出成率" width="110" align="right">
        <template #default="{ row }"><span :class="yieldClass(row.overallYieldRate)">{{ row.overallYieldRate == null ? '—' : (row.overallYieldRate * 100).toFixed(1) + '%' }}</span></template>
      </el-table-column>
      <el-table-column label="原料" width="100" align="right"><template #default="{ row }">{{ money(row.rawMaterialCost) }}</template></el-table-column>
      <el-table-column label="人工" width="100" align="right"><template #default="{ row }">{{ money(row.laborCost) }}</template></el-table-column>
      <el-table-column label="调料" width="100" align="right"><template #default="{ row }">{{ money(row.seasoningCost) }}</template></el-table-column>
      <el-table-column label="包装" width="100" align="right"><template #default="{ row }">{{ money(row.packagingCost) }}</template></el-table-column>
      <el-table-column prop="totalCost" label="总成本" width="120" align="right"><template #default="{ row }"><b>{{ money(row.totalCost) }}</b></template></el-table-column>
      <el-table-column label="元/kg" width="110" align="right"><template #default="{ row }">{{ perKg(row) }}</template></el-table-column>
      <el-table-column label="元/SKU单位" width="140" align="right"><template #default="{ row }">{{ perSkuUnit(row) }}</template></el-table-column>
    </el-table>

    <el-empty v-else-if="!loading && !error" :description="empties" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { canonicalUnitCode, displayUnit } from '@/utils/unitPricing';

interface Row {
  orderId: string; orderNumber?: string; orderDate?: string; productTypeId?: string; productName?: string; skuCode?: string;
  skuUnit?: string; skuQuantity?: number; outputQuantity?: number; boxCount?: number; overallYieldRate?: number;
  rawMaterialCost?: number; laborCost?: number; seasoningCost?: number; packagingCost?: number; totalCost?: number;
  costPerKg?: number; perKgCost?: number; costPerSkuUnit?: number; perSkuUnitCost?: number; perBoxCost?: number;
}
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const loading = ref(false);
const error = ref('');
const rows = ref<Row[]>([]);
const masked = ref(false);

function defaultRange(): [string, string] {
  const end = new Date();
  const start = new Date(end.getTime() - 30 * 86400000);
  const f = (d: Date) => d.toISOString().slice(0, 10);
  return [f(start), f(end)];
}
const range = ref<[string, string]>(defaultRange());
const empties = computed(() => masked.value ? '无价格查看权限或区间内无订单' : '该区间无含生产批次的订单');

const money = (v?: number | null) => (v == null ? '—' : '¥' + Number(v).toFixed(2));
const skuOutput = (row: Row) => {
  const unit = canonicalUnitCode(row.skuUnit);
  const quantity = row.skuQuantity ?? row.outputQuantity ?? (unit === 'box' ? row.boxCount : null);
  return quantity == null ? '—' : `${Number(quantity).toFixed(2)} ${displayUnit(unit)}`;
};
const perKg = (row: Row) => money(row.costPerKg ?? row.perKgCost);
const perSkuUnit = (row: Row) => {
  const unit = canonicalUnitCode(row.skuUnit);
  const value = row.costPerSkuUnit ?? row.perSkuUnitCost ?? (unit === 'box' ? row.perBoxCost : null);
  return value == null || !unit ? '—' : `${money(value)}/${displayUnit(unit)}`;
};
const yieldClass = (y?: number | null) => (y == null ? '' : (y * 100 < 50 ? 'y-low' : (y * 100 > 130 ? 'y-high' : 'y-ok')));

function summary({ columns, data }: { columns: Array<{ property?: string }>; data: Row[] }) {
  return columns.map((column, index) => {
    if (index === 0) return '合计';
    if (column.property === 'totalCost') return money(data.reduce((sum, row) => sum + (row.totalCost || 0), 0));
    return '';
  });
}

async function load() {
  if (!factoryId.value || !range.value?.length) return;
  loading.value = true; error.value = '';
  try {
    const resp = await get<Row[]>(`/${factoryId.value}/production/cost-summary?startDate=${range.value[0]}&endDate=${range.value[1]}`);
    if (resp.success && Array.isArray(resp.data)) {
      rows.value = resp.data;
      masked.value = resp.data.some(r => r.totalCost == null);
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
.sum-page { padding: 16px; }
.sum-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 16px; }
.sum-header h2 { margin: 0; }
.sub { color: #909399; margin: 4px 0 0; font-size: 13px; }
.ctrls { display: flex; align-items: center; gap: 8px; }
.mb { margin-bottom: 16px; }
.y-ok { color: #67c23a; } .y-low { color: #f56c6c; font-weight: 600; } .y-high { color: #e6a23c; }
.wide-table :deep(.el-scrollbar__bar.is-horizontal) { opacity: 1; }
</style>
