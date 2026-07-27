<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import echarts from '@/utils/echarts';
import type { ECharts } from 'echarts/core';
import FlywheelHeader from './components/FlywheelHeader.vue';
import { useFlywheelDomain } from './composables/useFlywheelDomain';
import { flywheelApi, type FlywheelQuality } from '@/api/smartbi/ai-flywheel';

const { domain } = useFlywheelDomain();
const loading = ref(false);
const data = ref<FlywheelQuality | null>(null);
const days = ref(30);

const chartRef = ref<HTMLDivElement | null>(null);
let chart: ECharts | null = null;

async function load() {
  loading.value = true;
  try {
    data.value = await flywheelApi.quality(domain.value, days.value);
    await nextTick();
    renderChart();
  } catch (e) {
    ElMessage.error('加载质量与回归数据失败: ' + (e instanceof Error ? e.message : String(e)));
  } finally {
    loading.value = false;
  }
}

function renderChart() {
  if (!chartRef.value || !data.value) return;
  if (!chart || chart.isDisposed?.()) {
    chart = echarts.init(chartRef.value, 'cretas');
  }
  const trend = data.value.battery_trend;
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['通过率 (%)', '失败数'], bottom: 0 },
    grid: { left: 40, right: 40, top: 24, bottom: 40 },
    xAxis: { type: 'category', data: trend.map((t) => t.date.slice(5)) },
    yAxis: [
      { type: 'value', name: '通过率 %', min: 0, max: 100 },
      { type: 'value', name: '失败数', min: 0 },
    ],
    series: [
      { name: '通过率 (%)', type: 'line', data: trend.map((t) => t.pass_rate), yAxisIndex: 0 },
      { name: '失败数', type: 'bar', data: trend.map((t) => t.failed), yAxisIndex: 1, itemStyle: { color: '#f56c6c' } },
    ],
  });
}

function handleResize() {
  chart?.resize();
}

function fmtTime(ts: string): string {
  return new Date(ts).toLocaleString('zh-CN');
}

onMounted(() => {
  load();
  window.addEventListener('resize', handleResize);
});
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize);
  if (chart && !chart.isDisposed?.()) chart.dispose();
  chart = null;
});
watch(domain, load);

const latestPassRate = computed(() => {
  const trend = data.value?.battery_trend;
  if (!trend || trend.length === 0) return null;
  return trend[trend.length - 1].pass_rate;
});
</script>

<template>
  <div class="page-container">
    <FlywheelHeader v-model:domain="domain" />

    <div class="controls-row">
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <span v-if="latestPassRate !== null" class="latest-pass" :class="{ warn: latestPassRate < 90 }">
        最近一次电池通过率: {{ latestPassRate }}%
      </span>
    </div>

    <el-card class="section-card" v-loading="loading">
      <template #header><span>每日回归电池结果趋势</span></template>
      <div ref="chartRef" class="chart-box"></div>
    </el-card>

    <el-row :gutter="16">
      <el-col :xs="24" :md="14">
        <el-card class="section-card" v-loading="loading">
          <template #header>
            <div class="section-header">
              <span>契约失败明细</span>
              <el-tag size="small" type="danger" v-if="data">{{ data.contract_failures.length }} 条</el-tag>
            </div>
          </template>
          <el-table :data="data?.contract_failures || []" stripe empty-text="暂无契约失败" size="small">
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ fmtTime(row.ts) }}</template>
            </el-table-column>
            <el-table-column label="问法" prop="query_text" min-width="160" show-overflow-tooltip />
            <el-table-column label="契约" prop="contract_name" width="150" />
            <el-table-column label="错误详情" prop="error_detail" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="10">
        <el-card class="section-card" v-loading="loading">
          <template #header>
            <div class="section-header">
              <span>👎 关联问答对</span>
              <el-tag size="small" type="warning" v-if="data">{{ data.negative_feedback.length }} 条</el-tag>
            </div>
          </template>
          <div class="feedback-list">
            <div v-for="f in data?.negative_feedback || []" :key="f.id" class="feedback-item">
              <div class="feedback-q">Q: {{ f.query_text }}</div>
              <div class="feedback-a">A: {{ f.answer_text }}</div>
              <div v-if="f.note" class="feedback-note">备注: {{ f.note }}</div>
              <div class="feedback-ts">{{ fmtTime(f.ts) }}</div>
            </div>
            <el-empty v-if="data && data.negative_feedback.length === 0" description="暂无差评问答对" :image-size="60" />
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.page-container {
  padding: 20px;
}
.controls-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.latest-pass {
  color: #67c23a;
  font-size: 13px;
  font-weight: 600;
}
.latest-pass.warn {
  color: #f56c6c;
}
.section-card {
  margin-bottom: 16px;
}
.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.chart-box {
  height: 280px;
  width: 100%;
}
.feedback-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 360px;
  overflow-y: auto;
}
.feedback-item {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
}
.feedback-q {
  font-weight: 600;
  color: #303133;
  font-size: 13px;
}
.feedback-a {
  color: #606266;
  font-size: 13px;
  margin-top: 4px;
}
.feedback-note {
  color: #e6a23c;
  font-size: 12px;
  margin-top: 4px;
}
.feedback-ts {
  color: #c0c4cc;
  font-size: 11px;
  margin-top: 6px;
  text-align: right;
}

@media (max-width: 768px) {
  .page-container {
    padding: 12px;
  }
  .chart-box {
    height: 220px;
  }
}
</style>
