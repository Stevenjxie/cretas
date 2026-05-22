<template>
  <div class="threshold-gauge">
    <!-- 数值 + 告警级别 -->
    <div class="gauge-header">
      <div class="value-block">
        <span class="value-number" :class="alertClass">
          {{ formattedValue }}
        </span>
        <span v-if="unit" class="value-unit">{{ unit }}</span>
      </div>
      <el-tag v-if="alertLevel" :type="alertTagType" effect="dark" size="small">
        {{ alertText }}
      </el-tag>
    </div>

    <!-- 阈值色带 -->
    <div v-if="bands.length > 0" class="gauge-bar-wrapper">
      <div class="gauge-bar">
        <div
          v-for="(band, idx) in bands"
          :key="idx"
          class="band-segment"
          :class="`band-${band.level.toLowerCase()}`"
          :style="{ left: band.leftPct + '%', width: band.widthPct + '%' }"
          :title="`${band.label}: ${band.operator} ${band.thresholdValue}`"
        />
        <!-- 当前值指针 -->
        <div
          v-if="hasValue && pointerPosition !== null"
          class="value-pointer"
          :style="{ left: pointerPosition + '%' }"
        />
      </div>

      <!-- 阈值刻度文字 -->
      <div class="gauge-scale">
        <span class="scale-min">{{ formatScale(scaleMin) }}</span>
        <span class="scale-max">{{ formatScale(scaleMax) }}</span>
      </div>
    </div>

    <!-- 阈值规则文字描述 -->
    <div v-if="thresholds.length > 0" class="threshold-rules">
      <div
        v-for="t in thresholds"
        :key="t.id"
        class="rule-item"
        :class="`rule-${t.alertLevel.toLowerCase()}`"
      >
        <span class="rule-dot" :class="`dot-${t.alertLevel.toLowerCase()}`" />
        <span class="rule-label">{{ alertLevelText(t.alertLevel) }}</span>
        <span class="rule-condition">
          {{ operatorText(t.operator) }} {{ t.thresholdValue }}
          <template v-if="t.operator === 'BETWEEN' && t.thresholdValueUpper !== null && t.thresholdValueUpper !== undefined">
            ~ {{ t.thresholdValueUpper }}
          </template>
        </span>
      </div>
    </div>

    <div v-else-if="!loading" class="no-thresholds">
      <el-empty :image-size="40" description="暂未配置阈值" />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * ThresholdGauge — 阈值色带 + 当前值指针 + 规则文字.
 *
 * 输入:
 *  - currentValue: 当前指标值 (number | null, null 时不显示指针)
 *  - thresholds: GREEN/YELLOW/RED 三色阈值配置
 *  - alertLevel: 当前命中告警级别 (GREEN/YELLOW/RED/null)
 *
 * Phase 1 简化: 用 operator 推断刻度范围.
 *  - 所有 thresholdValue 取 min/max ± 20% padding 作为 scale.
 *  - BETWEEN 则用 thresholdValue/thresholdValueUpper 区间.
 */
import { computed } from 'vue';
import type { ThresholdResponse, AlertLevel } from '@/api/indicator';

interface Props {
  currentValue?: number | null;
  unit?: string;
  thresholds: ThresholdResponse[];
  alertLevel?: AlertLevel;
  loading?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  currentValue: null,
  unit: '',
  alertLevel: null,
  loading: false,
});

const hasValue = computed(() => props.currentValue !== null && props.currentValue !== undefined);

const formattedValue = computed(() => {
  if (!hasValue.value) return '—';
  const v = props.currentValue as number;
  // 0-100 类百分比保留 2 位; 大数加千分位
  if (Math.abs(v) < 1000) {
    return Number.isInteger(v) ? v.toString() : v.toFixed(2);
  }
  return v.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
});

const alertClass = computed(() => {
  if (!props.alertLevel) return 'alert-none';
  return `alert-${props.alertLevel.toLowerCase()}`;
});

const alertTagType = computed<'success' | 'warning' | 'danger' | 'info'>(() => {
  switch (props.alertLevel) {
    case 'GREEN': return 'success';
    case 'YELLOW': return 'warning';
    case 'RED': return 'danger';
    default: return 'info';
  }
});

const alertText = computed(() => {
  switch (props.alertLevel) {
    case 'GREEN': return '正常';
    case 'YELLOW': return '关注';
    case 'RED': return '告警';
    default: return '未评估';
  }
});

function alertLevelText(level: 'GREEN' | 'YELLOW' | 'RED'): string {
  return level === 'GREEN' ? '正常' : level === 'YELLOW' ? '关注' : '告警';
}

function operatorText(op: string): string {
  const map: Record<string, string> = {
    GT: '>',
    GTE: '≥',
    LT: '<',
    LTE: '≤',
    EQ: '=',
    BETWEEN: '∈',
  };
  return map[op] || op;
}

/**
 * 推断刻度区间. 取所有 threshold value (含 upper) min/max, 加 20% 缓冲.
 * 若全部阈值是单点 (e.g. GREEN GTE 95 / RED LT 90), 区间可能很小, 强制最小宽度 10.
 */
const scaleMin = computed((): number => {
  if (props.thresholds.length === 0) return 0;
  const values: number[] = [];
  props.thresholds.forEach(t => {
    values.push(t.thresholdValue);
    if (t.thresholdValueUpper !== null && t.thresholdValueUpper !== undefined) {
      values.push(t.thresholdValueUpper);
    }
  });
  if (hasValue.value) values.push(props.currentValue as number);
  const minVal = Math.min(...values);
  const maxVal = Math.max(...values);
  const range = Math.max(maxVal - minVal, 10);
  return Math.floor(minVal - range * 0.1);
});

