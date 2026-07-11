<script setup lang="ts">
/**
 * G4 — 单张诊断卡片。
 *
 * 渲染一个 metric 的诊断: severity 左色条 + 当前值/行业中位/偏差 + 可折叠
 * 描述 + RxAction 时间框 tabs (立即 P0 / 本周 P1 / 本月 P2)。
 *
 * formatValue 按 metricKey 的 scale 显示:
 *   - 0-100 scale (food/labor/discount): 直接 + "%"
 *   - 0-1 scale (delivery/channel): ×100 + "%"
 *   - review_score_decline: 0-1 delta → ×100 + "pp"
 *   - cost_rigidity: 纯比值, 2 位小数
 */
import { computed, ref } from 'vue';
import type { DiagnosisItem, RxAction } from '@/api/smartbi/healthCheck';

const props = defineProps<{
  diagnosis: DiagnosisItem;
  defaultExpanded?: boolean;
}>();

const expanded = ref(props.defaultExpanded ?? false);

// 0-1 scale metrics (inline-threshold). Everything else is %-scale or plain.
const ZERO_ONE_KEYS = new Set([
  'delivery_dependency',
  'channel_collection_rate',
  'avg_ticket_vs_target',
]);
const RATIO_KEYS = new Set(['cost_rigidity']);
const DELTA_PP_KEYS = new Set(['review_score_decline']);

function fmt(value: number | null, key: string): string {
  if (value == null) return '—';
  if (RATIO_KEYS.has(key)) return value.toFixed(2);
  if (DELTA_PP_KEYS.has(key)) {
    const pp = value * 100;
    return `${pp >= 0 ? '+' : ''}${pp.toFixed(1)}pp`;
  }
  if (ZERO_ONE_KEYS.has(key)) return `${(value * 100).toFixed(1)}%`;
  // %-scale (already 0-100)
  return `${value.toFixed(1)}%`;
}

const actualDisplay = computed(() => fmt(props.diagnosis.actualValue, props.diagnosis.metricKey));
const benchmarkDisplay = computed(() =>
  props.diagnosis.benchmarkMedian != null
    ? fmt(props.diagnosis.benchmarkMedian, props.diagnosis.metricKey)
    : null,
);

const severityMeta = computed(() => {
  switch (props.diagnosis.severity) {
    case 'critical':
      return { label: '严重', cls: 'sev-critical', color: '#b91c1c' };
    case 'warning':
      return { label: '预警', cls: 'sev-warning', color: '#d97706' };
    default:
      return { label: '提示', cls: 'sev-info', color: '#0284c7' };
  }
});

// deltaPp 偏差展示 (绝对值 + 方向)。review/0-1 delta 已在描述中体现, 这里只在
// 有 benchmarkMedian 时显示 (benchmark-source metrics)。
const deltaDisplay = computed(() => {
  if (props.diagnosis.benchmarkMedian == null) return null;
  const pp = props.diagnosis.deltaPp;
  return `${pp >= 0 ? '+' : ''}${pp.toFixed(1)}pp`;
});

// RxAction 按 timeframe 优先级分到 3 个时间框 tab。
type TimeKey = 'now' | 'week' | 'month';
const TAB_DEFS: Array<{ key: TimeKey; label: string }> = [
  { key: 'now', label: '立即' },
  { key: 'week', label: '本周' },
  { key: 'month', label: '本月' },
];

function bucketOf(a: RxAction): TimeKey {
  // priority P0 → 立即; P1 → 本周; P2/其他 → 本月。timeframe 文本也参考。
  if (a.priority === 'P0') return 'now';
  if (a.priority === 'P1') return 'week';
  return 'month';
}

const buckets = computed<Record<TimeKey, RxAction[]>>(() => {
  const out: Record<TimeKey, RxAction[]> = { now: [], week: [], month: [] };
  for (const a of props.diagnosis.rxActions || []) {
    out[bucketOf(a)].push(a);
  }
  return out;
});

const availableTabs = computed(() => TAB_DEFS.filter((t) => buckets.value[t.key].length > 0));
const activeTab = ref<TimeKey>('now');

// 确保 activeTab 落在有内容的 tab 上。
const effectiveTab = computed<TimeKey>(() => {
  if (buckets.value[activeTab.value]?.length) return activeTab.value;
  return availableTabs.value[0]?.key ?? 'now';
});

function priorityClass(p: string): string {
  return `priority-${p.toLowerCase()}`;
}
function effortClass(e: string): string {
  return `effort-${e.toLowerCase()}`;
}
function effortLabel(e: string): string {
  return { low: '低成本', medium: '中等', high: '高投入' }[e] || e;
}
</script>

