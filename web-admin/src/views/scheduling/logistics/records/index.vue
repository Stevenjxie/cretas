<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { LogisticsPlan, PlanStatus } from '@/api/logistics';
import { useLogisticsScheduling } from '../useLogisticsScheduling';
import LogisticsMap from '../components/LogisticsMap.vue';
import RouteCards from '../components/RouteCards.vue';

const state = useLogisticsScheduling();
const currentPage = ref(1);
const pageSize = ref(20);

// 调度记录「查看排线」= 在本模块内打开只读详情抽屉(自己的地图 + 路线 + 状态), 不跳排线工作台
// (避免复用工作台导致一会 2 步一会 3 步来回撞)。
const detailOpen = ref(false);
const detailLoading = ref(false);
const detailPlan = ref<LogisticsPlan | null>(null);
const detailTotalKm = computed(() =>
  state.scheduleResult.value.trips.reduce((sum, t) => sum + (t.totalDistanceKm || 0), 0),
);

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
  // 在调度记录模块内打开只读详情抽屉(自己的地图 + 路线 + 状态), 不跳排线工作台。
  detailPlan.value = plan;
  detailOpen.value = true;
  detailLoading.value = true;
  await state.openPlan(plan.id); // 载入 stores + trips 到共享 state, 供抽屉里的地图/路线只读展示
  detailLoading.value = false;
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

    <!-- 调度记录详情(只读)—— 自己的地图 + 路线 + 状态, 不跳排线工作台 -->
    <el-drawer
      v-model="detailOpen"
      :title="`调度记录 · ${detailPlan?.planNumber ?? ''}`"
      size="82%"
      direction="rtl"
      destroy-on-close
    >
      <div v-loading="detailLoading" class="rd-body">
        <div class="rd-status">
          <span class="rd-stat"><strong>{{ state.scheduleResult.value.trips.length }}</strong>车次</span>
          <span class="rd-dot">·</span>
          <span class="rd-stat"><strong>{{ detailPlan?.totalStores ?? 0 }}</strong>门店</span>
          <span class="rd-dot">·</span>
          <span class="rd-stat"><strong>{{ detailTotalKm.toFixed(1) }}</strong>km</span>
          <el-tag v-if="detailPlan" :type="statusMeta[detailPlan.status as PlanStatus].type" effect="plain" style="margin-left:8px">{{ statusMeta[detailPlan.status as PlanStatus].label }}</el-tag>
          <span class="rd-date">{{ detailPlan?.planDate }}</span>
        </div>
        <div class="rd-main">
          <div class="rd-map">
            <LogisticsMap
              :stores="state.stores.value"
              :trips="state.scheduleResult.value.trips"
              :selected-trip-id="state.selectedTripId.value"
              :selected-store-id="state.selectedStoreId.value"
              @select-trip="state.selectTrip"
              @select-store="state.selectStore"
            />
          </div>
          <div class="rd-routes">
            <div class="rd-routes-title">配送线路 <span>{{ state.scheduleResult.value.trips.length }} 车次</span></div>
            <RouteCards
              readonly
              :stores="state.stores.value"
              :trips="state.scheduleResult.value.trips"
              :selected-trip-id="state.selectedTripId.value"
              :selected-store-id="state.selectedStoreId.value"
              @select-trip="state.selectTrip"
              @select-store="state.selectStore"
            />
          </div>
        </div>
        <div class="rd-note">💡 送达跟踪 / 异常处理(执行处理)即将上线 —— 届时可在此逐单标记送达情况、改派或标异常。</div>
      </div>
    </el-drawer>

  </main>
</template>

<style scoped lang="scss">
.support-page { display: grid; gap: 20px; max-width: 1440px; min-height: 100%; padding: 24px; margin: 0 auto; background: #f8fafc; }
.page-header h1 { margin: 0; color: #101828; font-size: 24px; }
.page-header p { margin: 8px 0 0; color: #667085; }
.empty-card { display: grid; gap: 12px; padding: 20px; text-align: center; }
.pagination-wrapper { display: flex; justify-content: flex-end; margin-top: 12px; }
/* 调度记录详情抽屉(只读地图 + 路线 + 状态) */
.rd-body { display: flex; flex-direction: column; gap: 12px; }
.rd-status { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 10px 14px; background: linear-gradient(180deg,#fff,#f8fafc); border: 1px solid #e2e8f0; border-radius: 10px; font-size: 13px; color: #667085; }
.rd-stat strong { color: #0f172a; font-size: 18px; margin-right: 3px; font-variant-numeric: tabular-nums; } .rd-dot { color: #cbd5e1; }
.rd-date { margin-left: auto; color: #98a2b3; font-size: 12.5px; }
.rd-main { display: flex; gap: 12px; height: 62vh; min-height: 380px; }
.rd-map { flex: 1 1 auto; min-width: 0; position: relative; border-radius: 10px; overflow: hidden; border: 1px solid #e2e8f0; }
.rd-map :deep(.map-stage) { height: 100% !important; aspect-ratio: auto !important; }
.rd-routes { flex: 0 0 380px; display: flex; flex-direction: column; overflow: hidden; }
.rd-routes-title { flex: 0 0 auto; margin-bottom: 8px; color: #101828; font-size: 14px; font-weight: 700; } .rd-routes-title span { color: #98a2b3; font-size: 12px; font-weight: 400; margin-left: 6px; }
.rd-routes :deep(.route-cards) { grid-template-columns: 1fr; overflow-y: auto; flex: 1 1 auto; padding-right: 6px; align-content: start; }
.rd-note { padding: 10px 14px; color: #175cd3; font-size: 12.5px; background: #eff8ff; border: 1px solid #b2ddff; border-radius: 8px; }
@media (max-width: 980px) { .rd-main { flex-direction: column; height: auto; } .rd-map { height: 360px; } .rd-routes { flex-basis: auto; } .rd-routes :deep(.route-cards) { max-height: 400px; } }
@media (max-width: 720px) { .support-page { padding: 16px; } }
</style>