const scaleMax = computed((): number => {
  if (props.thresholds.length === 0) return 100;
  const values: number[] = [];
  props.thresholds.forEach(t => {
    values.push(t.thresholdValue);
    if (t.thresholdValueUpper !== null && t.thresholdValueUpper !== undefined) {
      values.push(t.thresholdValueUpper);
    }
  });
  if (hasValue.value) values.push(props.currentValue as number);
  const minVal = Math.min(...values);
  const maxVal = Math.max(...values);
  const range = Math.max(maxVal - minVal, 10);
  return Math.ceil(maxVal + range * 0.1);
});

const scaleRange = computed(() => Math.max(scaleMax.value - scaleMin.value, 1));

function formatScale(v: number): string {
  if (Math.abs(v) >= 10000) {
    return (v / 10000).toFixed(1) + '万';
  }
  return v.toString();
}

interface Band {
  level: 'GREEN' | 'YELLOW' | 'RED';
  label: string;
  operator: string;
  thresholdValue: number;
  thresholdValueUpper?: number | null;
  leftPct: number;
  widthPct: number;
}

/**
 * 阈值色带计算.
 *
 * 简化策略: 每条阈值按其 operator 涵盖的数值范围渲染一条彩色 segment.
 *   - GTE/GT: 从 thresholdValue 到 scaleMax
 *   - LTE/LT: 从 scaleMin 到 thresholdValue
 *   - BETWEEN: 从 thresholdValue 到 thresholdValueUpper
 *   - EQ: 单点 ±2% 宽度
 *
 * 由 z-order (RED 最上层 → YELLOW → GREEN) 表达"越严格的告警越优先显示".
 */
const bands = computed((): Band[] => {
  const result: Band[] = [];

  // RED/YELLOW/GREEN 倒序 → CSS z-index 自然顺序: RED on top.
  const order: ('GREEN' | 'YELLOW' | 'RED')[] = ['GREEN', 'YELLOW', 'RED'];
  order.forEach(level => {
    const matched = props.thresholds.filter(t => t.alertLevel === level);
    matched.forEach(t => {
      let from = scaleMin.value;
      let to = scaleMax.value;
      switch (t.operator) {
        case 'GT':
        case 'GTE':
          from = t.thresholdValue;
          to = scaleMax.value;
          break;
        case 'LT':
        case 'LTE':
          from = scaleMin.value;
          to = t.thresholdValue;
          break;
        case 'BETWEEN':
          from = t.thresholdValue;
          to = (t.thresholdValueUpper ?? t.thresholdValue);
          break;
        case 'EQ':
          // 单点 ±2% 区间
          {
            const half = scaleRange.value * 0.02;
            from = t.thresholdValue - half;
            to = t.thresholdValue + half;
          }
          break;
      }
      const leftPct = Math.max(0, ((from - scaleMin.value) / scaleRange.value) * 100);
      const widthPct = Math.min(100, ((to - from) / scaleRange.value) * 100);
      result.push({
        level: t.alertLevel,
        label: alertLevelText(t.alertLevel),
        operator: t.operator,
        thresholdValue: t.thresholdValue,
        thresholdValueUpper: t.thresholdValueUpper,
        leftPct,
        widthPct: Math.max(widthPct, 1),
      });
    });
  });

  return result;
});

/**
 * 当前值指针 (百分比位置). 超出刻度时 clamp 到 0/100, null 隐藏.
 */
const pointerPosition = computed((): number | null => {
  if (!hasValue.value) return null;
  const v = props.currentValue as number;
  const pct = ((v - scaleMin.value) / scaleRange.value) * 100;
  return Math.max(0, Math.min(100, pct));
});
</script>

<style scoped>
.threshold-gauge {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  background: var(--el-fill-color-blank);
  border-radius: 6px;
}

.gauge-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.value-block {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.value-number {
  font-size: 28px;
  font-weight: 600;
  line-height: 1;
}

.value-number.alert-green { color: var(--el-color-success); }
.value-number.alert-yellow { color: var(--el-color-warning); }
.value-number.alert-red { color: var(--el-color-danger); }
.value-number.alert-none { color: var(--el-text-color-primary); }

.value-unit {
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.gauge-bar-wrapper {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.gauge-bar {
  position: relative;
  height: 16px;
  background: var(--el-fill-color-darker);
  border-radius: 8px;
  overflow: hidden;
}

.band-segment {
  position: absolute;
  top: 0;
  height: 100%;
  opacity: 0.85;
}

.band-segment.band-green { background: var(--el-color-success-light-5); z-index: 1; }
.band-segment.band-yellow { background: var(--el-color-warning-light-5); z-index: 2; }
.band-segment.band-red { background: var(--el-color-danger-light-5); z-index: 3; }

.value-pointer {
  position: absolute;
  top: -3px;
  width: 4px;
  height: 22px;
  background: var(--el-text-color-primary);
  border-radius: 2px;
  transform: translateX(-50%);
  z-index: 10;
  box-shadow: 0 0 4px rgba(0, 0, 0, 0.3);
}

.gauge-scale {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.threshold-rules {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 4px;
  border-top: 1px dashed var(--el-border-color-lighter);
}

.rule-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.rule-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot-green { background: var(--el-color-success); }
.dot-yellow { background: var(--el-color-warning); }
.dot-red { background: var(--el-color-danger); }

.rule-label {
  font-weight: 600;
  min-width: 36px;
  color: var(--el-text-color-primary);
}

.rule-condition {
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.no-thresholds {
  padding: 8px 0;
}
</style>
