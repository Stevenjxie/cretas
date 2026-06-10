<script setup lang="ts">
// Sprint 7 wave 2 T5 — 销售排行榜.
// HJ Round 13 §15 业绩 6 项: 销售排名 + 团队 quota + ECharts bar.
// 防呆 R2 context: header 含 period + year/periodNum.
import { ref, computed, onMounted, watch, nextTick } from 'vue';
import { handleCatchError } from '@/utils/errorToast';
import { getLeaderboard, type Leaderboard, type TargetPeriod } from '@/api/salesTarget';
import * as echarts from 'echarts';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const period = ref<TargetPeriod>('MONTH');
const year = ref<number>(new Date().getFullYear());
const periodNum = ref<number>(new Date().getMonth() + 1);
const limit = ref<number>(10);

const data = ref<Leaderboard | null>(null);
const loading = ref(false);
const chartContainer = ref<HTMLElement | null>(null);
let chartInstance: echarts.ECharts | null = null;

const periodNumChoices = computed<{ value: number; label: string }[]>(() => {
  if (period.value === 'MONTH') {
    return Array.from({ length: 12 }, (_, i) => ({ value: i + 1, label: `${i + 1} 月` }));
  }
  if (period.value === 'QUARTER') {
    return [1, 2, 3, 4].map((q) => ({ value: q, label: `Q${q}` }));
  }
  return [{ value: 1, label: '全年' }];
});

watch(period, () => {
  if (period.value === 'MONTH') periodNum.value = new Date().getMonth() + 1;
  else if (period.value === 'QUARTER') periodNum.value = 1;
  else periodNum.value = 1;
});

async function load(): Promise<void> {
  loading.value = true;
  try {
    data.value = await getLeaderboard(period.value, year.value, periodNum.value, limit.value);
    await nextTick();
    renderChart();
  } catch (e) {
    handleCatchError(e, '加载排行榜失败');
  } finally {
    loading.value = false;
  }
}

function renderChart(): void {
  if (!chartContainer.value || !data.value) return;
  if (!chartInstance) {
    chartInstance = echarts.init(chartContainer.value);
  }
  const entries = data.value.entries;
  const option: echarts.EChartsOption = {
    title: {
      text: `${year.value}-${data.value.periodDisplay} ${periodNum.value} 销售排行榜`,
      left: 'center',
    },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['实际', '目标'], top: 30 },
    grid: { left: 60, right: 30, bottom: 50, top: 80 },
    xAxis: {
      type: 'category',
      data: entries.map((e) => `用户${e.ownerId}`),
      axisLabel: { rotate: 30 },
    },
    yAxis: { type: 'value', name: '金额 (¥)' },
    series: [
      {
        name: '实际',
        type: 'bar',
        data: entries.map((e) => Number(e.actual)),
        itemStyle: { color: '#67c23a' },
      },
      {
        name: '目标',
        type: 'bar',
        data: entries.map((e) => Number(e.target)),
        itemStyle: { color: '#409eff' },
      },
    ],
  };
  chartInstance.setOption(option, true);
}

window.addEventListener('resize', () => chartInstance?.resize());
onMounted(load);

// ==================== Chart Insight ====================
// Single BAR chart: sales persons ranked by actual revenue (RANKING family).
// xDim='store' maps to person-level categorical ranking; domain='factory'.
const leaderboardSource = (): { chart: ChartWithMeta } | null => {
  const d = data.value;
  if (!d || d.entries.length < 2) return null;
  return {
    chart: {
      chartType: 'BAR',
      meta: { xDim: 'store', yMetric: 'revenue', aggregation: 'sum', domain: 'factory' },
      config: {
        xAxis: { data: d.entries.map((e) => `用户${e.ownerId}`) },
        series: [{ type: 'bar', data: d.entries.map((e) => Number(e.actual)) }],
      },
    },
  };
};

const leaderboardPerms = () => ({ canViewFinance: permissionStore.canWrite('finance') });

const { insight: leaderboardInsight, loading: leaderboardInsightLoading } = useChartInsight(
  leaderboardSource,
  leaderboardPerms,
  { factoryId: () => authStore.factoryId ?? '', autoTier2: true },
);
</script>

<template>
  <div class="sales-leaderboard">
    <div class="header">
      <h2>销售排行榜</h2>
      <p class="subtitle">org-wide ranking — HJ Round 13 §15 业绩 6 项 (排名 + 团队 quota)</p>
    </div>

    <div class="filter-bar">
      <el-form inline>
        <el-form-item label="周期">
          <el-select v-model="period" style="width: 120px">
            <el-option label="月度" value="MONTH" />
            <el-option label="季度" value="QUARTER" />
            <el-option label="年度" value="YEAR" />
          </el-select>
        </el-form-item>
        <el-form-item label="年份">
          <el-input-number v-model="year" :min="2024" :max="2099" style="width: 120px" />
        </el-form-item>
        <el-form-item :label="period === 'MONTH' ? '月份' : period === 'QUARTER' ? '季度' : '全年'">
          <el-select v-model="periodNum" style="width: 120px" :disabled="period === 'YEAR'">
            <el-option
              v-for="opt in periodNumChoices"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="Top">
          <el-input-number v-model="limit" :min="3" :max="50" style="width: 100px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div v-loading="loading" v-if="data" class="content">
      <div class="team-summary">
        <el-card class="kpi"><div class="label">团队目标 (Quota)</div>
          <div class="value primary">¥ {{ Number(data.teamTarget).toLocaleString() }}</div>
        </el-card>
        <el-card class="kpi"><div class="label">团队实际</div>
          <div class="value success">¥ {{ Number(data.teamActual).toLocaleString() }}</div>
        </el-card>
        <el-card class="kpi"><div class="label">团队完成率</div>
          <div class="value" :class="Number(data.teamCompletionRate) >= 100 ? 'success' : 'warning'">
            {{ data.teamCompletionRate }}%
          </div>
        </el-card>
      </div>

      <div ref="chartContainer" class="chart-box"></div>
      <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
      <ChartInsight :insight="leaderboardInsight" :loading="leaderboardInsightLoading" depth="detailed" />

      <el-table :data="data.entries" border>
        <el-table-column prop="rank" label="排名" width="80" />
        <el-table-column prop="ownerId" label="销售员 ID" />
        <el-table-column label="实际">
          <template #default="{ row }">¥ {{ Number(row.actual).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="目标">
          <template #default="{ row }">¥ {{ Number(row.target).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="完成率">
          <template #default="{ row }">{{ row.completionRate }}%</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.sales-leaderboard {
  padding: 16px;
  .header h2 { margin: 0; }
  .subtitle { color: #909399; margin: 4px 0 16px; font-size: 13px; }
  .filter-bar { margin-bottom: 16px; }
  .team-summary {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 16px;
    margin-bottom: 16px;
    .kpi {
      .label { color: #909399; font-size: 13px; }
      .value { font-size: 22px; font-weight: 600; margin-top: 8px;
        &.primary { color: #409eff; }
        &.success { color: #67c23a; }
        &.warning { color: #e6a23c; }
      }
    }
  }
  .chart-box { width: 100%; height: 360px; margin-bottom: 16px; }
}
</style>
