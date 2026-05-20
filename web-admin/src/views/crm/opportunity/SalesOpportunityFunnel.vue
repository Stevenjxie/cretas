<!--
  Sprint 7 wave 2 T4 — 销售商机 漏斗报表 (2026-05-20).

  ECharts funnel chart: 8 阶段 商机数量 + 金额 + 预期值 (× 概率).
-->
<template>
  <div class="sales-opportunity-funnel">
    <div class="page-header">
      <h2>商机漏斗</h2>
      <div class="header-actions">
        <el-switch
          v-model="onlyMine"
          active-text="我的商机"
          inactive-text="全部"
          @change="loadStats"
        />
        <el-button :icon="Refresh" @click="loadStats">刷新</el-button>
        <el-button :icon="DataLine" @click="goList">列表视图</el-button>
        <el-button :icon="View" @click="goKanban">看板视图</el-button>
      </div>
    </div>

    <el-card v-loading="loading" shadow="never">
      <!-- Summary KPI -->
      <div class="kpi-row" v-if="stats.length > 0">
        <div class="kpi-item">
          <div class="kpi-label">活跃商机</div>
          <div class="kpi-value">{{ totalActive }}</div>
        </div>
        <div class="kpi-item">
          <div class="kpi-label">商机总金额</div>
          <div class="kpi-value">{{ formatCurrency(totalAmount) }}</div>
        </div>
        <div class="kpi-item">
          <div class="kpi-label">加权预期</div>
          <div class="kpi-value highlight">{{ formatCurrency(totalExpected) }}</div>
        </div>
        <div class="kpi-item">
          <div class="kpi-label">赢单数</div>
          <div class="kpi-value won">{{ wonCount }}</div>
        </div>
        <div class="kpi-item">
          <div class="kpi-label">丢单数</div>
          <div class="kpi-value lost">{{ lostCount }}</div>
        </div>
      </div>

      <!-- Funnel chart -->
      <div class="chart-area">
        <div ref="chartContainer" class="chart-canvas"></div>
      </div>

      <!-- Stats table -->
      <el-table :data="stats" style="margin-top: 24px">
        <el-table-column label="阶段" width="160">
          <template #default="{ row }">
            <el-tag :type="stageMeta(row.stage).tagType" effect="light">
              {{ row.stageDisplay || stageLabel(row.stage) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="count" label="数量" width="120" align="right" />
        <el-table-column label="金额" align="right">
          <template #default="{ row }">
            {{ formatCurrency(row.totalValue) }}
          </template>
        </el-table-column>
        <el-table-column label="预期值 (× 概率)" align="right">
          <template #default="{ row }">
            {{ formatCurrency(row.expectedValue) }}
          </template>
        </el-table-column>
        <el-table-column label="阶段默认概率" width="140" align="center">
          <template #default="{ row }">
            {{ stageMeta(row.stage).probability }}%
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && stats.length === 0" class="empty-state">
        <el-empty description="暂无商机数据">
          <el-button type="primary" @click="goList">新增商机</el-button>
        </el-empty>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Refresh, DataLine, View } from '@element-plus/icons-vue';
import * as echarts from 'echarts';
import type { ECharts } from 'echarts';
import {
  STAGE_META,
  type FunnelStatEntry,
  stageMeta,
  stageLabel,
  formatCurrency,
  getFunnelStats,
} from '@/api/salesOpportunity';
import { useAuthStore } from '@/store/modules/auth';

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');
const currentUserId = computed<number>(
  () => Number((authStore.user as { id?: number } | null)?.id || 0),
);

const onlyMine = ref(false);
const loading = ref(false);
const stats = ref<FunnelStatEntry[]>([]);

const chartContainer = ref<HTMLDivElement | null>(null);
let chartInstance: ECharts | null = null;

const totalActive = computed(() =>
  stats.value
    .filter((s) => s.stage !== 'CLOSED_WON' && s.stage !== 'CLOSED_LOST')
    .reduce((sum, s) => sum + s.count, 0),
);
const totalAmount = computed(() =>
  stats.value.reduce((sum, s) => sum + Number(s.totalValue || 0), 0),
);
const totalExpected = computed(() =>
  stats.value.reduce((sum, s) => sum + Number(s.expectedValue || 0), 0),
);
const wonCount = computed(
  () => stats.value.find((s) => s.stage === 'CLOSED_WON')?.count || 0,
);
const lostCount = computed(
  () => stats.value.find((s) => s.stage === 'CLOSED_LOST')?.count || 0,
);

async function loadStats() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const ownerId = onlyMine.value ? currentUserId.value : undefined;
    stats.value = await getFunnelStats(factoryId.value, ownerId);
    // Sort according to canonical STAGE_META order
    stats.value.sort(
      (a, b) =>
        stageMeta(a.stage).order - stageMeta(b.stage).order,
    );
    renderChart();
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '加载漏斗数据失败';
    ElMessage.error(msg);
  } finally {
    loading.value = false;
  }
}

