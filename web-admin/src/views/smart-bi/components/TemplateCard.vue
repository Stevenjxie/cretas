<template>
  <el-card class="tpl-card" shadow="hover">
    <template #header>
      <div class="tpl-header">
        <span
          class="tpl-title"
          :title="uploadLabel ? `数据来源: ${uploadLabel} (${formattedDate})` : undefined"
        >{{ title }}</span>
      </div>
    </template>

    <!-- Loading -->
    <div v-if="status === 'loading'" class="tpl-loading">
      <el-skeleton :rows="4" animated />
    </div>

    <!-- Loaded: real chart + KPI + insight -->
    <template v-else-if="status === 'loaded' && item">
      <div v-if="kpis.length > 0" class="tpl-kpis">
        <div v-for="kpi in kpis" :key="kpi.label" class="tpl-kpi" :title="kpi.value">
          <div class="tpl-kpi-label">{{ kpi.label }}</div>
          <div
            class="tpl-kpi-value"
            :class="{ 'tpl-kpi-value--long': kpi.isLongText }"
          >{{ kpi.value }}</div>
        </div>
      </div>
      <div ref="chartRef" class="tpl-chart" v-if="chartOption"></div>
      <!-- R6-1: collapsible insight — long text clamps to 3 lines with
           "more/less" toggle so user sees short at-a-glance, expands when needed. -->
      <div v-if="insightText" class="tpl-insight">
        <div
          class="tpl-insight-text"
          :class="{ 'tpl-insight-text--clamped': !insightExpanded && insightIsLong }"
          :title="insightText"
        >{{ insightText }}</div>
        <button
          v-if="insightIsLong"
          class="tpl-insight-toggle"
          type="button"
          @click="insightExpanded = !insightExpanded"
        >{{ insightExpanded ? '收起' : '展开' }}</button>
      </div>
    </template>

    <!-- Empty: code was in some upload but not this one -->
    <div v-else-if="status === 'missing'" class="tpl-empty">
 <div class="tpl-empty-icon"></div>
      <div class="tpl-empty-title">该数据集不包含 [{{ title }}] 所需字段</div>
      <div class="tpl-empty-hint">上传含 {{ requiredFields }} 的文件后将自动生成</div>
    </div>

    <!-- Empty: code never materialized for this factory -->
    <div v-else-if="status === 'never'" class="tpl-empty">
 <div class="tpl-empty-icon"></div>
      <div class="tpl-empty-title">尚未为该工厂生成过 [{{ title }}]</div>
      <div class="tpl-empty-hint">上传含 {{ requiredFields }} 的数据文件</div>
      <!-- UX P2-10: 空工厂 onboarding — CTA 直接跳上传 -->
      <el-button
        class="tpl-empty-cta"
        type="primary"
        size="small"
        plain
        @click="$emit('go-upload', code)"
      >去上传数据</el-button>
    </div>

    <!-- Footer: 数据来源 + 生成时间 (P2-17: 从标题挪到这里,不挤占卡片标题行) -->
    <div v-if="uploadLabel && status === 'loaded'" class="tpl-footer">
 <span :title="`文件: ${uploadLabel}`"> {{ formattedDate }}</span>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch, nextTick } from 'vue';
import * as echarts from 'echarts';
import type { ECharts } from 'echarts';
import type { AnalysisResultItem } from '@/api/smartbi/analysisResults';
import { usePermissionStore } from '@/store/modules/permission';
import {
  getTemplateTitle,
  getRequiredFields,
} from '../composables/useTemplateMap';

type CardStatus = 'loading' | 'loaded' | 'missing' | 'never';

const props = defineProps<{
  code: string;
  item?: AnalysisResultItem;
  status: CardStatus;
}>();

// UX P2-10: 空状态 CTA 引导
defineEmits<{
  (e: 'go-upload', code: string): void;
}>();

const permissionStore = usePermissionStore();
const canViewPrice = computed(() => permissionStore.canViewPrice);

const chartRef = ref<HTMLElement>();
let chartInstance: ECharts | null = null;

const title = computed(() => getTemplateTitle(props.code));
const requiredFields = computed(() => getRequiredFields(props.code));

