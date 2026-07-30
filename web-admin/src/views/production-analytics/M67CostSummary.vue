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
      <!-- productTypeId 是 UUID, 兜底展示它等于给操作员看一串乱码, 不如照实显示"—" -->
      <el-table-column prop="skuCode" label="SKU" min-width="130"><template #default="{ row }">{{ row.skuCode || '—' }}</template></el-table-column>
      <el-table-column label="SKU产出" width="120" align="right"><template #default="{ row }">{{ skuOutput(row) }}</template></el-table-column>
      <el-table-column label="整批出成率" width="110" align="right">
        <template #default="{ row }"><span :class="yieldClass(row.overallYieldRate)">{{ row.overallYieldRate == null ? '—' : (row.overallYieldRate * 100).toFixed(1) + '%' }}</span></template>
      </el-table-column>
      <el-table-column label="原料" width="100" align="right"><template #default="{ row }">{{ money(row.rawMaterialCost) }}</template></el-table-column>
      <el-table-column label="人工" width="100" align="right"><template #default="{ row }">{{ money(row.laborCost) }}</template></el-table-column>
      <el-table-column label="调料" width="100" align="right"><template #default="{ row }">{{ money(row.seasoningCost) }}</template></el-table-column>
      <el-table-column label="包装" width="100" align="right"><template #default="{ row }">{{ money(row.packagingCost) }}</template></el-table-column>
      <!-- 成本项没采集齐时 totalCost 依约为 null (不伪造完整成本), 但仍把「已填的都算进去」的
           已知合计显示出来, 并挂上"待补"标记说明差什么 —— 否则一个光秃秃的数会被当成完整成本。 -->
      <el-table-column prop="totalCost" label="总成本" width="150" align="right">
        <template #default="{ row }">
          <b>{{ money(row.totalCost ?? row.knownCostSubtotal) }}</b>
          <el-tooltip v-if="row.totalCost == null && row.knownCostSubtotal != null" placement="top">
            <template #content>
              <div>此金额只含已录入的成本项，尚未包含：</div>
              <div v-for="label in missingCostLabels(row)" :key="label">· {{ label }}</div>
              <div>补录后总成本会自动变完整。</div>
            </template>
            <el-tag type="warning" size="small" effect="plain" class="cost-partial-tag">待补</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
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
  knownCostSubtotal?: number; knownPerBoxCost?: number;
  calculationStatus?: string; missingCostItems?: string[];
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
const skuUnit = (row: Row) => canonicalUnitCode(row.skuUnit) || (row.boxCount != null ? 'box' : '');
const skuOutput = (row: Row) => {
  const unit = skuUnit(row);
  const quantity = row.skuQuantity ?? row.outputQuantity ?? row.boxCount;
  return quantity == null ? '—' : `${Number(quantity).toFixed(2)} ${displayUnit(unit)}`;
};
const perKg = (row: Row) => money(row.costPerKg ?? row.perKgCost);
const perSkuUnit = (row: Row) => {
  const unit = skuUnit(row);
  // 与「总成本」同口径: 完整值优先, 没有就用已知合计的单位成本 (行上已有"待补"标记说明不完整)。
  const value = row.costPerSkuUnit ?? row.perSkuUnitCost ?? row.perBoxCost ?? row.knownPerBoxCost;
  return value == null || !unit ? '—' : `${money(value)}/${displayUnit(unit)}`;
};

/** 把后端 missingCostItems 的机器码翻成一线看得懂的中文 (冒号后是定位信息, 去掉)。 */
const MISSING_COST_LABELS: Record<string, string> = {
  EQUIPMENT_COST: '设备成本',
  OTHER_COST: '其他成本',
  LABOR_RATE_OR_TIME: '人工工时或费率',
  PINNED_BOM_SNAPSHOT_MISSING: 'BOM 配方快照',
  SEMI_FINISHED_OR_FINISHED_FEED_PRICE: '半成品/成品投料单价',
};
const missingCostLabels = (row: Row): string[] => {
  const codes = (row.missingCostItems || []).map((item) => String(item).split(':')[0]);
  return [...new Set(codes)].map((code) => MISSING_COST_LABELS[code] || code);
};
const yieldClass = (y?: number | null) => (y == null ? '' : (y * 100 < 50 ? 'y-low' : (y * 100 > 130 ? 'y-high' : 'y-ok')));

function summary({ columns, data }: { columns: Array<{ property?: string }>; data: Row[] }) {
  return columns.map((column, index) => {
    if (index === 0) return '合计';
    // 与行内口径一致: 行显示已知合计, 合计就不能只加 totalCost (否则行有数、合计 ¥0.00 自相矛盾)。
    // 只要有任一行不完整, 合计也标"含待补"。
    if (column.property === 'totalCost') {
      const total = data.reduce((sum, row) => sum + (row.totalCost ?? row.knownCostSubtotal ?? 0), 0);
      const anyPartial = data.some((row) => row.totalCost == null && row.knownCostSubtotal != null);
      return anyPartial ? `${money(total)}（含待补）` : money(total);
    }
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
.cost-partial-tag { margin-left: 6px; cursor: help; }
</style>
