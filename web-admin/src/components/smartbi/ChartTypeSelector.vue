<script setup lang="ts">
/**
 * ChartTypeSelector - 图表类型切换下拉组件
 * Allows switching a chart's type. Shows current type with dropdown to select alternatives.
 * Intelligently filters chart types based on data characteristics.
 */
import { computed } from 'vue';
import { Switch, Refresh } from '@element-plus/icons-vue';

interface Props {
  currentType: string;
  numericColumns?: string[];
  categoricalColumns?: string[];
  dateColumns?: string[];
  rowCount?: number;
  loading?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  numericColumns: () => [],
  categoricalColumns: () => [],
  dateColumns: () => [],
  rowCount: 0,
  loading: false,
});

const emit = defineEmits<{
  switchType: [chartType: string];
  refresh: [];
}>();

/** All available chart types with display info */
const ALL_CHART_TYPES: Array<{
  type: string;
  label: string;
  icon: string;
  requiresNumeric: number;
  requiresCategorical: boolean;
  requiresDate: boolean;
  minRows: number;
  maxCategories?: number;
}> = [
  { type: 'bar', label: '柱状图', icon: '', requiresNumeric: 1, requiresCategorical: true, requiresDate: false, minRows: 2 },
  { type: 'line', label: '折线图', icon: '', requiresNumeric: 1, requiresCategorical: false, requiresDate: false, minRows: 3 },
  { type: 'pie', label: '饼图', icon: '', requiresNumeric: 1, requiresCategorical: true, requiresDate: false, minRows: 2, maxCategories: 10 },
  { type: 'area', label: '面积图', icon: '', requiresNumeric: 1, requiresCategorical: false, requiresDate: false, minRows: 3 },
  { type: 'scatter', label: '散点图', icon: '', requiresNumeric: 2, requiresCategorical: false, requiresDate: false, minRows: 5 },
  { type: 'waterfall', label: '瀑布图', icon: '', requiresNumeric: 1, requiresCategorical: true, requiresDate: false, minRows: 3 },
  { type: 'radar', label: '雷达图', icon: '', requiresNumeric: 3, requiresCategorical: false, requiresDate: false, minRows: 1 },
  { type: 'funnel', label: '漏斗图', icon: '', requiresNumeric: 1, requiresCategorical: true, requiresDate: false, minRows: 3 },
  { type: 'doughnut', label: '环形图', icon: '', requiresNumeric: 1, requiresCategorical: true, requiresDate: false, minRows: 2, maxCategories: 10 },
  { type: 'treemap', label: '矩形树图', icon: '', requiresNumeric: 1, requiresCategorical: true, requiresDate: false, minRows: 3 },
  { type: 'bar_horizontal', label: '水平柱状图', icon: '', requiresNumeric: 1, requiresCategorical: true, requiresDate: false, minRows: 3 },
  { type: 'pareto', label: '帕累托图', icon: '', requiresNumeric: 1, requiresCategorical: true, requiresDate: false, minRows: 3 },
  { type: 'dual_axis', label: '双Y轴图', icon: '', requiresNumeric: 2, requiresCategorical: true, requiresDate: false, minRows: 3 },
  { type: 'combination', label: '组合图', icon: '', requiresNumeric: 2, requiresCategorical: true, requiresDate: false, minRows: 3 },
  { type: 'sunburst', label: '旭日图', icon: '', requiresNumeric: 1, requiresCategorical: true, requiresDate: false, minRows: 3 },
  { type: 'stacked_bar', label: '堆叠柱状图', icon: '', requiresNumeric: 2, requiresCategorical: true, requiresDate: false, minRows: 2 },
  { type: 'gauge', label: '仪表盘', icon: '', requiresNumeric: 1, requiresCategorical: false, requiresDate: false, minRows: 1 },
  { type: 'heatmap', label: '热力图', icon: '', requiresNumeric: 1, requiresCategorical: true, requiresDate: false, minRows: 3 },
];

/** Filter chart types by data compatibility */
const availableTypes = computed(() => {
  const numCount = props.numericColumns.length;
  const hasCat = props.categoricalColumns.length > 0 || props.dateColumns.length > 0;

  return ALL_CHART_TYPES.filter(ct => {
    if (ct.type === props.currentType) return false; // Don't show current type
    if (numCount < ct.requiresNumeric) return false;
    if (ct.requiresCategorical && !hasCat) return false;
    if (props.rowCount < ct.minRows) return false;
    return true;
  });
});

const currentLabel = computed(() => {
  const found = ALL_CHART_TYPES.find(ct => ct.type === props.currentType);
  return found ? `${found.icon} ${found.label}` : props.currentType;
});

function handleCommand(chartType: string) {
  emit('switchType', chartType);
}
</script>

<template>
  <div class="chart-type-selector">
    <el-dropdown trigger="click" @command="handleCommand" :disabled="loading">
      <el-button size="small" :loading="loading" class="selector-btn">
        <el-icon><Switch /></el-icon>
      </el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item disabled class="current-type-hint">
            当前: {{ currentLabel }}
          </el-dropdown-item>
          <el-dropdown-item divided v-for="ct in availableTypes" :key="ct.type" :command="ct.type">
            <span class="type-icon">{{ ct.icon }}</span>
            <span class="type-label">{{ ct.label }}</span>
          </el-dropdown-item>
          <el-dropdown-item v-if="availableTypes.length === 0" disabled>
            无可切换类型
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <el-tooltip content="换一种图表" placement="top">
      <el-button size="small" :icon="Refresh" circle class="refresh-btn" :loading="loading" @click="$emit('refresh')" />
    </el-tooltip>
  </div>
</template>

<style scoped>
.chart-type-selector {
  display: flex;
  gap: 4px;
  align-items: center;
}

.selector-btn {
  padding: 4px 8px;
}

.refresh-btn {
  padding: 4px;
}

.current-type-hint {
  font-size: 12px;
  color: #909399;
}

.type-icon {
  margin-right: 6px;
  font-size: 14px;
}

.type-label {
  font-size: 13px;
}
</style>
