<script setup lang="ts">
import { computed, ref } from 'vue';
import type {
  LogisticsDeliveryOrder,
  DeliveryExecutionStatus,
  ExceptionReason,
  ExceptionDisposition,
} from '@/api/logistics';
import type { RouteTrip } from '../types';

/**
 * 排线后处理(执行跟踪)—— 调度确认后, 按车次逐门店标记「已送达 / 异常」。
 * 异常走防呆两段引导对话框(原因 → 处置), 单一维度只改 deliveryStatus, 不动规划路线。
 * 使用者是物流调度员(文化素质参差): 明确告诉能做什么(fool-proof Rule 2/3/5)。
 */
const props = defineProps<{
  trips: RouteTrip[];
  orders: LogisticsDeliveryOrder[];
  busyId?: string | null;
  readonly?: boolean;
}>();

const emit = defineEmits<{
  (e: 'deliver', orderId: string): void;
  (e: 'exception', payload: { orderId: string; reason: ExceptionReason; disposition: ExceptionDisposition; note: string | null }): void;
  (e: 'reset', orderId: string): void;
}>();

const REASONS: { value: ExceptionReason; label: string }[] = [
  { value: 'STORE_CLOSED', label: '门店关门 / 未营业' },
  { value: 'REJECTED', label: '门店拒收' },
  { value: 'UNREACHABLE', label: '联系不上收货人' },
  { value: 'DAMAGED', label: '货物破损' },
  { value: 'OTHER', label: '其他原因' },
];
const DISPOSITIONS: { value: ExceptionDisposition; label: string; desc: string }[] = [
  { value: 'RESCHEDULE', label: '明日再送', desc: '保留订单，明天重新排线配送' },
  { value: 'REASSIGN', label: '改派其他车次', desc: '记录改派意向，稍后由调度安排到别的车次' },
  { value: 'RETURN', label: '退回仓库', desc: '货物随车退回仓库' },
  { value: 'CANCEL', label: '取消该单', desc: '本单作废，不再配送' },
];
const reasonLabel = (v?: string | null) => REASONS.find((r) => r.value === v)?.label ?? v ?? '';
const dispositionLabel = (v?: string | null) => DISPOSITIONS.find((d) => d.value === v)?.label ?? v ?? '';

const orderById = computed(() => {
  const m = new Map<string, LogisticsDeliveryOrder>();
  for (const o of props.orders) m.set(o.id, o);
  return m;
});

const execOf = (o?: LogisticsDeliveryOrder | null): DeliveryExecutionStatus => o?.deliveryStatus || 'PENDING';

/** 每车次一组，按 storeIds 顺序取订单（缺失的订单跳过，诚实不伪造）。 */
const groups = computed(() =>
  props.trips.map((t) => ({
    trip: t,
    orders: t.storeIds.map((id) => orderById.value.get(id)).filter((o): o is LogisticsDeliveryOrder => !!o),
  })),
);

/** 全计划进度汇总。 */
const summary = computed(() => {
  let delivered = 0, exception = 0, pending = 0;
  for (const o of props.orders) {
    const s = execOf(o);
    if (s === 'DELIVERED') delivered++;
    else if (s === 'EXCEPTION') exception++;
    else pending++;
  }
  const total = delivered + exception + pending;
  return { delivered, exception, pending, total, donePct: total ? Math.round(((delivered + exception) / total) * 100) : 0 };
});

// ===== 异常上报对话框(两段引导) =====
const dialogOpen = ref(false);
const dialogOrder = ref<LogisticsDeliveryOrder | null>(null);
const exReason = ref<ExceptionReason | null>(null);
const exDisposition = ref<ExceptionDisposition | null>(null);
const exNote = ref('');

// note 何时必填: 原因=其他, 或 处置=改派(要写清改派到哪个车次)
const noteRequired = computed(() => exReason.value === 'OTHER' || exDisposition.value === 'REASSIGN');
const notePlaceholder = computed(() =>
  exDisposition.value === 'REASSIGN' ? '改派到哪个车次 / 线路？(必填)'
    : exReason.value === 'OTHER' ? '请简要说明异常情况 (必填)'
      : '补充说明 (选填)');
const canSubmit = computed(() =>
  !!exReason.value && !!exDisposition.value && (!noteRequired.value || exNote.value.trim().length > 0));

function openException(o: LogisticsDeliveryOrder): void {
  dialogOrder.value = o;
  exReason.value = (o.exceptionReason as ExceptionReason) || null;
  exDisposition.value = (o.exceptionDisposition as ExceptionDisposition) || null;
  exNote.value = o.exceptionNote || '';
  dialogOpen.value = true;
}

