<template>
  <el-card
    class="indicator-card"
    :class="[
      `card-${alertLevelClass}`,
      { 'card-clickable': clickable },
      { 'card-loading': loading }
    ]"
    shadow="hover"
    @click="onClick"
  >
    <div class="card-header">
      <div class="title-block">
        <span class="indicator-name">{{ indicator.name }}</span>
        <el-tag v-if="indicator.category" size="small" class="category-tag">
          {{ categoryText }}
        </el-tag>
        <!-- Sprint 11 BI deep-audit Finding-2 P1: mark F999_MOCK mirror codes -->
        <el-tag v-if="isMirrored" type="warning" size="small" class="source-tag" effect="plain">
          示例
        </el-tag>
        <el-tag v-else-if="isUnconfigured" type="info" size="small" class="source-tag" effect="plain">
          待配置
        </el-tag>
      </div>
      <el-tag
        v-if="indicator.alertLevel"
        :type="alertTagType"
        size="small"
        effect="dark"
      >
        {{ alertText }}
      </el-tag>
    </div>

    <div class="card-body">
      <div class="value-display">
        <span class="value-num" :class="valueColorClass">
          {{ formattedValue }}
        </span>
        <span v-if="indicator.unit" class="value-unit">{{ indicator.unit }}</span>
      </div>

      <div v-if="indicator.description" class="indicator-desc">
        {{ truncate(indicator.description, 60) }}
      </div>
    </div>

    <div v-if="indicator.lastComputedAt" class="card-footer">
      <el-icon><Clock /></el-icon>
      <span>{{ relativeTime(indicator.lastComputedAt) }}</span>
      <el-tag
        v-if="indicator.computeStrategy"
        size="small"
        type="info"
        effect="plain"
      >
        {{ strategyText }}
      </el-tag>
    </div>
    <div v-else class="card-footer card-footer-empty">
      <el-icon><InfoFilled /></el-icon>
      <span>未计算</span>
    </div>

    <!-- Sprint 12 #265: next-action button (dead-end → 跳 actionable 页, fool-proof Rule 5) -->
    <div v-if="actionHint && actionHint.route" class="card-action">
      <el-button
        text
        type="primary"
        size="small"
        @click.stop="goAction"
      >
        {{ actionHint.label }}
        <el-icon class="action-arrow"><ArrowRight /></el-icon>
      </el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
/**
 * IndicatorValueCard — 单指标摘要卡片.
 *
 * Dashboard / Tree / List 共用. 点击 emit 'click' 让父组件打开 detail drawer.
 */
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { Clock, InfoFilled, ArrowRight } from '@element-plus/icons-vue';

/** Sprint 12 #265: KPI 卡片 next-action 提示 (后端 config.actionHint extract). */
interface ActionHint {
  label: string;
  route?: string | null;
}

interface CardData {
  code: string;
  name: string;
  description?: string;
  category?: string;
  unit?: string;
  lastValue?: number | null;
  lastComputedAt?: string | null;
  computeStrategy?: string;
  alertLevel?: 'GREEN' | 'YELLOW' | 'RED' | null;
  actionHint?: ActionHint | null;
}

interface Props {
  indicator: CardData;
  clickable?: boolean;
  loading?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  clickable: true,
  loading: false,
});

// Sprint 12 Phase A: V_23_11 mirror is being deleted via V20260825_01 migration.
// After deploy, the indicator codes that used to mirror F999_MOCK no longer exist,
// so isMirrored is always false. Kept wired (empty array) so isUnconfigured logic
// remains stable while Phase B installs real-business indicators.
const MIRRORED_CODES: string[] = [];
const isMirrored = computed(() => MIRRORED_CODES.includes(props.indicator.code));
const isUnconfigured = computed(() =>
  !isMirrored.value &&
  (props.indicator.lastValue === null || props.indicator.lastValue === undefined)
);

const emit = defineEmits<{
  (e: 'click', code: string): void;
}>();

const router = useRouter();

function onClick() {
  if (props.clickable) {
    emit('click', props.indicator.code);
  }
}

// Sprint 12 #265: next-action button — dead-end → 跳到 actionable 页面 (fool-proof Rule 5).
// @click.stop 防止冒泡触发卡片的 detail drawer.
const actionHint = computed(() => props.indicator.actionHint || null);

