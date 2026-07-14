<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { LogisticsPlan, PlanStatus, ExceptionReason, ExceptionDisposition } from '@/api/logistics';
import { useLogisticsScheduling } from '../useLogisticsScheduling';
import LogisticsMap from '../components/LogisticsMap.vue';
import RouteCards from '../components/RouteCards.vue';
import ExecutionTracker from '../components/ExecutionTracker.vue';

const state = useLogisticsScheduling();
const currentPage = ref(1);
const pageSize = ref(20);

// 调度记录「查看排线」= 在本模块内打开只读详情抽屉(自己的地图 + 路线 + 状态), 不跳排线工作台
// (避免复用工作台导致一会 2 步一会 3 步来回撞)。
const detailOpen = ref(false);
const detailLoading = ref(false);
const detailPlan = ref<LogisticsPlan | null>(null);
// 抽屉内视图: 路线(只读地图+线路) / 执行(送达跟踪, 仅已确认计划)
const detailView = ref<'route' | 'execution'>('route');
const detailTotalKm = computed(() =>
  state.scheduleResult.value.trips.reduce((sum, t) => sum + (t.totalDistanceKm || 0), 0),
);
// 计划是否进入执行阶段(已确认/已导出才能标送达)
const detailConfirmed = computed(() =>
  detailPlan.value?.status === 'CONFIRMED' || detailPlan.value?.status === 'EXPORTED',
);

// 列表「执行进度」列展示(对齐 mockup rt-prog: 徽章 + 进度条 + X/Y 已送达)
function execMeta(row: LogisticsPlan): {
  text: string; badgeCls: string; done: number; total: number; delivered: number; pct: number; fill: string;
} | null {
  if (!row.executionStatus) return null;
  const total = row.totalOrders ?? 0;
  const delivered = row.deliveredCount ?? 0;
  const done = delivered + (row.exceptionCount ?? 0);
  const pct = total ? Math.round((done / total) * 100) : 0;
  if (row.executionStatus === 'COMPLETED') return { text: '已完成', badgeCls: 'b-done', done, total, delivered, pct, fill: '#12b76a' };
  if (row.executionStatus === 'IN_PROGRESS') return { text: '执行中', badgeCls: 'b-running', done, total, delivered, pct, fill: '#175cd3' };
  return { text: '待执行', badgeCls: 'b-pending', done, total, delivered, pct, fill: '#12b76a' };
}

const statusMeta: Record<PlanStatus, { label: string; type: 'info' | 'success' | 'warning' | 'danger' }> = {
  DRAFT: { label: '草稿', type: 'info' },
  NEEDS_ACTION: { label: '待处理', type: 'warning' },
  CONFIRMED: { label: '已确认', type: 'success' },
  EXPORTED: { label: '已导出', type: 'success' },
  CANCELLED: { label: '已取消', type: 'danger' },
};

const records = computed(() => state.planHistory.value?.content ?? []);
const total = computed(() => state.planHistory.value?.totalElements ?? 0);

// 按业务日期区间搜索调度记录(el-date-picker daterange, value-format YYYY-MM-DD)
const dateRange = ref<[string, string] | null>(null);
function dateParams(): { startDate?: string; endDate?: string } | undefined {
  return dateRange.value ? { startDate: dateRange.value[0], endDate: dateRange.value[1] } : undefined;
}

async function reload(page = 0): Promise<void> {
  currentPage.value = page + 1;
  await state.loadPlanHistory(page, pageSize.value, dateParams());
}

onMounted(() => {
  void reload(0);
});

async function handlePageChange(nextPage: number): Promise<void> {
  await reload(nextPage - 1);
}

// 日期区间变化 → 回到第一页重新查
async function onDateChange(): Promise<void> {
  await reload(0);
}

async function showDetails(plan: LogisticsPlan, view: 'route' | 'execution' = 'route'): Promise<void> {
  // 在调度记录模块内打开只读详情抽屉(自己的地图 + 路线 + 状态 + 执行跟踪), 不跳排线工作台。
  detailPlan.value = plan;
  // 执行视图仅对已确认/已导出计划有意义, 否则回落路线视图
  detailView.value = (view === 'execution' && (plan.status === 'CONFIRMED' || plan.status === 'EXPORTED')) ? 'execution' : 'route';
  detailOpen.value = true;
  detailLoading.value = true;
  await state.openPlan(plan.id); // 载入 stores + trips + orders 到共享 state, 供抽屉里的地图/路线/执行只读展示
  detailLoading.value = false;
}

