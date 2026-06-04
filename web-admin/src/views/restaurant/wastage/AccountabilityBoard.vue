<template>
  <el-drawer
    :model-value="modelValue"
    title="损耗责任看板"
    size="640px"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @open="onOpen"
  >
    <div class="board-wrapper" v-loading="loading">
      <!-- 日期范围 -->
      <div class="board-toolbar">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          :clearable="false"
          style="width: 260px"
          @change="loadBoard"
        />
        <span class="board-summary" v-if="loaded">
          共 <b>{{ data.totalCount }}</b> 条已审批损耗 ·
          总成本 <b class="danger">¥{{ fmtMoney(data.totalCost) }}</b>
        </span>
      </div>

      <el-empty v-if="loaded && data.totalCount === 0" description="该时段暂无已审批损耗记录" />

      <template v-else-if="loaded">
        <!-- 按档口 -->
        <div class="board-section">
          <div class="board-section-title">按档口</div>
          <el-table :data="data.bySection" size="small" border stripe empty-text="无档口损耗数据">
            <el-table-column label="档口" min-width="100">
              <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.sectionName }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="count" label="记录数" width="90" align="right" />
            <el-table-column label="损耗成本" width="130" align="right">
              <template #default="{ row }"><span class="danger">¥{{ fmtMoney(row.totalCost) }}</span></template>
            </el-table-column>
            <el-table-column label="占比" min-width="160">
              <template #default="{ row }">
                <el-progress :percentage="pct(row.totalCost, data.totalCost)" :stroke-width="12" :show-text="true" />
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 按责任人 -->
        <div class="board-section">
          <div class="board-section-title">按责任人</div>
          <el-table :data="data.byOperator" size="small" border stripe empty-text="无责任人损耗数据">
            <el-table-column prop="operatorName" label="责任人" min-width="120" show-overflow-tooltip />
            <el-table-column prop="count" label="记录数" width="90" align="right" />
            <el-table-column label="损耗成本" width="130" align="right">
              <template #default="{ row }"><span class="danger">¥{{ fmtMoney(row.totalCost) }}</span></template>
            </el-table-column>
            <el-table-column label="占比" min-width="160">
              <template #default="{ row }">
                <el-progress :percentage="pct(row.totalCost, data.totalCost)" :stroke-width="12" :show-text="true" />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { getWastageAccountability } from '@/api/restaurant';
import type { WastageAccountability } from '@/types/restaurant';
import { handleCatchError } from '@/utils/errorToast';

const props = defineProps<{ modelValue: boolean; factoryId: string }>();
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>();

const loading = ref(false);
const loaded = ref(false);
const data = ref<WastageAccountability>({ startDate: '', endDate: '', totalCost: 0, totalCount: 0, byOperator: [], bySection: [] });

function defaultRange(): [string, string] {
  const now = new Date();
  const first = new Date(now.getFullYear(), now.getMonth(), 1);
  const iso = (d: Date) => d.toISOString().slice(0, 10);
  return [iso(first), iso(now)];
}
const dateRange = ref<[string, string]>(defaultRange());

function fmtMoney(v?: number | null): string {
  return (v ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
function pct(part?: number | null, total?: number | null): number {
  if (!total || total <= 0 || !part || part <= 0) return 0;
  return Math.round((part / total) * 1000) / 10;
}

async function loadBoard() {
  if (!props.factoryId) return;
  loading.value = true;
  try {
    const res = await getWastageAccountability(props.factoryId, {
      startDate: dateRange.value?.[0],
      endDate: dateRange.value?.[1],
    });
    if (res.success && res.data) {
      data.value = res.data as WastageAccountability;
      loaded.value = true;
    } else {
      ElMessage.error(res.message || '加载损耗责任看板失败');
    }
  } catch (e) {
    handleCatchError(e, '加载损耗责任看板失败，请检查网络');
  } finally {
    loading.value = false;
  }
}

function onOpen() {
  loaded.value = false;
  loadBoard();
}
</script>

<style scoped lang="scss">
.board-wrapper {
  padding: 4px 8px;
}
.board-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.board-summary {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.board-section {
  margin-bottom: 24px;
}
.board-section-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--el-text-color-primary);
}
.danger {
  color: var(--el-color-danger);
}
.muted-cell {
  color: var(--el-text-color-placeholder);
}
</style>
