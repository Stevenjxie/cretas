<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import type { LogisticsPlan, PlanStatus } from '@/api/logistics';
import { useLogisticsScheduling } from '../useLogisticsScheduling';

const state = useLogisticsScheduling();
const router = useRouter();
const currentPage = ref(1);
const pageSize = ref(20);

const statusMeta: Record<PlanStatus, { label: string; type: 'info' | 'success' | 'warning' | 'danger' }> = {
  DRAFT: { label: '草稿', type: 'info' },
  NEEDS_ACTION: { label: '待处理', type: 'warning' },
  CONFIRMED: { label: '已确认', type: 'success' },
  EXPORTED: { label: '已导出', type: 'success' },
  CANCELLED: { label: '已取消', type: 'danger' },
};

const records = computed(() => state.planHistory.value?.content ?? []);
const total = computed(() => state.planHistory.value?.totalElements ?? 0);

onMounted(() => {
  void state.loadPlanHistory(0, pageSize.value);
});

async function handlePageChange(nextPage: number): Promise<void> {
  currentPage.value = nextPage;
  await state.loadPlanHistory(nextPage - 1, pageSize.value);
}

async function showDetails(plan: LogisticsPlan): Promise<void> {
  // 跳到排线工作台并加载该计划，直接看地图 + 路线（workbench 支持 ?planId=&step= 深链）。
  // from=record: 标记「从调度记录查看」→ 工作台隔绝为 2 步(查看并确认路线 + 确认排班), 隐藏导入、退不回导入。
  await router.push({ path: '/scheduling/logistics/workbench', query: { planId: plan.id, step: 'map', from: 'record' } });
}

async function ensurePlanLoaded(plan: LogisticsPlan): Promise<boolean> {
  if (state.plan.value?.id === plan.id) return true;
  return state.openPlan(plan.id);
}

async function reExportCsv(plan: LogisticsPlan): Promise<void> {
  if (await ensurePlanLoaded(plan)) await state.exportCsv();
}

async function reExportXlsx(plan: LogisticsPlan): Promise<void> {
  if (await ensurePlanLoaded(plan)) await state.exportXlsx();
}
</script>

<template>
  <main class="support-page">
    <header class="page-header">
      <div>
        <h1>调度记录</h1>
        <p>查看每次排线计划的门店、车次和里程，可重新查看或导出。</p>
      </div>
    </header>

    <el-alert v-if="state.recordsError.value" type="error" :closable="false" :title="state.recordsError.value" show-icon />

    <el-card v-if="!records.length && !state.recordsLoading.value" shadow="never" class="empty-card">
      <p>暂无已生成的排线计划。</p>
      <router-link to="/scheduling/logistics/workbench">前往排线工作台</router-link>
    </el-card>

    <el-card v-else shadow="never">
      <el-table v-loading="state.recordsLoading.value" :data="records" stripe>
        <el-table-column prop="planDate" label="日期" min-width="120" />
        <el-table-column prop="planNumber" label="批次号" min-width="170" />
        <el-table-column prop="totalStores" label="门店数" min-width="90" />
        <el-table-column prop="totalTrips" label="车次数" min-width="90" />
        <el-table-column label="总里程" min-width="110">
          <template #default="{ row }">{{ Number(row.totalDistanceKm ?? 0).toFixed(1) }} km</template>
        </el-table-column>
        <el-table-column label="状态" min-width="100">
          <template #default="{ row }"><el-tag :type="statusMeta[row.status as PlanStatus].type" effect="plain">{{ statusMeta[row.status as PlanStatus].label }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" min-width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetails(row)">查看详情</el-button>
            <el-button link type="primary" @click="reExportCsv(row)">导出 CSV</el-button>
            <el-button link type="primary" @click="reExportXlsx(row)">导出 Excel</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

  </main>
</template>

<style scoped lang="scss">
.support-page { display: grid; gap: 20px; max-width: 1440px; min-height: 100%; padding: 24px; margin: 0 auto; background: #f8fafc; }
.page-header h1 { margin: 0; color: #101828; font-size: 24px; }
.page-header p { margin: 8px 0 0; color: #667085; }
.empty-card { display: grid; gap: 12px; padding: 20px; text-align: center; }
.pagination-wrapper { display: flex; justify-content: flex-end; margin-top: 12px; }
@media (max-width: 720px) { .support-page { padding: 16px; } }
</style>