// 执行跟踪: 逐门店标送达/异常/撤销(改后刷新 detailPlan 的进度)。
async function onDeliver(orderId: string): Promise<void> {
  if (await state.markStoreDelivered(orderId)) syncDetailProgress();
}
async function onException(p: { orderId: string; reason: ExceptionReason; disposition: ExceptionDisposition; note: string | null }): Promise<void> {
  if (await state.markStoreException(p.orderId, p.reason, p.disposition, p.note)) syncDetailProgress();
}
async function onResetDelivery(orderId: string): Promise<void> {
  if (await state.resetStoreDelivery(orderId)) syncDetailProgress();
}
// 一键全部送达: 逐单串行标记(每单独立, 后端无 trip 级乐观锁), 完成后刷新进度。
async function onDeliverAll(orderIds: string[]): Promise<void> {
  for (const id of orderIds) {
    const ok = await state.markStoreDelivered(id);
    if (!ok) break; // 出错停止(错误已由 planError 展示), 已标的保留
  }
  syncDetailProgress();
}
// 完成本次调度: 全部门店已处理后的收口(执行态已逐单持久化, 此处仅提示 + 收起抽屉)。
async function onComplete(): Promise<void> {
  const { ElMessage } = await import('element-plus');
  ElMessage({ message: '本次调度已完成，全部门店处理完毕', type: 'success' });
  detailOpen.value = false;
}
// 标记后同步刷新列表行的进度数字(从当前 orders 现算, 免整列表重拉)。
function syncDetailProgress(): void {
  if (!detailPlan.value) return;
  const os = state.orders.value;
  const delivered = os.filter((o) => o.deliveryStatus === 'DELIVERED').length;
  const exception = os.filter((o) => o.deliveryStatus === 'EXCEPTION').length;
  const total = os.length;
  const done = delivered + exception;
  detailPlan.value.deliveredCount = delivered;
  detailPlan.value.exceptionCount = exception;
  detailPlan.value.pendingCount = total - done;
  detailPlan.value.totalOrders = total;
  detailPlan.value.executionStatus = done === 0 ? 'NOT_STARTED' : (done < total ? 'IN_PROGRESS' : 'COMPLETED');
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
      <div class="rec-filters">
        <span class="rf-label">按日期</span>
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          clearable
          data-testid="records-date-range"
          @change="onDateChange"
        />
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
        <el-table-column label="执行进度" min-width="180">
          <template #default="{ row }">
            <div v-if="execMeta(row)" class="rt-prog">
              <span class="ex-badge" :class="execMeta(row)!.badgeCls">{{ execMeta(row)!.text }}</span>
              <div class="rt-bar"><div class="rt-fill" :style="{ width: execMeta(row)!.pct + '%', background: execMeta(row)!.fill }" /></div>
              <span class="rt-txt">
                {{ execMeta(row)!.delivered }} / {{ execMeta(row)!.total }} 已送达
                <span v-if="(row.exceptionCount ?? 0) > 0" class="rec-exc">· 异常 {{ row.exceptionCount }}</span>
              </span>
            </div>
            <span v-else class="rec-dash">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="280" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'CONFIRMED' || row.status === 'EXPORTED'"
              link type="primary" @click="showDetails(row, 'execution')"
            >执行处理</el-button>
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

        <!-- 路线 / 执行 视图切换(执行仅已确认计划) -->
        <el-radio-group v-if="detailConfirmed" v-model="detailView" size="small" class="rd-viewswitch">
          <el-radio-button value="route">配送路线</el-radio-button>
          <el-radio-button value="execution">执行跟踪</el-radio-button>
        </el-radio-group>

        <!-- 路线视图 -->
        <div v-show="detailView === 'route'" class="rd-main">
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

        <!-- 执行跟踪视图: 逐门店标送达/异常 -->
        <div v-if="detailConfirmed" v-show="detailView === 'execution'" class="rd-exec">
          <el-alert
            v-if="state.planError.value"
            :title="state.planError.value" type="error" :closable="false" show-icon style="margin-bottom:10px"
          />
          <ExecutionTracker
            :trips="state.scheduleResult.value.trips"
            :orders="state.orders.value"
            :busy-id="state.executionBusyId.value"
            @deliver="onDeliver"
            @deliver-all="onDeliverAll"
            @exception="onException"
            @reset="onResetDelivery"
            @complete="onComplete"
          />
        </div>

        <div v-if="!detailConfirmed" class="rd-note">💡 该计划尚未确认排班，确认后即可在此逐门店标记送达 / 异常。</div>
      </div>
    </el-drawer>

  </main>
</template>

<style scoped lang="scss">
.support-page { display: grid; gap: 20px; max-width: 1440px; min-height: 100%; padding: 24px; margin: 0 auto; background: #f8fafc; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.page-header h1 { margin: 0; color: #101828; font-size: 24px; }
.page-header p { margin: 8px 0 0; color: #667085; }
.rec-filters { display: flex; align-items: center; gap: 10px; }
.rf-label { color: #344054; font-size: 13px; font-weight: 650; }
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
.rd-viewswitch { align-self: flex-start; }
/* 执行视图放在浅灰面板上, 让内部白卡片浮起来(对齐 mockup frame 的 #F4F6F9 底) */
.rd-exec { display: flex; flex-direction: column; background: #F4F6F9; padding: 16px; border-radius: 12px; border: 1px solid #e8edf3; }
.rec-exc { color: #b42318; font-size: 11px; }
.rec-dash { color: #cbd5e1; }
/* 列表执行进度列(对齐 mockup rt-prog) */
.rt-prog { display: flex; flex-direction: column; gap: 4px; }
.ex-badge { align-self: flex-start; display: inline-flex; align-items: center; padding: 2px 9px; border-radius: 999px; font-size: 11.5px; font-weight: 700; }
.ex-badge.b-pending { color: #475467; background: #f2f4f7; }
.ex-badge.b-running { color: #175cd3; background: #eff8ff; }
.ex-badge.b-done { color: #027a48; background: #ecfdf3; }
.rt-bar { height: 6px; background: #eef2f7; border-radius: 999px; overflow: hidden; }
.rt-fill { height: 100%; border-radius: 999px; transition: width .3s ease; }
.rt-txt { font-size: 11px; color: #667085; font-variant-numeric: tabular-nums; }
@media (max-width: 980px) { .rd-main { flex-direction: column; height: auto; } .rd-map { height: 360px; } .rd-routes { flex-basis: auto; } .rd-routes :deep(.route-cards) { max-height: 400px; } }
@media (max-width: 720px) { .support-page { padding: 16px; } }
</style>
