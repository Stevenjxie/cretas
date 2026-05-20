<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { handleCatchError } from '@/utils/errorToast';
import { fetchDailyReport, type DailyReport } from '@/api/salesPreset';

const data = ref<DailyReport | null>(null);
const loading = ref(false);
const selectedDate = ref<string>(new Date().toISOString().slice(0, 10));

async function load(): Promise<void> {
  loading.value = true;
  try {
    const res = await fetchDailyReport(selectedDate.value);
    data.value = res?.data;
  } catch (e) {
    // UX polish (2026-05-20): interceptor toast already covers 4xx/5xx.
    handleCatchError(e, '加载失败,请检查网络');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="daily-report">
    <div class="header">
      <h2>销售额日报</h2>
      <div class="filter">
        <el-date-picker
          v-model="selectedDate"
          type="date"
          value-format="YYYY-MM-DD"
          @change="load"
        />
      </div>
    </div>

    <div v-loading="loading" class="kpi-grid" v-if="data">
      <el-card class="kpi">
        <div class="label">日期</div>
        <div class="value">{{ data.date }}</div>
      </el-card>
      <el-card class="kpi">
        <div class="label">订单数</div>
        <div class="value">{{ data.orderCount }}</div>
      </el-card>
      <el-card class="kpi primary">
        <div class="label">销售额</div>
        <div class="value">¥ {{ data.revenue.toLocaleString() }}</div>
      </el-card>
      <el-card class="kpi success">
        <div class="label">已收款</div>
        <div class="value">¥ {{ data.paid.toLocaleString() }}</div>
      </el-card>
      <el-card class="kpi warning">
        <div class="label">未收款</div>
        <div class="value">¥ {{ data.unpaid.toLocaleString() }}</div>
      </el-card>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.daily-report {
  padding: 16px;
  .header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 24px;
    h2 { margin: 0; }
  }
  .kpi-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 16px;
  }
  .kpi {
    .label { color: #909399; font-size: 13px; margin-bottom: 8px; }
    .value { font-size: 22px; font-weight: 600; }
    &.primary .value { color: #409eff; }
    &.success .value { color: #67c23a; }
    &.warning .value { color: #e6a23c; }
  }
}
</style>
