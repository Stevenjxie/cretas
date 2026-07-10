<script setup lang="ts">
import { computed, ref } from 'vue';
import { MOCK_RECORDS } from '../mockData';
import { useLogisticsDemoState } from '../useLogisticsDemoState';

const state = useLogisticsDemoState();
const selectedRecordId = ref<string | null>(null);
const exportReadyRecordId = ref<string | null>(null);

const records = computed(() => {
  const confirmedTrips = state.scheduleResult.value.trips.filter((trip) => trip.status === 'confirmed');
  const hasConfirmedSchedule = confirmedTrips.length > 0
    && confirmedTrips.length === state.scheduleResult.value.trips.length
    && state.scheduleResult.value.unassignedStoreIds.length === 0;

  if (!hasConfirmedSchedule) return MOCK_RECORDS;

  return [
    {
      id: 'CURRENT',
      date: '当前排程',
      batchNo: '当前排程',
      storeCount: new Set(confirmedTrips.flatMap((trip) => trip.storeIds)).size,
      tripCount: confirmedTrips.length,
      totalDistanceKm: confirmedTrips.reduce((total, trip) => total + trip.totalDistanceKm, 0),
      status: '已确认',
    },
    ...MOCK_RECORDS,
  ];
});

const selectedRecord = computed(() => records.value.find((record) => record.id === selectedRecordId.value) ?? null);

function showDetails(recordId: string): void {
  selectedRecordId.value = recordId;
}

function prepareExport(recordId: string): void {
  exportReadyRecordId.value = recordId;
}
</script>

<template>
  <main class="support-page">
    <header class="page-header">
      <div>
        <h1>调度记录</h1>
        <p>查看每次已确认排程的门店、车次和里程。</p>
      </div>
    </header>

    <el-card shadow="never">
      <el-table :data="records" stripe>
        <el-table-column prop="date" label="日期" min-width="120" />
        <el-table-column prop="batchNo" label="批次号" min-width="170" />
        <el-table-column prop="storeCount" label="门店数" min-width="90" />
        <el-table-column prop="tripCount" label="车次数" min-width="90" />
        <el-table-column label="总里程" min-width="110">
          <template #default="{ row }">{{ Number(row.totalDistanceKm).toFixed(1) }} km</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" min-width="100">
          <template #default="{ row }"><el-tag type="success" effect="plain">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" min-width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetails(row.id)">查看详情</el-button>
            <el-button link type="primary" @click="prepareExport(row.id)">再次导出</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-alert
      v-if="selectedRecord"
      :title="`${selectedRecord.batchNo}：${selectedRecord.storeCount} 家门店对应 ${selectedRecord.tripCount} 个车次`"
      type="info"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="exportReadyRecordId"
      title="已准备该批次的导出内容。"
      type="success"
      :closable="false"
      show-icon
    />
  </main>
</template>

<style scoped lang="scss">
.support-page { display: grid; gap: 20px; max-width: 1440px; min-height: 100%; padding: 24px; margin: 0 auto; background: #f8fafc; }
.page-header h1 { margin: 0; color: #101828; font-size: 24px; }
.page-header p { margin: 8px 0 0; color: #667085; }
@media (max-width: 720px) { .support-page { padding: 16px; } }
</style>