const uploadLabel = computed(() => props.item?.uploadLabel);
const formattedDate = computed(() => {
  const d = props.item?.uploadCreatedAt;
  if (!d) return '';
  try {
    return new Date(d).toLocaleDateString('zh-CN');
  } catch {
    return d.slice(0, 10);
  }
});

// English KPI key → 中文. Templates emit mixed camelCase keys; map the
// common ones here so users see localized labels. Unknown keys fall
// through to the raw key (better than blocking render).
const KPI_LABEL_MAP: Record<string, string> = {
  // generic totals
  total: '合计',
  totalRevenue: '营业额',
  totalTracked: '统计总数',
  totalOrders: '订单总数',
  totalItemCount: '明细总数',
  grandTotal: '总计',
  grandRevenue: '营业额',
  grandOrders: '订单总数',
  grandAmount: '合计金额',
  revenue: '营业额',
  orders: '订单数',
  orderCount: '订单数',
  billCount: '单数',
  avgBillValue: '客单价',
  avgOrder: '平均客单',
  storeCount: '门店数',
  dayCount: '天数',
  periodCount: '期数',
  monthCount: '月份数',
  latestMonth: '最近月份',
  latestRevenue: '最近月营业额',
  // peaks / extremes
  peakValue: '峰值',
  peakPeriod: '峰值日期',
  peakMonth: '峰值月份',
  peakArea: '峰值区域',
  peakSlot: '峰值时段',
  peakRevenue: '峰值营业额',
  // R5-9: two "worst/trough" keys used to read identically. Differentiate:
  // worstMonth = the worst single value; troughMonth = the smoothed trough
  // point from anomaly detection.
  worstMonth: '最差月份',
  troughMonth: '平滑低谷月',
  // stats
  max: '最大值',
  min: '最小值',
  mean: '均值',
  std: '标准差',
  median: '中位数',
  avg: '平均值',
  // deltas / ratios
  deltaPct: '波动率(%)',
  dodDeltaPct: '环比(%)',
  yoyDeltaPct: '同比(%)',
  sharePct: '占比(%)',
  netSharePct: '到账率(%)',
  anomalyCount: '异常月数',
  weekdayAvgOrder: '工作日均客单',
  weekendAvgOrder: '周末均客单',
  weekendOrderShare: '周末订单占比(%)',
  // dimensions (generic top_n / category_distribution / anomaly)
  dimCount: '维度数',
  topLabel: 'Top 项',
  topValue: 'Top 值',
  topSharePct: 'Top 占比(%)',
  topCategory: 'Top 分类',
  categoryCount: '分类数',
  topCategoryShare: 'Top 分类占比(%)',
  // finance
  netRevenue: '实收',
  grossRevenue: '应收',
  hasCostData: '是否含成本',
  topSlot: '主要时段',
  topChannel: '主要渠道',
  topPlatform: '最大平台',
  topPlatformAmount: '最大平台金额',
  platformCount: '平台数',
  // stored value card
  cardTotal: '储值卡消费',
  cardOrders: '储值卡笔数',
  // dish / product
  topDishName: '热销菜品',
  topDishQty: '热销销量',
  topDishRevenue: '热销营收',
  distinctDishes: '菜品总数',
  bottomDish: '最滞销菜品',
  bottomQty: '最滞销销量',
  nearZeroCount: '近零销量数',
  // combo
  comboOrders: '套餐订单数',
  topComboName: '热销套餐',
  usageRatePct: '使用率(%)',
  // product
  topProduct: '热销商品',
  topProductRevenue: '热销商品营收',
};

function localizeKpiKey(key: string): string {
  return KPI_LABEL_MAP[key] || key;
}

/** Format a large number with 中式万/亿 units.
 * - |n| >= 1e8 → "X.XX亿"
 * - |n| >= 1e4 → "X.XX万"
 * - otherwise  → 千分位 + 2 decimals
 * Integers <10000 stay as integers (no trailing .00).
 * Percentages (0..100 range-hinted) use plain formatting so "16.79%" stays
 * readable (caller passes the % sign separately if needed). */
