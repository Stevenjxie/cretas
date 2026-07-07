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
  const applyLabels = (value?: string) => {
    if (!value) return value;
    if (chart.meta?.domain === 'restaurant') return value;
    let localized = value;
    const labels = chart.config?.xAxis?.data ?? [];
    labels.slice(0, 6).forEach((label, index) => {
      const name = String(label);
      localized = localized
        .replace(new RegExp(`${context.noun}${index + 1}`, 'g'), name)
        .replace(new RegExp(`项目${index + 1}`, 'g'), name);
    });
    return localized;
  };
  const finding = applyLabels(apply(raw.finding)) || raw.finding;
  const implication = applyLabels(apply(raw.implication));
  const suggestion = applyLabels(apply(raw.suggestion));
  const title = chart.title || '';
  const labels = [
    ...axisData(chart.config?.xAxis),
    ...axisData(chart.config?.yAxis),
  ].map((label) => String(label));
  const labelText = labels.join(' ');
  const hasNonDishLine = /无需餐具|测试商品|打包费|配送费/.test(labelText);
  if ((/慢销|滞销/.test(title) && !hasNonDishLine)) {
    return {
      ...raw,
      finding: '这张慢销榜已经排除了测试商品、无需餐具这类系统行，重点看真实菜品里谁长期卖不动。',
      implication: '老板不要只看销量低，还要看它是不是占备货、占厨房工位、或者造成损耗。',
      suggestion: '先把低销量菜分三类：能做套餐搭配的保留，毛利好但曝光低的试推一周，只拖库存和工位的限量或下架。',
    };
  }
  if (/慢销|滞销/.test(title) || hasNonDishLine) {
    return {
      ...raw,
      finding: '这张慢销榜里混有“测试商品/无需餐具”这类非正式菜品，老板先不要直接按榜单下架。',
      implication: '真正要处理的是销量低、还占备货或厨房工位的正式菜品；只看排名会误伤临时规格或系统行。',
      suggestion: '先把非菜品行排除，再看酒酿馒头、腐竹-砂锅煲这类真实菜品：能并入套餐的并入，只拖库存的限量或下架。',
    };
  }
  if (/畅销|热销|菜品|销量|Top\s*\d*/i.test(title) && chart.meta?.domain === 'restaurant' && context.noun === '菜品') {
    return {
      ...raw,
      finding: 'Top 菜品可以作为主推池，但不等于每一道都适合外卖或套餐。',
      implication: '老板要先看三件事：毛利能不能守住、出餐是否稳定、包装和差评能不能扛住。',
      suggestion: '先从第一名和套餐类菜品里挑 1-2 个做小范围测试，再用毛利、客单价和差评标签决定要不要放大。',
    };
  }
  if (/周末|周中/.test(title) && /头尾大约差1\.0倍|差距很小/.test(finding)) {
    return {
      ...raw,
      finding: '周末和周中差距很小，基本可以按同一水平看。',
      implication: '这张图不适合硬分第一名和末位，重点看两边的客流、客单价、排班和差评有没有结构差异。',
      suggestion: '建议不要因为这张图单独做大促，先用同一套备货和排班基准，再查午晚市、客单价和差评标签是否有明显差别。',
    };
  }
  return {
    ...raw,
    finding,
    implication,
    suggestion,
  };
}

function axisData(axis: unknown): unknown[] {
  const firstAxis = Array.isArray(axis) ? axis[0] : axis;
  if (!firstAxis || typeof firstAxis !== 'object') return [];
  const data = (firstAxis as { data?: unknown }).data;
  return Array.isArray(data) ? data : [];
}

function insightContext(chart: ChartWithMeta) {
  const title = chart.title || '';
  const xDim = String(chart.meta?.xDim || '');
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
    if (/周末|周中|工作日|午市|晚市|早餐|夜宵|时段|小时|日期|月份|星期/.test(title)) {
      return { noun: '时段', action: '不同时段的客流、客单价、排班、出餐速度和活动节奏', efficiency: '时段经营效率' };
    }
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