function submitException(): void {
  if (!dialogOrder.value || !exReason.value || !exDisposition.value) return;
  emit('exception', {
    orderId: dialogOrder.value.id,
    reason: exReason.value,
    disposition: exDisposition.value,
    note: exNote.value.trim() || null,
  });
  dialogOpen.value = false;
}
</script>

<template>
  <div class="exec-tracker">
    <!-- 进度汇总 -->
    <div class="et-summary">
      <div class="et-progress">
        <div class="et-bar"><span :style="{ width: summary.donePct + '%' }" /></div>
        <span class="et-pct">{{ summary.donePct }}%</span>
      </div>
      <div class="et-counts">
        <span class="et-c ok"><b>{{ summary.delivered }}</b> 已送达</span>
        <span class="et-c warn"><b>{{ summary.exception }}</b> 异常</span>
        <span class="et-c pend"><b>{{ summary.pending }}</b> 待送达</span>
        <span class="et-c total">共 {{ summary.total }} 家</span>
      </div>
    </div>

    <!-- 逐车次逐门店 -->
    <div v-for="g in groups" :key="g.trip.id" class="et-group">
      <div class="et-ghead">
        <span class="et-veh">{{ g.trip.vehiclePlate || '待分配车辆' }}</span>
        <span v-if="g.trip.driverName" class="et-drv">{{ g.trip.driverName }}</span>
        <span class="et-tno">第 {{ g.trip.tripNo }} 车 · {{ g.orders.length }} 家门店</span>
      </div>
      <ul class="et-stores">
        <li v-for="(o, i) in g.orders" :key="o.id" class="et-store" :class="execOf(o).toLowerCase()">
          <span class="et-seq">{{ i + 1 }}</span>
          <div class="et-info">
            <div class="et-name">{{ o.storeName }}</div>
            <div class="et-addr">{{ o.address }}</div>
            <div v-if="execOf(o) === 'EXCEPTION'" class="et-exc">
              {{ reasonLabel(o.exceptionReason) }} → {{ dispositionLabel(o.exceptionDisposition) }}
              <span v-if="o.exceptionNote" class="et-note">「{{ o.exceptionNote }}」</span>
            </div>
          </div>
          <div class="et-actions">
            <template v-if="execOf(o) === 'PENDING'">
              <template v-if="!readonly">
                <el-button type="success" size="small" :loading="busyId === o.id" @click="emit('deliver', o.id)">已送达</el-button>
                <el-button type="warning" size="small" plain :disabled="busyId === o.id" @click="openException(o)">异常</el-button>
              </template>
              <el-tag v-else type="info" effect="plain">待送达</el-tag>
            </template>
            <template v-else-if="execOf(o) === 'DELIVERED'">
              <el-tag type="success" effect="dark">已送达</el-tag>
              <el-button v-if="!readonly" link type="info" :loading="busyId === o.id" @click="emit('reset', o.id)">撤销</el-button>
            </template>
            <template v-else>
              <el-tag type="danger" effect="dark">异常</el-tag>
              <el-button v-if="!readonly" link type="info" :loading="busyId === o.id" @click="openException(o)">改</el-button>
              <el-button v-if="!readonly" link type="info" :loading="busyId === o.id" @click="emit('reset', o.id)">撤销</el-button>
            </template>
          </div>
        </li>
      </ul>
    </div>

    <!-- 异常上报: 两段引导(原因 → 处置), 门店名/地址常显(fool-proof Rule 2) -->
    <el-dialog v-model="dialogOpen" title="上报配送异常" width="440px" append-to-body destroy-on-close>
      <div v-if="dialogOrder" class="ex-dialog">
        <div class="ex-ctx">
          <div class="ex-store">{{ dialogOrder.storeName }}</div>
          <div class="ex-addr">{{ dialogOrder.address }}</div>
        </div>

        <div class="ex-step">
          <div class="ex-label"><span class="ex-num">1</span>异常原因</div>
          <el-radio-group v-model="exReason" class="ex-radios">
            <el-radio v-for="r in REASONS" :key="r.value" :value="r.value" border>{{ r.label }}</el-radio>
          </el-radio-group>
        </div>

        <div class="ex-step" :class="{ disabled: !exReason }">
          <div class="ex-label"><span class="ex-num">2</span>怎么处置</div>
          <div class="ex-disp">
            <label
              v-for="d in DISPOSITIONS"
              :key="d.value"
              class="ex-dcard"
              :class="{ active: exDisposition === d.value }"
            >
              <input type="radio" :value="d.value" :checked="exDisposition === d.value" :disabled="!exReason" @change="exDisposition = d.value" />
              <span class="ex-dlabel">{{ d.label }}</span>
              <span class="ex-ddesc">{{ d.desc }}</span>
            </label>
          </div>
        </div>

        <el-input
          v-model="exNote"
          type="textarea"
          :rows="2"
          :placeholder="notePlaceholder"
          maxlength="200"
          show-word-limit
          class="ex-note-input"
        />
      </div>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!canSubmit" @click="submitException">确认上报</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.exec-tracker { display: flex; flex-direction: column; gap: 14px; }
