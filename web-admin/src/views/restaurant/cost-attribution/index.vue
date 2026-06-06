<template>
  <CanvasAwareWrapper module-code="restaurant">
    <div class="restaurant-page">
      <div class="page-head">
        <div>
          <h2>成本归因</h2>
          <p>把已过账的领料、损耗和盘亏成本按来源、档口、责任人、厨师拆开看。</p>
        </div>
        <div class="head-actions">
          <el-date-picker
            v-model="range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            unlink-panels
          />
          <el-button :icon="Refresh" :loading="loading" @click="loadSummary">刷新</el-button>
        </div>
      </div>

      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="只统计真实扣库后的成本"
        description="未过账的领料、损耗、盘点不会进入成本归因。没有权限查看金额时，金额字段显示为已脱敏，但笔数和数量仍可用于管理。"
      />

      <div class="metric-row">
        <div class="metric">
          <span>期间</span>
          <strong>{{ summary?.startDate || '-' }} 至 {{ summary?.endDate || '-' }}</strong>
        </div>
        <div class="metric">
          <span>总成本</span>
          <strong>{{ money(summary?.totalCost) }}</strong>
        </div>
        <div class="metric">
          <span>记录数</span>
          <strong>{{ summary?.totalCount || 0 }}</strong>
        </div>
      </div>

      <el-tabs v-model="activeTab" class="tabs">
        <el-tab-pane label="按来源" name="source">
          <bucket-table :rows="summary?.bySource || []" />
        </el-tab-pane>
        <el-tab-pane label="按部门" name="section">
          <bucket-table :rows="summary?.bySection || []" />
        </el-tab-pane>
        <el-tab-pane label="按档口" name="stall">
          <bucket-table :rows="summary?.byStall || []" />
        </el-tab-pane>
        <el-tab-pane label="按责任人" name="person">
          <bucket-table :rows="summary?.byPerson || []" />
        </el-tab-pane>
        <el-tab-pane label="按厨师" name="chef">
          <bucket-table :rows="summary?.byChef || []" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </CanvasAwareWrapper>
</template>

<script setup lang="ts">
import { defineComponent, h, onMounted, ref } from 'vue';
import { ElMessage, ElTable, ElTableColumn } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import { useFactoryId } from '@/composables/useFactoryId';
import { getErrorMessage } from '@/utils/errorToast';
import {
  getRestaurantCostAttributionSummary,
  type RestaurantCostAttributionBucket,
  type RestaurantCostAttributionSummary,
} from '@/api/restaurant/costAttribution';

const factoryId = useFactoryId();
const loading = ref(false);
const summary = ref<RestaurantCostAttributionSummary | null>(null);
const activeTab = ref('source');
const range = ref<[string, string]>(defaultRange());

const BucketTable = defineComponent({
  name: 'BucketTable',
  props: {
    rows: { type: Array<RestaurantCostAttributionBucket>, required: true },
  },
  setup(props) {
    return () => h(ElTable, { data: props.rows, stripe: true, style: 'width: 100%' }, () => [
      h(ElTableColumn, { prop: 'label', label: '归因对象', minWidth: 180 }),
      h(ElTableColumn, { prop: 'count', label: '记录数', width: 100, align: 'right' }),
      h(ElTableColumn, {
        label: '数量',
        width: 120,
        align: 'right',
        formatter: (row: RestaurantCostAttributionBucket) => formatQty(row.totalQuantity),
      }),
      h(ElTableColumn, {
        label: '成本',
        width: 140,
        align: 'right',
        formatter: (row: RestaurantCostAttributionBucket) => money(row.totalCost),
      }),
    ]);
  },
});

onMounted(loadSummary);

async function loadSummary() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const [startDate, endDate] = range.value;
    const resp = await getRestaurantCostAttributionSummary(factoryId.value, { startDate, endDate });
    summary.value = resp.data;
  } catch (error) {
    showStickyError(`${getErrorMessage(error, '成本归因加载失败')}。请确认当前账号有财务查看权限，或刷新后重试。`);
  } finally {
    loading.value = false;
  }
}

function showStickyError(message: string) {
  ElMessage({ message, type: 'error', duration: 0, showClose: true });
}

function money(value?: number | null) {
  if (value === null || value === undefined) return '已脱敏';
  return `¥${Number(value).toFixed(2)}`;
}

function formatQty(value?: number | null) {
  if (value === null || value === undefined) return '-';
  return Number(value).toFixed(2);
}

function defaultRange(): [string, string] {
  const end = new Date();
  const start = new Date(end.getFullYear(), end.getMonth(), 1);
  return [toDate(start), toDate(end)];
}

function toDate(date: Date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}
</script>

<style scoped>
@use '../restaurant-shared.scss';

.restaurant-page { padding: 16px; }
.page-head { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; margin-bottom: 12px; }
.page-head h2 { margin: 0 0 6px; font-size: 20px; }
.page-head p { margin: 0; color: var(--el-text-color-secondary); }
.head-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.metric-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin: 12px 0; }
.metric { border: 1px solid var(--el-border-color); border-radius: 6px; padding: 12px; background: var(--el-bg-color); }
.metric span { display: block; color: var(--el-text-color-secondary); font-size: 12px; margin-bottom: 6px; }
.metric strong { font-size: 18px; }
.tabs { margin-top: 8px; }

@media (max-width: 900px) {
  .page-head { flex-direction: column; }
  .metric-row { grid-template-columns: 1fr; }
}
</style>
