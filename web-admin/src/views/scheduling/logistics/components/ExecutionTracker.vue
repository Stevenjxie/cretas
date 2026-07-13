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
 * 视觉对齐 scratchpad/execution-module-mockup.html: exec-head(进度+完成本次调度) +
 * 圆角车次卡(圆形序号图标 + 逐门店 + 一键全部送达)。异常走防呆两段引导对话框(原因→处置)。
 * 执行态只改 deliveryStatus 单一维度, 不动规划路线(fool-proof Rule 2/3/5)。
 */
const props = defineProps<{
  trips: RouteTrip[];
  orders: LogisticsDeliveryOrder[];
  busyId?: string | null;
  readonly?: boolean;
}>();

const emit = defineEmits<{
  (e: 'deliver', orderId: string): void;
  (e: 'deliver-all', orderIds: string[]): void;
  (e: 'exception', payload: { orderId: string; reason: ExceptionReason; disposition: ExceptionDisposition; note: string | null }): void;
  (e: 'reset', orderId: string): void;
  (e: 'complete'): void;
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

/** "2026-07-14T08:12:34.1" → "08:12"; 空/异常返空。 */
function fmtTime(iso?: string | null): string {
  if (!iso) return '';
  const t = iso.split('T')[1];
  return t ? t.slice(0, 5) : '';
}

/** 每车次一组，按 storeIds 顺序取订单(缺失跳过，诚实不伪造) + 该车次送达计数。 */
const groups = computed(() =>
  props.trips.map((t) => {
    const os = t.storeIds.map((id) => orderById.value.get(id)).filter((o): o is LogisticsDeliveryOrder => !!o);
    const delivered = os.filter((o) => execOf(o) === 'DELIVERED').length;
    const pendingIds = os.filter((o) => execOf(o) === 'PENDING').map((o) => o.id);
    return { trip: t, orders: os, delivered, total: os.length, pendingIds };
  }),
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
  const done = delivered + exception;
  return {
    delivered, exception, pending, total, done,
    donePct: total ? Math.round((done / total) * 100) : 0,
    status: total === 0 || done === 0 ? 'PENDING' : (done < total ? 'RUNNING' : 'DONE'),
  };
});
const statusBadge = computed(() => {
  if (summary.value.status === 'DONE') return { cls: 'b-done', text: '已完成' };
  if (summary.value.status === 'RUNNING') return { cls: 'b-running', text: '执行中' };
  return { cls: 'b-pending', text: '待执行' };
});
const allDone = computed(() => summary.value.total > 0 && summary.value.pending === 0);

// ===== 异常上报对话框(两段引导) =====
const dialogOpen = ref(false);
const dialogOrder = ref<LogisticsDeliveryOrder | null>(null);
const exReason = ref<ExceptionReason | null>(null);
const exDisposition = ref<ExceptionDisposition | null>(null);
const exNote = ref('');

const noteRequired = computed(() => exReason.value === 'OTHER' || exDisposition.value === 'REASSIGN');
const notePlaceholder = computed(() =>
  exDisposition.value === 'REASSIGN' ? '改派到哪个车次 / 线路？(必填)'
    : exReason.value === 'OTHER' ? '请简要说明异常情况 (必填)'
      : '补充说明 (选填)');
const canSubmit = computed(() =>
  !!exReason.value && !!exDisposition.value && (!noteRequired.value || exNote.value.trim().length > 0));

