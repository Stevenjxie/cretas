<script setup lang="ts">
import { computed } from 'vue';
import type { ExportRow } from '../types';

const props = defineProps<{
  rows: ExportRow[];
  confirmed: boolean;
  previewConfirmed: boolean;
  canConfirmSchedule: boolean;
  planId?: string | null;
}>();
const emit = defineEmits<{
  (event: 'confirm-schedule'): void;
  (event: 'confirm-preview'): void;
  (event: 'export-csv'): void;
  (event: 'export-xlsx'): void;
}>();

const previewRows = computed(() => props.rows.map((row) => ({
  ...row,
  vehicle: row.vehicle || '待匹配车辆',
  driver: row.driver || '待匹配司机',
})));
const pendingRows = computed(() => previewRows.value.filter((row) => row.vehicle === '待匹配车辆'));
const canExport = computed(() => Boolean(props.rows.length && props.planId));

/** 装载率字符串("82%") → 数值, 给进度条用; 解析不出按 0。 */
function loadPct(loadRate: string): number {
  const n = parseInt(loadRate, 10);
  return Number.isFinite(n) ? Math.max(0, Math.min(100, n)) : 0;
}
/** 装载率 → 进度条颜色(展示用): ≥90 红(接近满载) / ≥80 橙 / 其余绿。 */
function loadColor(loadRate: string): string {
  const pct = loadPct(loadRate);
  if (pct >= 90) return '#f04438';
  if (pct >= 80) return '#f79009';
  return '#12b76a';
}
</script>

<template>
  <section data-testid="export-step" class="export-step"><header><p>第三步</p><h2>确认排班调度</h2><span class="sub">核对当天各车次的车辆、司机、门店顺序，确认后即为当天正式排班。可另存 CSV / Excel 发给司机。</span></header>
    <p v-if="confirmed" data-testid="export-confirmed" class="confirmed-row">当天排班已正式确认。</p>
    <p v-else-if="previewConfirmed" data-testid="export-preview-confirmed" class="preview-row">已核对排班预览；仍有未确认或待匹配车辆的车次，请回到「查看并确认路线」逐一确认后再正式确认当天排班。</p>
    <p v-if="pendingRows.length" data-testid="pending-export-row" class="pending-row">待匹配车辆：{{ pendingRows.map((row) => row.tripLabel).join('、') }}</p>
    <!-- 排班总览卡片(每车一卡, 比纯文字表更有信息层次) -->
    <div class="summary-cards" data-testid="export-summary-cards">
      <article
        v-for="row in previewRows"
        :key="row.tripId"
        class="sc-card"
        :class="{ pending: row.vehicle === '待匹配车辆' }"
        data-testid="export-summary-card"
      >
        <div class="sc-head">
          <strong class="sc-line">{{ row.tripLabel }}</strong>
          <span class="sc-load-badge" :style="{ color: loadColor(row.loadRate), background: loadColor(row.loadRate) + '18' }">装载 {{ row.loadRate }}</span>
        </div>
        <div class="sc-vd">
          <span class="sc-veh">🚚 {{ row.vehicle }}</span>
          <span class="sc-drv">👤 {{ row.driver }}</span>
        </div>
        <div class="sc-bar"><span class="sc-bar-fill" :style="{ width: loadPct(row.loadRate) + '%', background: loadColor(row.loadRate) }" /></div>
        <div class="sc-metrics">
          <span><b>{{ row.storeIds.length }}</b> 店</span>
          <span class="sc-dot">·</span>
          <span>里程 <b>{{ row.distance }}</b></span>
          <span class="sc-dot">·</span>
          <span>体积 <b>{{ row.volume }}</b> m³</span>
        </div>
        <div class="sc-stores" :title="row.storeOrder">{{ row.storeOrder }}</div>
      </article>
    </div>
    <div class="actions">
      <el-button v-if="confirmed" data-testid="confirm-schedule-done" type="success" size="large" disabled>✓ 已确认当天排班</el-button>
      <el-button v-else-if="canConfirmSchedule" data-testid="confirm-schedule" type="primary" size="large" @click="emit('confirm-schedule')">确认当天排班</el-button>
      <el-button v-else data-testid="confirm-export-preview" type="primary" size="large" @click="emit('confirm-preview')">确认排班预览</el-button>
      <span class="export-hint">另存：</span>
      <el-button data-testid="export-csv" :disabled="!canExport" @click="emit('export-csv')">下载 CSV</el-button>
      <el-button data-testid="export-xlsx" :disabled="!canExport" @click="emit('export-xlsx')">下载 Excel</el-button>
    </div>
  </section>
</template>

<style scoped lang="scss">.export-step { display: grid; gap: 20px; } header p { margin: 0 0 6px; color: #1b65a8; font-size: 13px; font-weight: 750; } h2 { margin: 0; color: #101828; } .sub { display: block; margin-top: 6px; color: #667085; font-size: 13px; font-weight: 400; } .actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; } .export-hint { margin-left: 8px; color: #98a2b3; font-size: 13px; } .pending-row { margin: 0; padding: 10px 12px; color: #b54708; font-weight: 600; background: #fffaeb; border-radius: 8px; } .confirmed-row { margin: 0; padding: 10px 12px; color: #027a48; font-weight: 600; background: #ecfdf3; border-radius: 8px; } .preview-row { margin: 0; padding: 10px 12px; color: #175cd3; font-weight: 600; background: #eff8ff; border-radius: 8px; }
/* 排班总览卡片 */
.summary-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.sc-card { display: grid; gap: 8px; padding: 16px; background: #fff; border: 1px solid #e6eaf0; border-radius: 12px; box-shadow: 0 2px 12px rgba(27,101,168,0.06); }
.sc-card.pending { border-color: #fec84b; background: #fffcf5; }
.sc-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.sc-line { color: #101828; font-size: 16px; font-weight: 750; }
.sc-load-badge { padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; white-space: nowrap; }
.sc-vd { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; color: #344054; font-size: 13.5px; font-weight: 650; }
.sc-bar { height: 6px; background: #eef2f7; border-radius: 999px; overflow: hidden; }
.sc-bar-fill { display: block; height: 100%; border-radius: 999px; transition: width 0.3s ease; }
.sc-metrics { display: flex; align-items: baseline; gap: 6px; flex-wrap: wrap; color: #667085; font-size: 13px; font-variant-numeric: tabular-nums; }
.sc-metrics b { color: #101828; font-weight: 750; }
.sc-metrics .sc-dot { color: #cbd5e1; }
.sc-stores { color: #475467; font-size: 12.5px; line-height: 1.6; padding-top: 8px; border-top: 1px dashed #edf2f7; }</style>
