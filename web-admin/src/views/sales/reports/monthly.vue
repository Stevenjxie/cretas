<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { handleCatchError } from '@/utils/errorToast';
import { fetchMonthlyReport, type MonthlyReport } from '@/api/salesPreset';

const data = ref<MonthlyReport | null>(null);
const loading = ref(false);
const now = new Date();
const selectedMonth = ref<string>(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`);

function money(value: number | null | undefined): string {
  return `¥ ${Number(value ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const res = await fetchMonthlyReport(selectedMonth.value);
    data.value = res?.data;
  } catch (e) {
    // UX polish (2026-05-20): interceptor toast already covers 4xx/5xx.
    handleCatchError(e, '加载失败,请检查网络');
  } finally {
    loading.value = false;
  }
}

const dailyTable = computed(() => data.value?.daily ?? []);

onMounted(load);
</script>

<template>
  <div class="monthly-report">
    <div class="header">
      <h2>销售额月报</h2>
      <el-date-picker
        v-model="selectedMonth"
        type="month"
        value-format="YYYY-MM"
        @change="load"
      />
    </div>

    <div v-loading="loading" class="kpi-grid" v-if="data">
      <el-card>
        <div class="label">月份</div><div class="value">{{ data.yearMonth }}</div>
      </el-card>
      <el-card>
        <div class="label">订单数</div><div class="value">{{ data.orderCount }}</div>
      </el-card>
      <el-card>
        <div class="label">含税销售额</div><div class="value primary">{{ money(data.totalAmountWithTax) }}</div>
      </el-card>
      <el-card>
        <div class="label">未税金额</div><div class="value">{{ money(data.taxableAmount) }}</div>
      </el-card>
      <el-card>
        <div class="label">税额</div><div class="value">{{ money(data.taxAmount) }}</div>
      </el-card>
      <el-card>
        <div class="label">已收款</div><div class="value success">{{ money(data.paid) }}</div>
      </el-card>
      <el-card>
        <div class="label">未收款</div><div class="value warning">{{ money(data.unpaid) }}</div>
      </el-card>
    </div>

    <el-card v-if="dailyTable.length" class="daily-table">
      <h4>按日明细</h4>
      <el-table :data="dailyTable" stripe size="small">
        <el-table-column prop="date" label="日期" />
        <el-table-column prop="orderCount" label="订单数" />
        <el-table-column label="含税销售额">
          <template #default="{ row }">{{ money(row.totalAmountWithTax) }}</template>
        </el-table-column>
        <el-table-column label="未税金额">
          <template #default="{ row }">{{ money(row.taxableAmount) }}</template>
        </el-table-column>
        <el-table-column label="税额">
          <template #default="{ row }">{{ money(row.taxAmount) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.monthly-report {
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
    .warning { color: #e6a23c; }
  }
  .daily-table h4 { margin: 0 0 12px 0; }
}
</style>