/** preselectReassign=true 时(点「改派」快捷入口)预选改派处置。 */
function openException(o: LogisticsDeliveryOrder, preselectReassign = false): void {
  dialogOrder.value = o;
  exReason.value = (o.exceptionReason as ExceptionReason) || null;
  exDisposition.value = (o.exceptionDisposition as ExceptionDisposition) || (preselectReassign ? 'REASSIGN' : null);
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
    <!-- exec-head: 进度汇总 + 完成本次调度 -->
    <div class="exec-head">
      <div class="eh-left">
        <span class="eh-title"><b>今日执行</b><span>{{ trips.length }} 车次 · {{ summary.total }} 门店</span></span>
        <span class="badge" :class="statusBadge.cls">{{ statusBadge.text }}</span>
      </div>
      <div class="eh-prog">
        <div class="bar"><div class="fill" :style="{ width: summary.donePct + '%' }" /></div>
        <span class="txt">{{ summary.done }} / {{ summary.total }} 已送达</span>
      </div>
      <el-button
        v-if="!readonly"
        class="eh-done-btn" :disabled="!allDone" @click="emit('complete')"
      >完成本次调度</el-button>
    </div>

    <!-- 逐车次卡 -->
    <div v-for="g in groups" :key="g.trip.id" class="trip-card">
      <div class="tc-head">
        <div class="tc-h-left">
          <b>线路 {{ String(g.trip.tripNo).padStart(2, '0') }}<template v-if="g.trip.vehicleTripSeq"> · 第 {{ g.trip.vehicleTripSeq }} 趟</template></b>
          <span class="tc-veh">🚚 {{ g.trip.vehiclePlate || '待分配车辆' }}<template v-if="g.trip.driverName"> · 👤 {{ g.trip.driverName }}</template></span>
        </div>
        <div class="tc-h-right">
          <span class="tc-count" :class="{ done: g.delivered === g.total && g.total > 0 }">{{ g.delivered }} / {{ g.total }} 已送达</span>
          <button
            v-if="!readonly && g.pendingIds.length > 0"
            class="tc-all-btn" @click="emit('deliver-all', g.pendingIds)"
          >一键全部送达</button>
        </div>
      </div>
      <div class="stops">
        <div
          v-for="(o, i) in g.orders" :key="o.id"
          class="stop" :class="{ done: execOf(o) === 'DELIVERED', exc: execOf(o) === 'EXCEPTION' }"
        >
          <span class="st-ic" :class="execOf(o).toLowerCase()">
            <template v-if="execOf(o) === 'DELIVERED'">✓</template>
            <template v-else-if="execOf(o) === 'EXCEPTION'">!</template>
            <template v-else>{{ i + 1 }}</template>
          </span>
          <div class="st-body">
            <div class="st-name">{{ o.storeName }}</div>
            <div v-if="execOf(o) === 'DELIVERED'" class="st-meta ok">已送达<template v-if="fmtTime(o.deliveredAt)"> {{ fmtTime(o.deliveredAt) }}</template></div>
            <div v-else-if="execOf(o) === 'EXCEPTION'" class="st-meta exc">
              异常 · {{ reasonLabel(o.exceptionReason) }} → {{ dispositionLabel(o.exceptionDisposition) }}
              <template v-if="o.exceptionNote">「{{ o.exceptionNote }}」</template>
            </div>
            <div v-else class="st-meta wait">待送达<template v-if="o.windowStart"> · {{ o.windowStart }}<template v-if="o.windowEnd">-{{ o.windowEnd }}</template></template></div>
          </div>
          <div class="st-ops">
            <template v-if="readonly" />
            <template v-else-if="execOf(o) === 'PENDING'">
              <el-button class="st-btn done" :loading="busyId === o.id" @click="emit('deliver', o.id)">✓ 已送达</el-button>
              <button class="st-btn exc" :disabled="busyId === o.id" @click="openException(o)">标异常</button>
              <button class="st-btn edit" :disabled="busyId === o.id" @click="openException(o, true)">改派</button>
            </template>
            <template v-else-if="execOf(o) === 'DELIVERED'">
              <span class="st-undo" :class="{ disabled: busyId === o.id }" @click="busyId !== o.id && emit('reset', o.id)">撤销</span>
            </template>
            <template v-else>
              <button class="st-btn edit" :disabled="busyId === o.id" @click="openException(o)">查看 / 改</button>
              <span class="st-undo" :class="{ disabled: busyId === o.id }" @click="busyId !== o.id && emit('reset', o.id)">撤销</span>
            </template>
          </div>
        </div>
      </div>
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
              v-for="d in DISPOSITIONS" :key="d.value"
              class="ex-dcard" :class="{ active: exDisposition === d.value }"
            >
              <input type="radio" :value="d.value" :checked="exDisposition === d.value" :disabled="!exReason" @change="exDisposition = d.value" />
              <span class="ex-dlabel">{{ d.label }}</span>
              <span class="ex-ddesc">{{ d.desc }}</span>
            </label>
          </div>
        </div>
        <el-input
          v-model="exNote" type="textarea" :rows="2"
          :placeholder="notePlaceholder" maxlength="200" show-word-limit class="ex-note-input"
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

/* 状态徽章 */
.badge { display: inline-flex; align-items: center; gap: 4px; padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; white-space: nowrap; }
.b-pending { color: #475467; background: #f2f4f7; }
.b-running { color: #175cd3; background: #eff8ff; }
.b-done { color: #027a48; background: #ecfdf3; }

/* exec-head */
.exec-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; background: linear-gradient(180deg,#fff,#f8fafc); border: 1px solid #E2E8F0; border-radius: 10px; padding: 12px 18px; }
.eh-left { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.eh-title b { font-size: 15px; color: #101828; } .eh-title span { font-size: 11.5px; color: #98a2b3; margin-left: 6px; }
.eh-prog { display: flex; align-items: center; gap: 10px; min-width: 260px; flex: 1 1 260px; }
.eh-prog .bar { flex: 1; height: 8px; background: #eef2f7; border-radius: 999px; overflow: hidden; }
.eh-prog .fill { height: 100%; background: #12b76a; border-radius: 999px; transition: width .3s ease; }
.eh-prog .txt { font-size: 12.5px; font-weight: 700; color: #101828; font-variant-numeric: tabular-nums; }
.eh-done-btn { padding: 8px 16px; color: #fff; background: #027a48; border: 0; border-radius: 7px; font-size: 13px; font-weight: 650; }
.eh-done-btn:not(.is-disabled):hover { background: #04663d; color: #fff; }
.eh-done-btn.is-disabled { background: #cbd5e1; color: #fff; cursor: not-allowed; }

/* 车次卡 */
.trip-card { background: #fff; border: 1px solid #EDF2F7; border-radius: 12px; overflow: hidden; }
.tc-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 16px; background: #fafbfc; border-bottom: 1px solid #EDF2F7; }
.tc-h-left { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; } .tc-h-left b { font-size: 14px; color: #101828; }
.tc-veh { font-size: 12.5px; color: #475467; font-weight: 600; }
.tc-h-right { display: flex; align-items: center; gap: 10px; }
.tc-count { font-size: 12.5px; font-weight: 700; color: #175cd3; font-variant-numeric: tabular-nums; } .tc-count.done { color: #027a48; }
.tc-all-btn { font-size: 12px; padding: 5px 11px; color: #1B65A8; background: #eff6fd; border: 1px solid #cfe3fb; border-radius: 7px; cursor: pointer; font-weight: 600; }
.tc-all-btn:hover { background: #dbeafe; }

/* 停靠卡 */
.stops { padding: 8px 12px; display: flex; flex-direction: column; gap: 6px; }
.stop { display: flex; align-items: center; gap: 10px; padding: 9px 12px; border: 1px solid #EDF2F7; border-radius: 9px; }
.stop.done { background: #f6fef9; border-color: #a6f4c5; } .stop.exc { background: #fef3f2; border-color: #fecdca; }
.st-ic { width: 22px; height: 22px; flex: none; display: grid; place-items: center; border-radius: 50%; font-size: 12px; font-weight: 800; }
.st-ic.pending { color: #98a2b3; background: #f2f4f7; border: 1.5px solid #d0d5dd; }
.st-ic.delivered { color: #fff; background: #12b76a; } .st-ic.exception { color: #fff; background: #f04438; }
.st-body { flex: 1; min-width: 0; } .st-name { color: #101828; font-size: 13px; font-weight: 650; }
.st-meta { font-size: 11.5px; margin-top: 1px; } .st-meta.ok { color: #027a48; } .st-meta.wait { color: #98a2b3; } .st-meta.exc { color: #b42318; }
.st-ops { flex: none; display: flex; align-items: center; gap: 6px; }
.st-btn { font-size: 12px; padding: 5px 10px; border-radius: 6px; cursor: pointer; font-weight: 600; border: 1px solid #dbe3ec; background: #fff; color: #475569; height: auto; }
.st-btn:disabled { opacity: .55; cursor: not-allowed; }
.st-btn.done { color: #fff; background: #12b76a; border-color: #12b76a; }
.st-btn.done:not(.is-disabled):hover { background: #0e9f5b; color: #fff; border-color: #0e9f5b; }
.st-btn.exc { color: #b42318; background: #fff; border-color: #fecdca; }
.st-btn.exc:hover { background: #fef3f2; }
.st-btn.edit { color: #1b65a8; border-color: #cfe3fb; background: #eff6fd; }
.st-btn.edit:hover { background: #dbeafe; }
.st-undo { font-size: 11.5px; color: #98a2b3; cursor: pointer; text-decoration: underline; }
.st-undo.disabled { opacity: .5; cursor: not-allowed; }

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
