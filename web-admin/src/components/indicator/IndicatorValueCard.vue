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
  </el-card>
</template>

<script setup lang="ts">
/**
 * IndicatorValueCard — 单指标摘要卡片.
 *
 * Dashboard / Tree / List 共用. 点击 emit 'click' 让父组件打开 detail drawer.
 */
import { computed } from 'vue';
import { Clock, InfoFilled } from '@element-plus/icons-vue';

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

const emit = defineEmits<{
  (e: 'click', code: string): void;
}>();

function onClick() {
  if (props.clickable) {
    emit('click', props.indicator.code);
  }
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
</style>
