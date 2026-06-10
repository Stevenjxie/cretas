<template>
  <!--
    餐饮经营驾驶舱 — Gold 营收分析区 (May 29 2026)
    替代通用 schema-driven 模板卡 (对青花椒会误选"星级分" → 累计 SUM bug + 老板不关心)。
    展示老板每天最在乎的 Gold 全量真营收: 门店营收排行 + 堂食外卖渠道占比。
    数据源 gold.ts (getFinanceSummary / getChannelBreakdown), 全量 agg_daily, 不依赖上传。
    业态门控: 仅 RESTAURANT 渲染 (父组件 v-if), 制造业仍用 TemplateGrid。
  -->
  <section class="rgg-section">
    <header class="rgg-header">
      <span class="rgg-title-text">餐饮营收分析</span>
      <span class="rgg-subtitle">基于全量经营数据 · {{ dateLabel }}</span>
      <el-button v-if="!loading" size="small" type="primary" plain @click="load">
        <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
      </el-button>
    </header>

    <div v-if="loadError" class="rgg-error">
      加载营收分析失败: {{ loadError }}
    </div>

    <!--
      WS2 #7: 规则洞察条 (0 LLM). 仅在有数据可算时渲染 (revenueInsight 非空);
      数据不足或加载中不显示 (诚实, 不编).
    -->
    <div v-if="!loadError && !loading && revenueInsight" class="rgg-insight">
      <el-icon class="rgg-insight-icon"><MagicStick /></el-icon>
      <span>{{ revenueInsight }}</span>
    </div>

    <div v-if="!loadError" class="rgg-grid">
      <!-- 门店营收排行 -->
      <el-card class="rgg-card" shadow="never">
        <template #header>
          <div class="rgg-card-header"><el-icon><Sell /></el-icon><span>门店营收排行</span></div>
        </template>
        <el-skeleton v-if="loading" :rows="5" animated />
        <div v-else-if="topStores.length" class="rgg-rank-list">
          <div v-for="(s, i) in topStores" :key="s.storeId" class="rgg-rank-item">
            <span class="rgg-rank-no" :class="'rgg-rank-' + Math.min(i, 3)">{{ i + 1 }}</span>
            <span class="rgg-rank-name" :title="s.storeName">{{ s.storeName }}</span>
            <span class="rgg-rank-bar-wrap">
              <span class="rgg-rank-bar" :style="{ width: storePct(s.revenue) + '%' }"></span>
            </span>
            <span class="rgg-rank-val">{{ formatMoney(s.revenue) }}</span>
          </div>
          <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
          <ChartInsight
            :insight="storeInsight"
            :loading="storeInsightLoading"
            depth="detailed"
          />
        </div>
        <el-empty v-else description="暂无门店营收数据" :image-size="60" />
      </el-card>

      <!-- 堂食 / 外卖 / 渠道占比 -->
      <el-card class="rgg-card" shadow="never">
        <template #header>
          <div class="rgg-card-header"><el-icon><PieChart /></el-icon><span>渠道占比 (堂食 / 外卖)</span></div>
        </template>
        <el-skeleton v-if="loading" :rows="5" animated />
        <div v-else-if="channels.length" class="rgg-rank-list">
          <div v-for="(c, i) in channels" :key="c.channelId" class="rgg-rank-item">
            <span class="rgg-rank-name rgg-chan-name" :title="c.channelName">{{ c.channelName }}</span>
            <span class="rgg-rank-bar-wrap">
              <span class="rgg-rank-bar rgg-chan-bar" :style="{ width: Math.min(c.sharePct, 100) + '%' }"></span>
            </span>
            <span class="rgg-rank-pct">{{ c.sharePct.toFixed(1) }}%</span>
            <span class="rgg-rank-val">{{ formatMoney(c.amount) }}</span>
          </div>
          <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
          <ChartInsight
            :insight="channelInsight"
            :loading="channelInsightLoading"
            depth="detailed"
          />
        </div>
        <el-empty v-else description="暂无渠道数据" :image-size="60" />
      </el-card>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Refresh, Sell, PieChart, MagicStick } from '@element-plus/icons-vue';
import { getFinanceSummary, getChannelBreakdown } from '@/api/smartbi/gold';
import { formatNumber } from '@/utils/format-number';
import { buildRevenueInsight } from './revenueInsight';
import type { ChartWithMeta } from './chartInsight';
import { usePermissionStore } from '@/store/modules/permission';
import ChartInsight from './ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';

const permissionStore = usePermissionStore();

const props = defineProps<{
  factoryId: string;
  /** 日期区间 [start, end] (父组件按 Gold 数据真实区间设置) */
  dateRange: [string, string] | null;
}>();

const loading = ref(false);
const loadError = ref('');
const topStores = ref<Array<{ storeId: number; storeName: string; revenue: number; billCount: number }>>([]);
const channels = ref<Array<{ channelId: number; channelName: string; amount: number; billCount: number; sharePct: number }>>([]);

const dateLabel = computed(() =>
  props.dateRange ? `${props.dateRange[0]} 至 ${props.dateRange[1]}` : '全部数据',
);

// WS2 #7: 规则洞察 (0 LLM) — 从已 fetch 的门店营收 + 渠道占比算一句话; 数据不足返 null。
// UNTOUCHED — leave the top combined insight bar as-is.
const revenueInsight = computed<string | null>(() =>
  buildRevenueInsight(topStores.value, channels.value),
);