function formatNumberCN(n: number): string {
  if (!isFinite(n)) return String(n);
  const abs = Math.abs(n);
  if (abs >= 1e8) {
    return (n / 1e8).toLocaleString('zh-CN', {
      minimumFractionDigits: 2, maximumFractionDigits: 2,
    }) + '亿';
  }
  if (abs >= 1e4) {
    return (n / 1e4).toLocaleString('zh-CN', {
      minimumFractionDigits: 2, maximumFractionDigits: 2,
    }) + '万';
  }
  return n.toLocaleString('zh-CN', {
    minimumFractionDigits: Number.isInteger(n) ? 0 : 2,
    maximumFractionDigits: 2,
  });
}

/** Keys that represent currency amounts — add ¥ prefix even when value
 * is small enough to stay in 千分位 format. */
const CURRENCY_KEY_RE = /(?:^|[A-Z_])(?:revenue|amount|cost|price|avgorder|avgbill|peakvalue|topvalue|grandamount|topplatformamount|netrevenue|grossrevenue|carddeposit|cardtotal)(?:$|[A-Z_])/i;

function formatKpiValue(value: unknown, key?: string): { text: string; isLong: boolean } {
  if (value === null || value === undefined) return { text: '—', isLong: false };
  if (typeof value === 'boolean') {
    return { text: value ? '是' : '否', isLong: false };
  }
  if (typeof value === 'number') {
    const isPercent = key ? /Pct|Share|Rate|占比/i.test(key) : false;
    const isCount = key ? /Count|Orders|Bills|Qty|Total$/i.test(key) && Number.isInteger(value) && Math.abs(value) < 1e6 : false;
    const isCurrency = key ? CURRENCY_KEY_RE.test(key) : false;
    let text: string;
    if (isPercent) {
      text = value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    } else if (isCount) {
      text = value.toLocaleString('zh-CN');
    } else {
      text = formatNumberCN(value);
    }
    // Prepend ¥ for known currency keys (after 万/亿 unit is already appended
    // by formatNumberCN). Skip if value already includes a currency-looking prefix.
    if (isCurrency && !/[¥￥$]/.test(text)) {
      text = '¥' + text;
    }
    return { text, isLong: false };
  }
  const text = String(value);
  return { text, isLong: text.length > 10 };
}

const kpis = computed(() => {
  const kv = props.item?.kpiValues;
  if (!kv || typeof kv !== 'object') return [];
  let entries = Object.entries(kv);
  // Defense-in-depth: drop currency-shape KPIs when role lacks canViewPrice.
  // Backend @PriceSensitive already nulls the value, but rendering ¥ + empty
  // (or — placeholder) wastes a slot. Filter so non-money KPIs fill the 4 slots.
  if (!canViewPrice.value) {
    entries = entries.filter(([key]) => !CURRENCY_KEY_RE.test(key));
  }
  return entries
    .slice(0, 4)
    .map(([key, value]) => {
      const formatted = formatKpiValue(value, key);
      return {
        label: localizeKpiKey(key),
        value: formatted.text,
        isLongText: formatted.isLong,
      };
    });
});

/** Format number for chart axis with 万/亿 units. */
function fmtNum(v: unknown): string {
  if (typeof v !== 'number' || !isFinite(v)) return String(v ?? '');
  return formatNumberCN(v);
}

/** Chinese count-category hints in axis/series name. When bar/line
 * series context carries a count semantic (订单/笔/件/人/次/份 etc),
 * keep 千分位 instead of 万/亿. Applies to: series.name, params.name
 * (axis category label), params.seriesName. */
const COUNT_CTX_RE = /(订单|笔数|单数|次数|件数|人次|份数|数量|个数|只数|场次)/;
const CURRENCY_CTX_RE = /(营业额|金额|营收|收入|支出|销售|消费|扣减|票面)/;

function fmtChartLabel(params: any): string {
  const v = params?.value;
  if (typeof v !== 'number' || !isFinite(v)) return String(v ?? '');
  const ctx = [
    typeof params?.name === 'string' ? params.name : '',
    typeof params?.seriesName === 'string' ? params.seriesName : '',
  ].join(' ');
  if (COUNT_CTX_RE.test(ctx)) {
    return v.toLocaleString('zh-CN', {
      minimumFractionDigits: Number.isInteger(v) ? 0 : 2,
      maximumFractionDigits: 2,
    });
  }
  // Default (including explicit currency context): 万/亿 format.
  return formatNumberCN(v);
}

