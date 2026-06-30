<template>
  <!--
    ChartInsightProvider.vue — Phase B keystone (2026-06-10).

    Problem solved:
      useChartInsight contains a Vue watcher that MUST be called at the top level
      of setup(). It CANNOT be called inside a v-for loop. Many surfaces render
      dynamic arrays of charts (unknown length, runtime types) and need per-chart
      insight state.

    Solution:
      This thin wrapper component calls useChartInsight ONCE in its own setup().
      Because each <ChartInsightProvider> is its own Vue component instance, mounting
      it N times inside a v-for is safe — each instance's watcher runs at that
      instance's setup() top level, never inside a loop.

    Usage in a v-for context (now safe):
      <ChartInsightProvider
        v-for="item in charts"
        :key="item.id"
        :chart="item.chart"
        :perms="userPerms"
        :factory-id="factoryId"
      />

    Props:
      chart      — ChartWithMeta | null (the chart object with .meta + .config).
                   Pass null to skip (renders nothing, no Tier2 call).
      perms      — UserPermissions (canViewFinance gate).
      factoryId  — Optional factory ID string (forwarded to Tier2 request body).
                   Defaults to ''.
      autoTier2  — Whether to automatically call Tier2 when Tier1 returns null.
                   Defaults to true.
      depth      — Passed through to <ChartInsight>. 'concise' (default) or 'detailed'.

    Reactive:
      Props are reactive. When the `chart` prop changes, the source getter re-fires
      and the watcher inside useChartInsight re-evaluates insight state.
  -->
  <ChartInsight :insight="displayInsight" :loading="loading" :depth="depth" :factory-id="factoryId" />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import ChartInsight from './ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';
import type { ChartWithMeta, InsightResult, UserPermissions } from './chartInsight';

const props = withDefaults(
  defineProps<{
    /**
     * The chart object (ChartWithMeta). Pass null to skip insight (renders nothing).
     * When this prop changes reactively, the composable's watcher re-evaluates.
     */
    chart: ChartWithMeta | null;
    /**
     * Current user's permission context.
     * Gates whether finance absolute ¥ values are visible.
     */
    perms: UserPermissions;
    /**
     * Factory ID forwarded to Tier2 request body.
     * Must match the JWT factoryId. Defaults to ''.
     */
    factoryId?: string;
    /**
     * Whether to automatically call Tier2 when Tier1 returns null.
     * Defaults to true (Tier2 is called on Tier1-null by default).
     */
    autoTier2?: boolean;
    /**
     * Display depth passed through to <ChartInsight>.
     * 'concise' = finding line only (default).
     * 'detailed' = finding + implication + suggestion.
     */
    depth?: 'concise' | 'detailed';
  }>(),
  {
    factoryId: '',
    autoTier2: true,
    depth: 'concise',
  },
);

/**
 * useChartInsight is called ONCE in this component's setup().
 *
 * v-for safety: each <ChartInsightProvider> instance has its own setup() execution,
 * so this watcher registration always happens at the top level of that instance's
 * setup — never inside a parent's loop body. Vue 3 composition API requires watchers
 * to be registered at setup() call time (not in event handlers or nested scopes).
 * Mounting N instances in a v-for creates N independent watchers, one per instance.
 *
 * Source getter: reads props.chart reactively. When `chart` prop changes (e.g.
 * the parent v-for updates), this getter returns a new value and the composable's
 * deep watcher fires, re-evaluating insight state for this instance.
 */
const { insight, loading } = useChartInsight(
  // source: reactive getter — returns { chart } when chart prop is non-null, null otherwise
  () => (props.chart != null ? { chart: props.chart } : null),
  // perms: reactive getter — re-reads props.perms on each evaluation
  () => props.perms,
  {
    factoryId: () => props.factoryId ?? '',
    autoTier2: props.autoTier2 !== false,
  },
);

const displayInsight = computed(() => localizeInsight(insight.value, props.chart));

function localizeInsight(raw: InsightResult | null, chart: ChartWithMeta | null): InsightResult | null {
  if (!raw || !chart) return raw;
  const context = insightContext(chart);
  const apply = (value?: string) => {
    if (!value) return value;
    const replaceLooseProject = (text: string) =>
      text
        .replace(/餐饮餐饮项目(\d+)/g, `${context.noun}$1`)
        .replace(/餐饮项目(\d+)/g, `${context.noun}$1`)
        .replace(/(^|[^\u4e00-\u9fa5])项目(\d+)/g, `$1${context.noun}$2`);
    return replaceLooseProject(value)
      .replace(/第(\d+)项/g, `${context.noun}$1`)
      .replace(/少数门店或少数菜品/g, `少数${context.noun}或关键环节`)
      .replace(/门店或菜品/g, context.noun)
      .replace(/供给、价格、曝光和执行/g, context.action)
      .replace(/整体效率/g, context.efficiency);
  };
  return {
    ...raw,
    finding: apply(raw.finding) || raw.finding,
    implication: apply(raw.implication),
    suggestion: apply(raw.suggestion),
  };
}

function insightContext(chart: ChartWithMeta) {
  const title = chart.title || '';
  const xDim = chart.meta?.xDim;
  const yMetric = String(chart.meta?.yMetric || '');
  const isReviewChart = /评价|口碑|点评|美团|差评|星级|好评/.test(title)
    || (chart.meta?.domain === 'restaurant' && /review|rating|star|score|comment/.test(yMetric));
  if (isReviewChart) {
    if (xDim === 'store') {
      return { noun: '门店评价', action: '评分、差评占比、回复和服务体验', efficiency: '口碑管理效率' };
    }
    if (xDim === 'channel') {
      return { noun: '评价平台', action: '平台结构、回复率和曝光质量', efficiency: '口碑运营效率' };
    }
    if (xDim === 'product') {
      return { noun: '菜品口碑', action: '评分、低星占比、回复和服务体验', efficiency: '口碑管理效率' };
    }
    return { noun: '评价分项', action: '评分、低星占比、回复和服务体验', efficiency: '口碑管理效率' };
  }
  if (chart.meta?.domain === 'restaurant') {
    if (xDim === 'store') {
      return { noun: '门店', action: '门店客流、客单价、渠道结构和班次执行', efficiency: '门店经营效率' };
    }
    if (xDim === 'product') {
      return { noun: '菜品', action: '菜品结构、价格带、出品稳定性和曝光', efficiency: '菜品经营效率' };
    }
    if (xDim === 'channel') {
      return { noun: '渠道', action: '堂食外卖结构、平台活动、客单价和履约', efficiency: '渠道经营效率' };
    }
    if (xDim === 'meal_period' || xDim === 'period' || xDim === 'time') {
      return { noun: '时段', action: '午市晚市客流、排班、出餐速度和活动节奏', efficiency: '时段经营效率' };
    }
    return { noun: '经营分项', action: '门店、菜品、渠道和执行', efficiency: '经营管理效率' };
  }
  return { noun: '项目', action: '结构、执行和数据录入', efficiency: '整体效率' };
}
</script>