<template>
  <div class="diagnosis-card" :class="severityMeta.cls" data-test="diagnosis-card">
    <div class="sev-bar" :style="{ background: severityMeta.color }" />
    <div class="card-body">
      <!-- 标题行 -->
      <div class="card-header" @click="expanded = !expanded">
        <div class="title-group">
          <span class="metric-name">{{ diagnosis.metricNameZh }}</span>
          <span class="sev-badge" :class="severityMeta.cls">{{ severityMeta.label }}</span>
          <span class="status-text">{{ diagnosis.status }}</span>
          <span v-if="diagnosis.estimated" class="est-tag">估算</span>
        </div>
        <el-icon class="expand-icon" :class="{ open: expanded }">
          <ArrowDown />
        </el-icon>
      </div>

      <!-- 数字行 -->
      <div class="number-row">
        <div class="num-block">
          <span class="num-label">当前</span>
          <span class="num-value" data-test="actual-value">{{ actualDisplay }}</span>
        </div>
        <div v-if="benchmarkDisplay" class="num-block">
          <span class="num-label">行业中位</span>
          <span class="num-value benchmark">{{ benchmarkDisplay }}</span>
        </div>
        <div v-if="deltaDisplay" class="num-block">
          <span class="num-label">偏差</span>
          <span class="num-value delta">{{ deltaDisplay }}</span>
        </div>
      </div>

      <!-- 折叠内容 -->
      <div v-show="expanded" class="card-detail">
        <p class="description">{{ diagnosis.descriptionZh }}</p>

        <div v-if="diagnosis.subSectorNotes && diagnosis.subSectorNotes.length" class="notes">
          <div class="notes-title">行业提示</div>
          <ul>
            <li v-for="(note, i) in diagnosis.subSectorNotes" :key="i">{{ note }}</li>
          </ul>
        </div>

        <!-- RxAction 时间框 tabs -->
        <div v-if="availableTabs.length" class="rx-section">
          <div class="rx-tabs">
            <button
              v-for="t in availableTabs"
              :key="t.key"
              class="rx-tab"
              :class="{ active: effectiveTab === t.key }"
              data-test="rx-tab"
              @click="activeTab = t.key"
            >
              {{ t.label }} ({{ buckets[t.key].length }})
            </button>
          </div>
          <ul class="rx-list">
            <li v-for="action in buckets[effectiveTab]" :key="action.id" class="rx-item">
              <div class="rx-head">
                <span class="rx-title">{{ action.title }}</span>
                <span class="rx-badge" :class="priorityClass(action.priority)">{{ action.priority }}</span>
                <span class="rx-badge" :class="effortClass(action.effort)">{{ effortLabel(action.effort) }}</span>
              </div>
              <div class="rx-description">{{ action.description }}</div>
              <div class="rx-meta">
                <span class="meta-item">负责: {{ action.owner }}</span>
                <span class="meta-item">期限: {{ action.timeframe }}</span>
              </div>
              <div class="rx-impact">预期影响: {{ action.expectedImpact }}</div>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.diagnosis-card {
  display: flex;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}
.sev-bar {
  width: 5px;
  flex-shrink: 0;
}
.card-body {
  flex: 1;
  padding: 14px 16px;
  min-width: 0;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
}
.title-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.metric-name {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}
.sev-badge {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
}
.sev-badge.sev-critical { background: #fee2e2; color: #b91c1c; }
.sev-badge.sev-warning { background: #fef3c7; color: #92400e; }
.sev-badge.sev-info { background: #e0f2fe; color: #075985; }
.status-text {
  font-size: 13px;
  color: #909399;
}
.est-tag {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #f0f0f0;
  color: #909399;
}
.expand-icon {
  transition: transform 0.2s;
  color: #c0c4cc;
}
.expand-icon.open {
  transform: rotate(180deg);
}
.number-row {
  display: flex;
  gap: 24px;
  margin-top: 10px;
}
.num-block {
  display: flex;
  flex-direction: column;
}
.num-label {
  font-size: 12px;
  color: #909399;
}
.num-value {
  font-size: 18px;
  font-weight: 700;
  color: #303133;
}
.num-value.benchmark { color: #606266; font-weight: 500; }
.num-value.delta { color: #d97706; }
.card-detail {
  margin-top: 14px;
  border-top: 1px dashed #ebeef5;
  padding-top: 12px;
}
.description {
  font-size: 14px;
  color: #606266;
  line-height: 1.6;
  margin: 0 0 12px;
}
.notes {
  background: #fafafa;
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 12px;
}
.notes-title {
  font-size: 12px;
  font-weight: 600;
  color: #909399;
  margin-bottom: 4px;
}
.notes ul {
  margin: 0;
  padding-left: 18px;
}
.notes li {
  font-size: 13px;
  color: #606266;
  line-height: 1.5;
}
.rx-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}
.rx-tab {
  border: 1px solid #dcdfe6;
  background: #fff;
  border-radius: 16px;
  padding: 4px 14px;
  font-size: 13px;
  cursor: pointer;
  color: #606266;
}
.rx-tab.active {
  background: #409eff;
  color: #fff;
  border-color: #409eff;
}
.rx-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.rx-item {
  background: #f8fafc;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
}
.rx-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.rx-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}
.rx-badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
}
.priority-p0 { background: #fee2e2; color: #8b1a1a; border: 1px solid #b91c1c; }
.priority-p1 { background: #fef3c7; color: #92400e; border: 1px solid #d97706; }
.priority-p2 { background: #e0f2fe; color: #075985; border: 1px solid #0284c7; }
.effort-low { background: #ecfdf5; color: #047857; }
.effort-medium { background: #fffbeb; color: #b45309; }
.effort-high { background: #fef2f2; color: #b91c1c; }
.rx-description {
  font-size: 13px;
  color: #606266;
  line-height: 1.5;
  margin: 6px 0;
}
.rx-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #909399;
}
.rx-impact {
  font-size: 12px;
  color: #059669;
  margin-top: 4px;
}
</style>