/** Truncate long labels for axis / legend / pie slices.
 * ECharts doesn't natively handle long Chinese product names — we cut
 * to N chars + … so charts remain readable. */
function truncateLabel(s: unknown, max = 10): string {
  const str = String(s ?? '');
  return str.length > max ? str.slice(0, max) + '…' : str;
}

/** Post-process any chart option to apply number formatters + label
 * truncation. Backend templates don't know client display conventions,
 * so FE enhances here. Non-destructive — only sets what's missing. */
function enhanceChartOption(option: unknown): unknown {
  if (!option || typeof option !== 'object') return option;
  const opt = JSON.parse(JSON.stringify(option)) as Record<string, any>; // deep clone

  // yAxis formatter (handle single object or array)
  const yAxes = Array.isArray(opt.yAxis) ? opt.yAxis : opt.yAxis ? [opt.yAxis] : [];
  for (const y of yAxes) {
    if (y && typeof y === 'object') {
      y.axisLabel = y.axisLabel || {};
      if (!y.axisLabel.formatter) y.axisLabel.formatter = (v: unknown) => fmtNum(v);
    }
  }

  // xAxis: truncate category labels
  const xAxes = Array.isArray(opt.xAxis) ? opt.xAxis : opt.xAxis ? [opt.xAxis] : [];
  for (const x of xAxes) {
    if (x && typeof x === 'object') {
      x.axisLabel = x.axisLabel || {};
      if (x.axisLabel.formatter === undefined) {
        x.axisLabel.formatter = (v: unknown) => truncateLabel(v, 8);
      }
    }
  }

  // tooltip: global value formatter
  if (!opt.tooltip) opt.tooltip = {};
  if (!opt.tooltip.valueFormatter) {
    opt.tooltip.valueFormatter = (v: unknown) => fmtNum(v);
  }

  // R14: auto-hide overlapping bar/line labels (grouped bar charts
  // emit 2+ labels at nearby positions, they collide). hideOverlap
  // keeps the higher-z label and drops the others; tooltip still
  // shows full values on hover.
  if (!opt.labelLayout) {
    opt.labelLayout = { hideOverlap: true };
  }

  // Series-level bar/line label + pie label — truncate + format numbers
  const series = Array.isArray(opt.series) ? opt.series : opt.series ? [opt.series] : [];
  for (const s of series) {
    if (!s || typeof s !== 'object') continue;
    if (s.type === 'pie') {
      s.label = s.label || {};
      if (!s.label.formatter) {
        s.label.formatter = '{b}: {d}%';
      }
      if (s.label.overflow === undefined) s.label.overflow = 'truncate';
      if (s.label.width === undefined) s.label.width = 100;
      // R5-4: avoid label overlap on small/adjacent slices.
      if (s.avoidLabelOverlap === undefined) s.avoidLabelOverlap = true;
    } else if (s.type === 'bar' || s.type === 'line' || s.type === 'scatter') {
      s.label = s.label || {};
      // Context-aware formatter: count-semantic categories keep 千分位,
      // everything else gets 万/亿. See fmtChartLabel.
      s.label.formatter = (p: any) => fmtChartLabel(p);
      if (Array.isArray(s.data)) {
        for (const d of s.data) {
          if (d && typeof d === 'object' && d.label) {
            d.label.formatter = (p: any) => fmtChartLabel(p);
          }
        }
      }
    }
  }

  // legend: truncate long entries + scroll-paginate when too many
  if (opt.legend) {
    const legends = Array.isArray(opt.legend) ? opt.legend : [opt.legend];
    for (const lg of legends) {
      if (lg && typeof lg === 'object') {
        if (!lg.formatter) lg.formatter = (name: string) => truncateLabel(name, 10);
        if (lg.textStyle === undefined) lg.textStyle = { overflow: 'truncate', width: 120 };
        // R15/R16: paginate legend when many series OR when a single pie
        // series has many data slices. Using scroll unconditionally is
        // safe — ECharts auto-falls-back to normal display if entries
        // fit without pagination.
        if (lg.type === undefined) {
          const seriesCount = Array.isArray(opt.series) ? opt.series.length : 0;
          const firstPieData = Array.isArray(opt.series) && opt.series[0] &&
            (opt.series[0] as any).type === 'pie' &&
            Array.isArray((opt.series[0] as any).data)
              ? (opt.series[0] as any).data.length : 0;
          if (seriesCount > 6 || firstPieData > 6) {
            lg.type = 'scroll';
          }
        }
      }
    }
  }

  return opt;
}

