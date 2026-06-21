<!--
  成本汇总 (对标客户 M67 Excel「汇总页」) — 区间内每张订单一行: 出成率/单盒成本/四拆。
  复用后端单一权威 GET /production/cost-summary?startDate&endDate。价格按权限脱敏(无权限显 —)。
-->
<template>
  <div class="sum-page">
    <div class="sum-header">
      <div>
        <h2>成本汇总</h2>
        <p class="sub">区间内每张订单的 出成率 · 单盒成本 · 成本拆解 (对标 Excel 汇总页)</p>
      </div>
      <div class="ctrls">
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="起始下单日" end-placeholder="截止下单日" :clearable="false" />
        <el-button type="primary" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mb" />

    <el-table v-if="rows.length" :data="rows" border stripe show-summary :summary-method="summary" size="default">
      <el-table-column prop="orderNumber" label="订单号" min-width="150" />
      <el-table-column prop="orderDate" label="下单日期" width="120" />
      <el-table-column label="盒数" width="90" align="right"><template #default="{ row }">{{ row.boxCount ?? '—' }}</template></el-table-column>
      <el-table-column label="整批出成率" width="110" align="right">
        <template #default="{ row }"><span :class="yieldClass(row.overallYieldRate)">{{ row.overallYieldRate == null ? '—' : (row.overallYieldRate * 100).toFixed(1) + '%' }}</span></template>
      </el-table-column>
      <el-table-column label="原料" width="100" align="right"><template #default="{ row }">{{ money(row.rawMaterialCost) }}</template></el-table-column>
      <el-table-column label="人工" width="100" align="right"><template #default="{ row }">{{ money(row.laborCost) }}</template></el-table-column>
      <el-table-column label="调料" width="100" align="right"><template #default="{ row }">{{ money(row.seasoningCost) }}</template></el-table-column>
      <el-table-column label="包装" width="100" align="right"><template #default="{ row }">{{ money(row.packagingCost) }}</template></el-table-column>
      <el-table-column label="总成本" width="120" align="right"><template #default="{ row }"><b>{{ money(row.totalCost) }}</b></template></el-table-column>
      <el-table-column label="单盒成本" width="110" align="right"><template #default="{ row }"><b>{{ money(row.perBoxCost) }}</b>/盒</template></el-table-column>
    </el-table>

    <el-empty v-else-if="!loading && !error" :description="empties" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';

interface Row {
  orderId: string; orderNumber?: string; orderDate?: string; boxCount?: number; overallYieldRate?: number;
  rawMaterialCost?: number; laborCost?: number; seasoningCost?: number; packagingCost?: number; totalCost?: number; perBoxCost?: number;
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
const yieldClass = (y?: number | null) => (y == null ? '' : (y * 100 < 50 ? 'y-low' : (y * 100 > 130 ? 'y-high' : 'y-ok')));

function summary({ columns, data }: any) {
  const out: string[] = [];
  columns.forEach((_: any, i: number) => {
    if (i === 0) { out.push('合计/均'); return; }
    if (i === 2) { out.push(String(data.reduce((s: number, r: Row) => s + (r.boxCount || 0), 0))); return; }
    if (i === 8) { out.push(money(data.reduce((s: number, r: Row) => s + (r.totalCost || 0), 0))); return; }
    if (i === 9) {
      const tc = data.reduce((s: number, r: Row) => s + (r.totalCost || 0), 0);
      const bc = data.reduce((s: number, r: Row) => s + (r.boxCount || 0), 0);
      out.push(bc ? money(tc / bc) + '/盒' : '—'); return;
    }
    out.push('');
  });
  return out;
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
</style>