/* 进度汇总 */
.et-summary { display: flex; flex-direction: column; gap: 8px; padding: 12px 14px; background: linear-gradient(180deg,#fff,#f8fafc); border: 1px solid #e2e8f0; border-radius: 10px; }
.et-progress { display: flex; align-items: center; gap: 10px; }
.et-bar { flex: 1 1 auto; height: 8px; background: #eef2f6; border-radius: 6px; overflow: hidden; }
.et-bar span { display: block; height: 100%; background: linear-gradient(90deg,#1B65A8,#3a86d4); border-radius: 6px; transition: width .3s ease; }
.et-pct { flex: 0 0 auto; color: #1B65A8; font-weight: 700; font-variant-numeric: tabular-nums; }
.et-counts { display: flex; flex-wrap: wrap; gap: 14px; font-size: 13px; color: #667085; }
.et-c b { font-size: 16px; font-variant-numeric: tabular-nums; margin-right: 3px; }
.et-c.ok b { color: #16a34a; } .et-c.warn b { color: #dc2626; } .et-c.pend b { color: #64748b; }
.et-c.total { margin-left: auto; color: #98a2b3; }
/* 车次分组 */
.et-group { border: 1px solid #e2e8f0; border-radius: 10px; overflow: hidden; }
.et-ghead { display: flex; align-items: center; gap: 10px; padding: 9px 14px; background: #f1f5f9; border-bottom: 1px solid #e2e8f0; }
.et-veh { color: #0f172a; font-weight: 700; font-size: 14px; }
.et-drv { color: #475569; font-size: 12.5px; }
.et-tno { margin-left: auto; color: #94a3b8; font-size: 12px; }
.et-stores { list-style: none; margin: 0; padding: 0; }
.et-store { display: flex; align-items: center; gap: 12px; padding: 10px 14px; border-bottom: 1px solid #f1f5f9; }
.et-store:last-child { border-bottom: none; }
.et-store.delivered { background: #f0fdf4; } .et-store.exception { background: #fef2f2; }
.et-seq { flex: 0 0 24px; width: 24px; height: 24px; display: grid; place-items: center; border-radius: 50%; background: #e2e8f0; color: #475569; font-size: 12.5px; font-weight: 700; }
.et-store.delivered .et-seq { background: #16a34a; color: #fff; } .et-store.exception .et-seq { background: #dc2626; color: #fff; }
.et-info { flex: 1 1 auto; min-width: 0; }
.et-name { color: #101828; font-weight: 650; font-size: 14px; }
.et-addr { color: #94a3b8; font-size: 12px; margin-top: 1px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.et-exc { color: #dc2626; font-size: 12px; margin-top: 3px; }
.et-note { color: #b45309; }
.et-actions { flex: 0 0 auto; display: flex; align-items: center; gap: 6px; }
/* 异常对话框 */
.ex-dialog { display: flex; flex-direction: column; gap: 16px; }
.ex-ctx { padding: 10px 12px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; }
.ex-store { color: #0f172a; font-weight: 700; font-size: 15px; }
.ex-addr { color: #94a3b8; font-size: 12.5px; margin-top: 2px; }
.ex-step.disabled { opacity: .5; pointer-events: none; }
.ex-label { display: flex; align-items: center; gap: 8px; color: #344054; font-weight: 650; font-size: 13.5px; margin-bottom: 10px; }
.ex-num { width: 20px; height: 20px; display: grid; place-items: center; border-radius: 50%; background: #1B65A8; color: #fff; font-size: 12px; }
.ex-radios { display: flex; flex-wrap: wrap; gap: 8px; }
.ex-radios :deep(.el-radio) { margin-right: 0; }
.ex-disp { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.ex-dcard { display: flex; flex-direction: column; gap: 2px; padding: 8px 10px; border: 1px solid #dcdfe6; border-radius: 8px; cursor: pointer; transition: all .15s; }
.ex-dcard.active { border-color: #1B65A8; background: #eff5fc; box-shadow: 0 0 0 1px #1B65A8 inset; }
.ex-dcard input { position: absolute; opacity: 0; width: 0; height: 0; }
.ex-dlabel { color: #101828; font-weight: 650; font-size: 13.5px; }
.ex-ddesc { color: #94a3b8; font-size: 11.5px; line-height: 1.35; }
</style>