const chartOption = computed(() => {
  const configs = props.item?.chartConfigs;
  if (!Array.isArray(configs) || configs.length === 0) return null;
  return enhanceChartOption(configs[0]);
});

// Backend insight templates use 书面 phrases that confuse operators.
// Replace the common ones with 白话 equivalents. Applied last so any
// future frontend-only rewording doesn't require a backend deploy.
const INSIGHT_REPLACEMENTS: Array<[RegExp, string]> = [
  [/余下梯队收敛明显/g, '其余门店之间差距不大'],
  [/可复盘成功因素用于复制/g, '可以总结做对了什么再用一次'],
  [/堂食客单价/g, '堂食人均消费'],
  [/人均订单量/g, '人均下单次数'],
  [/到账率/g, '实收比例'],
  [/DoD ↑/g, '日环比 ↑'],
  [/DoD ↓/g, '日环比 ↓'],
  [/YoY \+/g, '同比 +'],
  [/YoY -/g, '同比 -'],
  [/MoM \+/g, '环比 +'],
  [/MoM -/g, '环比 -'],
  [/\bstd\b/g, '标准差'],
  [/\bmean\b/g, '均值'],
  [/\bσ\b/g, '标准差'],
];

/** Normalize big numbers in insight text to 万/亿 to match KPI chip style.
 *
 * Matches: optional ¥ or ￥ prefix, then either thousands-separated
 * "12,345,678" or bare 5+ digit "123456" (treat as currency/count).
 * Optional trailing " 元" / "元".
 *
 * Skips: dates (YYYY-MM-DD / YYYY/MM/DD), percentages (already small),
 * years (4-digit standalone), time ranges (HH:MM).
 *
 * Example: "累计 36,176,041 元" → "累计 3,617.60万 元" (kept 元 for safety)
 *          "峰值 2025-09-29 (894,825)" → untouched date, "(89.48万)"
 */
const BIG_NUMBER_RE = /(¥|￥)?((?:\d{1,3}(?:,\d{3})+(?:\.\d+)?)|(?:\d{5,}(?:\.\d+)?))/g;

/** Chinese count suffixes — when a number is followed by one of these,
 * keep the 千分位 format (count semantics) instead of converting to 万/亿.
 * 元 / 万元 / 亿元 are currency and SHOULD convert. */
const COUNT_SUFFIX_RE = /^\s*[单笔份个条项家只次套道件场人张朵颗]/;

function normalizeInsightNumbers(text: string): string {
  return text.replace(BIG_NUMBER_RE, (match, currency, raw, offset, full) => {
    // Skip if inside a date-like context (preceded by '-' '/' year-month-day).
    const preCtx = full.slice(Math.max(0, offset - 5), offset);
    if (/\d{4}[-/]$/.test(preCtx) || /\d{2}[-/]$/.test(preCtx)) return match;
    // Skip standalone 4-digit year in 1900-2099 (no currency prefix).
    if (raw.length === 4 && !currency && parseInt(raw, 10) >= 1900 && parseInt(raw, 10) <= 2099) return match;

    // R4-3: skip if followed by count suffix (单/笔/份/个 etc).
    // Keep currency-prefixed numbers (¥123456) as amounts even if followed
    // by 单 (rare) — currency prefix is the stronger signal.
    const postCtx = full.slice(offset + match.length);
    if (!currency && COUNT_SUFFIX_RE.test(postCtx)) return match;

    const n = parseFloat(raw.replace(/,/g, ''));
    if (!isFinite(n) || n < 10000) return match;

    const abs = Math.abs(n);
    let out: string;
    if (abs >= 1e8) {
      out = (n / 1e8).toFixed(2) + '亿';
    } else if (abs >= 1e4) {
      out = (n / 1e4).toFixed(2) + '万';
    } else {
      return match;
    }
    return (currency || '') + out;
  });
}

