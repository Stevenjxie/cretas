<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { handleCatchError } from '@/utils/errorToast';
import { fetchYearlyReport, type YearlyReport } from '@/api/salesPreset';

const data = ref<YearlyReport | null>(null);
const loading = ref(false);
const selectedYear = ref<number>(new Date().getFullYear());

function money(value: number | null | undefined): string {
  return `¥ ${Number(value ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const res = await fetchYearlyReport(selectedYear.value);
    data.value = res?.data;
  } catch (e) {
    // UX polish (2026-05-20): interceptor toast already covers 4xx/5xx;
    // only fire fallback for pure network errors.
    handleCatchError(e, '加载失败,请检查网络');
  } finally {
    loading.value = false;
  }
}

const monthlyTable = computed(() => data.value?.monthly ?? []);

onMounted(load);
</script>

<template>
  <div class="yearly-report">
    <div class="header">
      <h2>销售额年报</h2>
      <el-input-number v-model="selectedYear" :min="2020" :max="2099" controls-position="right" @change="load" />
    </div>

    <div v-loading="loading" v-if="data" class="kpi-grid">
      <el-card><div class="label">年份</div><div class="value">{{ data.year }}</div></el-card>
      <el-card><div class="label">订单数</div><div class="value">{{ data.orderCount }}</div></el-card>
      <el-card><div class="label">含税销售额</div><div class="value primary">{{ money(data.totalAmountWithTax) }}</div></el-card>
      <el-card><div class="label">未税金额</div><div class="value">{{ money(data.taxableAmount) }}</div></el-card>
      <el-card><div class="label">税额</div><div class="value">{{ money(data.taxAmount) }}</div></el-card>
      <el-card><div class="label">已收款</div><div class="value success">{{ money(data.paid) }}</div></el-card>
    </div>

    <el-card v-if="monthlyTable.length" class="monthly-table">
      <h4>按月明细</h4>
      <el-table :data="monthlyTable" stripe size="small">
        <el-table-column prop="month" label="月份" width="80" />
        <el-table-column prop="orderCount" label="订单数" width="90" />
        <el-table-column label="未税金额" width="140">
          <template #default="{ row }">{{ money(row.taxableAmount) }}</template>
        </el-table-column>
        <el-table-column label="含税销售额" width="140">
          <template #default="{ row }">{{ money(row.totalAmountWithTax) }}</template>
        </el-table-column>
        <el-table-column label="税额" width="120">
          <template #default="{ row }">{{ money(row.taxAmount) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.yearly-report {
  padding: 16px;
  .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; h2 { margin: 0; } }
  .kpi-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
    gap: 16px;
    margin-bottom: 24px;
    .label { color: #909399; font-size: 13px; margin-bottom: 6px; }
    .value { font-size: 20px; font-weight: 600; }
    .primary { color: #409eff; }
    .success { color: #67c23a; }
  }
  .monthly-table h4 { margin: 0 0 12px 0; }
}
</style>