function goAction() {
  const route = actionHint.value?.route;
  if (route) router.push(route);
}

const hasValue = computed(() =>
  props.indicator.lastValue !== null && props.indicator.lastValue !== undefined
);

const formattedValue = computed(() => {
  if (!hasValue.value) return '—';
  const v = props.indicator.lastValue as number;
  if (Math.abs(v) < 1000) {
    return Number.isInteger(v) ? v.toString() : v.toFixed(2);
  }
  return v.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
});

const alertLevelClass = computed(() => {
  if (!props.indicator.alertLevel) return 'neutral';
  return props.indicator.alertLevel.toLowerCase();
});

const alertTagType = computed<'success' | 'warning' | 'danger' | 'info'>(() => {
  switch (props.indicator.alertLevel) {
    case 'GREEN': return 'success';
    case 'YELLOW': return 'warning';
    case 'RED': return 'danger';
    default: return 'info';
  }
});

const alertText = computed(() => {
  switch (props.indicator.alertLevel) {
    case 'GREEN': return '正常';
    case 'YELLOW': return '关注';
    case 'RED': return '告警';
    default: return '';
  }
});

const valueColorClass = computed(() => `value-${alertLevelClass.value}`);

const categoryText = computed(() => {
  const map: Record<string, string> = {
    FACTORY: '工厂',
    RESTAURANT: '餐饮',
    QUALITY: '质量',
  };
  return map[props.indicator.category || ''] || props.indicator.category || '';
});

const strategyText = computed(() => {
  const map: Record<string, string> = {
    REALTIME: '实时',
    CACHED: '缓存',
    PRECOMPUTED: '预算',
  };
  return map[props.indicator.computeStrategy || ''] || props.indicator.computeStrategy || '';
});

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) + '…' : s;
}

function relativeTime(iso: string): string {
  try {
    const then = new Date(iso).getTime();
    const now = Date.now();
    const diffSec = Math.floor((now - then) / 1000);
    if (diffSec < 60) return `${diffSec}秒前`;
    if (diffSec < 3600) return `${Math.floor(diffSec / 60)}分钟前`;
    if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}小时前`;
    const days = Math.floor(diffSec / 86400);
    if (days < 30) return `${days}天前`;
    return new Date(iso).toLocaleDateString('zh-CN');
  } catch {
    return iso;
  }
}
</script>

<style scoped>
.indicator-card {
  height: 100%;
  border-left: 4px solid transparent;
  transition: transform 0.15s, box-shadow 0.15s;
}

.indicator-card.card-green { border-left-color: var(--el-color-success); }
.indicator-card.card-yellow { border-left-color: var(--el-color-warning); }
.indicator-card.card-red { border-left-color: var(--el-color-danger); }
.indicator-card.card-neutral { border-left-color: var(--el-border-color); }

.indicator-card.card-clickable {
  cursor: pointer;
}

.indicator-card.card-clickable:hover {
  transform: translateY(-2px);
}

.indicator-card.card-loading {
  opacity: 0.6;
  pointer-events: none;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.title-block {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
}

.indicator-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.category-tag {
  flex-shrink: 0;
}

/* Sprint 11 BI deep-audit Finding-2 P1: source-tag styles */
.source-tag {
  flex-shrink: 0;
  font-size: 10px !important;
  padding: 0 4px !important;
}

.card-body {
  padding: 4px 0;
}

.value-display {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 6px;
}

.value-num {
  font-size: 32px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.value-num.value-green { color: var(--el-color-success); }
.value-num.value-yellow { color: var(--el-color-warning); }
.value-num.value-red { color: var(--el-color-danger); }
.value-num.value-neutral { color: var(--el-text-color-primary); }

.value-unit {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}

.indicator-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  min-height: 16px;
}

.card-footer {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
  margin-top: 8px;
}

.card-footer-empty {
  color: var(--el-text-color-placeholder);
}

/* Sprint 12 #265: next-action button (dead-end → 跳 actionable 页) */
.card-action {
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px dashed var(--el-border-color-lighter);
  text-align: right;
}
.action-arrow {
  margin-left: 2px;
  font-size: 12px;
}
</style>