function humanizeInsight(raw: string): string {
  let out = raw;
  for (const [re, repl] of INSIGHT_REPLACEMENTS) {
    out = out.replace(re, repl);
  }
  out = normalizeInsightNumbers(out);
  return out;
}

const insightText = computed(() => {
  const insights = props.item?.insights;
  if (!Array.isArray(insights) || insights.length === 0) return '';
  return insights
    .map((i) => (typeof i === 'string' ? i : (i as { text?: string }).text || ''))
    .filter(Boolean)
    .map(humanizeInsight)
    .join('\n');
});

// R6-1: clamp long insights to 3 lines with expand/collapse toggle
const insightExpanded = ref(false);
const INSIGHT_CLAMP_THRESHOLD = 90; // chars — beyond this, show toggle
const insightIsLong = computed(() => insightText.value.length > INSIGHT_CLAMP_THRESHOLD);

function renderChart() {
  if (!chartRef.value || !chartOption.value) {
    chartInstance?.dispose();
    chartInstance = null;
    return;
  }
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value);
  }
  chartInstance.setOption(chartOption.value as echarts.EChartsOption, true);
}

onMounted(() => {
  nextTick(renderChart);
});

watch(
  () => [props.status, props.item],
  () => nextTick(renderChart),
  { deep: true },
);
</script>

<style scoped>
/* N9: card fills grid cell height so empty-chart cards don't shrink.
 * :deep() because el-card wraps the root node. */
.tpl-card {
  margin-bottom: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}
.tpl-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.tpl-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.tpl-title {
  font-weight: 600;
  font-size: 14px;
}
.tpl-badge {
  font-size: 12px;
  color: #909399;
}
/* P2-17: card footer 来源日期小字,不挤占标题行 */
.tpl-footer {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #ebeef5;
  font-size: 11px;
  color: #c0c4cc;
  text-align: right;
}
/* R6-2 + R9-1: grid auto-fit with wider min so "¥X,XXX.XX万" fits on one
 * line. 135px min → on typical 400-500px card width → 3-col layout for
 * 4-KPI cards, 2-col for 3-KPI (fills naturally). */
.tpl-kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(135px, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
.tpl-kpi {
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 4px;
  min-width: 0;          /* grid handles sizing — let chip shrink if needed */
  overflow: hidden;
}
.tpl-kpi-label {
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 2px;
}
/* R9-1: KPI values must never truncate — critical data. Allow wrap instead
 * of ellipsis, shrink font if extremely long. 2-line max. */
.tpl-kpi-value {
  font-size: 17px;
  font-weight: 600;
  color: #303133;
  line-height: 1.25;
  word-break: break-all;  /* allow break inside long "¥3,617.60万" */
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
/* Long string values (e.g. 菜品名 "招牌青花椒鱼(微麻微辣)(一吃)") —
 * smaller font + 2-line clamp so whole name is readable. */
.tpl-kpi-value--long {
  font-size: 13px;
  font-weight: 500;
  white-space: normal;
  line-height: 1.3;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.tpl-chart {
  height: 260px;
  width: 100%;
}
.tpl-insight {
  margin-top: 12px;
  padding: 10px;
  background: #f0f9ff;
  border-left: 3px solid #409eff;
  font-size: 13px;
}
.tpl-insight-text {
  white-space: pre-wrap;
  line-height: 1.5;
}
/* R6-1: 3-line clamp with fade-out for long insights. */
.tpl-insight-text--clamped {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.tpl-insight-toggle {
  margin-top: 4px;
  padding: 0;
  background: none;
  border: 0;
  color: #409eff;
  font-size: 12px;
  cursor: pointer;
}
.tpl-insight-toggle:hover {
  text-decoration: underline;
}
.tpl-empty {
  text-align: center;
  padding: 32px 16px;
  color: #909399;
}
.tpl-empty-icon {
  font-size: 32px;
  margin-bottom: 8px;
}
.tpl-empty-title {
  font-size: 14px;
  color: #606266;
  margin-bottom: 4px;
}
.tpl-empty-hint {
  font-size: 12px;
}
.tpl-empty-cta {
  margin-top: 12px;
}
.tpl-loading {
  padding: 16px;
}
</style>
