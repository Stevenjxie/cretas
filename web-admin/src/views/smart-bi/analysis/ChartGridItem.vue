<template>
  <div class="chart-grid-item" :class="[sizeClass, { 'chart-empty': isEmpty }]">
    <template v-if="isEmpty">
      <div class="chart-title-row">
        <div class="chart-title" style="margin-bottom:0">{{ titleLabel }}</div>
        <div class="chart-controls">
          <el-button size="small" @click="$emit('refresh')">
            <el-icon><Refresh /></el-icon> 重新生成
          </el-button>
        </div>
      </div>
      <div class="chart-no-data">
        <el-empty description="暂无数据" :image-size="60" />
      </div>
    </template>
    <template v-else>
      <div class="chart-title-row">
        <div class="chart-title" style="margin-bottom:0">{{ titleLabel }}</div>
        <div class="chart-controls">
          <ChartTypeSelector
            :current-type="chart.chartType || 'bar'"
            :numeric-columns="numericColumns"
            :categorical-columns="categoricalColumns"
            :date-columns="dateColumns"
            :row-count="rowCount"
            :loading="switching"
            @switch-type="(type: string) => $emit('switch-type', type)"
            @refresh="$emit('refresh')"
          />
          <ChartConfigPanel
            :columns="columns"
            :current-config="{ chartType: chart.chartType || 'bar', xField: chart.xField, yFields: yFields }"
            :loading="switching"
            @apply="(config: ChartConfigPayload) => $emit('apply-config', config)"
          />
          <el-dropdown class="chart-export-btn" trigger="click" @command="(cmd: 'png' | 'svg') => $emit('export', cmd)">
            <el-button :icon="Download" circle size="small" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="png">导出 PNG</el-dropdown-item>
                <el-dropdown-item command="svg">导出 SVG</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
      <!-- Chart container — ECharts mounts into this DOM id from script-side composable callbacks. -->
      <div :id="containerId" class="chart-container"></div>
      <div v-if="miniInsight" class="chart-mini-insight">
 <span class="mini-insight-icon"></span>
        <span class="mini-insight-text">{{ miniInsight }}</span>
      </div>
      <div v-if="totalItems" class="chart-view-more">
        <el-button type="primary" link size="small" @click="$emit('view-more')">
          查看更多 (共 {{ totalItems }} 项，当前显示 {{ displayedCount }} 项)
        </el-button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { defineAsyncComponent, computed } from 'vue';
import { Refresh, Download } from '@element-plus/icons-vue';

const ChartTypeSelector = defineAsyncComponent(() => import('@/components/smartbi/ChartTypeSelector.vue'));
const ChartConfigPanel = defineAsyncComponent(() => import('@/components/smartbi/ChartConfigPanel.vue'));

interface SheetColumn {
  name: string;
  type: 'numeric' | 'categorical' | 'date';
}

interface ChartItem {
  title?: string;
  chartType?: string;
  xField?: string;
  config: Record<string, unknown>;
  totalItems?: number;
}

interface ChartConfigPayload {
  xField: string;
  yFields: string[];
  seriesField?: string;
  aggregation?: string;
}

const props = defineProps<{
  chart: ChartItem;
  containerId: string;
  isEmpty: boolean;
  sizeClass: string;
  titleLabel: string;
  columns: SheetColumn[];
  yFields: string[];
  rowCount: number;
  switching: boolean;
  miniInsight: string;
  displayedCount: number;
}>();

defineEmits<{
  (e: 'switch-type', type: string): void;
  (e: 'apply-config', config: ChartConfigPayload): void;
  (e: 'refresh'): void;
  (e: 'export', cmd: 'png' | 'svg'): void;
  (e: 'view-more'): void;
}>();

const totalItems = computed(() => props.chart.totalItems);
const numericColumns = computed(() => props.columns.filter(c => c.type === 'numeric').map(c => c.name));
const categoricalColumns = computed(() => props.columns.filter(c => c.type === 'categorical').map(c => c.name));
const dateColumns = computed(() => props.columns.filter(c => c.type === 'date').map(c => c.name));
</script>