const maxStoreRevenue = computed(() =>
  topStores.value.reduce((m, s) => Math.max(m, s.revenue), 0),
);
function storePct(rev: number): number {
  return maxStoreRevenue.value > 0 ? (rev / maxStoreRevenue.value) * 100 : 0;
}

function formatMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return '¥' + formatNumber(v);
}

// ============================================================
// U6: per-card insights via useChartInsight composable
// Explicit meta provided (store/channel) — no deriveChartMeta needed.
// autoTier2=true: Tier1 fires instantly (0 token); null → Tier2 auto.
// ============================================================

/**
 * Reactive source getter for the store ranking card.
 * Returns null when data has not loaded yet (< 2 stores → Tier1 will return null;
 * composable will attempt Tier2 once Tier1-null is observed).
 */
const storeSource = (): { chart: ChartWithMeta } | null => {
  if (topStores.value.length < 1) return null;
  return {
    chart: {
      chartType: 'BAR',
      meta: { xDim: 'store', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: {
        xAxis: { data: topStores.value.map((s) => s.storeName) },
        series: [{ type: 'bar', data: topStores.value.map((s) => s.revenue) }],
      },
    },
  };
};

/**
 * Reactive source getter for the channel breakdown card.
 * Returns null when data has not loaded yet.
 */
const channelSource = (): { chart: ChartWithMeta } | null => {
  if (channels.value.length < 1) return null;
  return {
    chart: {
      chartType: 'BAR',
      meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: {
        xAxis: { data: channels.value.map((c) => c.channelName) },
        series: [{ type: 'bar', data: channels.value.map((c) => c.amount) }],
      },
    },
  };
};

const permsGetter = () => ({ canViewFinance: permissionStore.canWrite('finance') });

const { insight: storeInsight, loading: storeInsightLoading } = useChartInsight(
  storeSource,
  permsGetter,
  { factoryId: () => props.factoryId, autoTier2: true },
);

const { insight: channelInsight, loading: channelInsightLoading } = useChartInsight(
  channelSource,
  permsGetter,
  { factoryId: () => props.factoryId, autoTier2: true },
);

async function load() {
  if (!props.factoryId || !props.dateRange) return;
  loading.value = true;
  loadError.value = '';
  const [startDate, endDate] = props.dateRange;
  const capturedFactoryId = props.factoryId;
  try {
    const [fin, chan] = await Promise.all([
      getFinanceSummary({ factoryId: capturedFactoryId, startDate, endDate, topNStores: 10 }),
      getChannelBreakdown({ factoryId: capturedFactoryId, startDate, endDate }),
    ]);
    topStores.value = fin.topStores ?? [];
    channels.value = chan.channels ?? [];
    // Insights are computed reactively by useChartInsight watchers —
    // updating topStores/channels above triggers them automatically.
  } catch (e) {
    // 禁降级假数据: 失败显式报错, 不伪造
    loadError.value = e instanceof Error ? e.message : '请求失败';
  } finally {
    loading.value = false;
  }
}

// 父组件先探测 Gold 区间再设 dateRange, 故 watch 立即触发首次加载。
watch(
  () => [props.factoryId, props.dateRange?.[0], props.dateRange?.[1]],
  () => { void load(); },
  { immediate: true },
);
</script>

<style scoped>
.rgg-section { margin-top: 16px; }
.rgg-header { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.rgg-title-text { font-size: 16px; font-weight: 600; }
.rgg-subtitle { font-size: 12px; color: #909399; flex: 1; }
.rgg-error { color: #f56c6c; padding: 12px; background: #fef0f0; border-radius: 6px; }
.rgg-insight {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.5;
  color: #1f6f54;
  background: #f0f9f4;
  border: 1px solid #d4ecdd;
  border-radius: 6px;
}
.rgg-insight-icon { color: #2d8b57; flex-shrink: 0; }
.rgg-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 992px) { .rgg-grid { grid-template-columns: 1fr; } }
.rgg-card-header { display: flex; align-items: center; gap: 6px; font-weight: 600; }
.rgg-rank-list { display: flex; flex-direction: column; gap: 10px; }
.rgg-rank-item { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.rgg-rank-no { width: 20px; height: 20px; line-height: 20px; text-align: center; border-radius: 4px; font-size: 12px; background: #f0f2f5; color: #606266; flex-shrink: 0; }
.rgg-rank-0 { background: #ffe7ba; color: #d48806; }
.rgg-rank-1 { background: #f0f5ff; color: #2f54eb; }
.rgg-rank-2 { background: #f6ffed; color: #389e0d; }
.rgg-rank-name { width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex-shrink: 0; }
.rgg-chan-name { width: 90px; }
.rgg-rank-bar-wrap { flex: 1; height: 8px; background: #f5f7fa; border-radius: 4px; overflow: hidden; }
.rgg-rank-bar { display: block; height: 100%; background: linear-gradient(90deg, #5470c6, #91cc75); border-radius: 4px; }
.rgg-chan-bar { background: linear-gradient(90deg, #fac858, #ee6666); }
.rgg-rank-pct { width: 48px; text-align: right; color: #606266; flex-shrink: 0; }
.rgg-rank-val { width: 96px; text-align: right; font-weight: 600; color: #303133; flex-shrink: 0; }
</style>