function renderChart() {
  if (!chartContainer.value) return;
  if (!chartInstance) {
    chartInstance = echarts.init(chartContainer.value);
  }

  // Build funnel data — order by stage canonical order; exclude CLOSED_LOST
  // so funnel is visually monotonic.
  const activeStages = STAGE_META.filter(
    (m) => m.value !== 'CLOSED_LOST',
  );
  const data = activeStages.map((m) => {
    const stat = stats.value.find((s) => s.stage === m.value);
    return {
      name: m.label,
      value: stat?.count || 0,
      itemStyle: { color: m.color },
      stageValue: m.value,
      totalValue: stat?.totalValue || 0,
      expectedValue: stat?.expectedValue || 0,
    };
  });

  const option: echarts.EChartsOption = {
    title: {
      text: '商机漏斗 (按数量)',
      left: 'center',
    },
    tooltip: {
      trigger: 'item',
      formatter: (params: unknown) => {
        const p = params as {
          name: string;
          value: number;
          data: { totalValue?: number; expectedValue?: number };
        };
        return [
          `<strong>${p.name}</strong>`,
          `数量: ${p.value}`,
          `金额: ${formatCurrency(p.data.totalValue)}`,
          `预期: ${formatCurrency(p.data.expectedValue)}`,
        ].join('<br/>');
      },
    },
    legend: {
      top: 30,
    },
    series: [
      {
        name: '商机漏斗',
        type: 'funnel',
        left: '10%',
        top: 80,
        bottom: 20,
        width: '80%',
        min: 0,
        max: Math.max(1, ...data.map((d) => d.value)),
        minSize: '20%',
        maxSize: '100%',
        sort: 'none',
        gap: 2,
        label: {
          show: true,
          position: 'inside',
          formatter: '{b}: {c}',
        },
        labelLine: {
          show: true,
        },
        itemStyle: {
          borderColor: '#fff',
          borderWidth: 1,
        },
        data,
      },
    ],
  };
  chartInstance.setOption(option, true);
}

function handleResize() {
  chartInstance?.resize();
}

function goList() {
  router.push({ name: 'CrmOpportunityList' });
}
function goKanban() {
  router.push({ name: 'CrmOpportunityKanban' });
}

onMounted(() => {
  loadStats();
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize);
  chartInstance?.dispose();
  chartInstance = null;
});

watch(() => stats.value, () => {
  renderChart();
}, { deep: false });
</script>

<style scoped>
.sales-opportunity-funnel {
  padding: 16px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-header h2 {
  margin: 0;
}
.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.kpi-row {
  display: flex;
  gap: 32px;
  padding: 16px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  margin-bottom: 24px;
}
.kpi-item {
  text-align: center;
  flex: 1;
}
.kpi-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.kpi-value {
  font-size: 22px;
  font-weight: 600;
  margin-top: 6px;
}
.kpi-value.highlight {
  color: var(--el-color-primary);
}
.kpi-value.won {
  color: var(--el-color-success);
}
.kpi-value.lost {
  color: var(--el-color-danger);
}
.chart-area {
  width: 100%;
  height: 420px;
}
.chart-canvas {
  width: 100%;
  height: 100%;
}
.empty-state {
  margin-top: 32px;
}
</style>
