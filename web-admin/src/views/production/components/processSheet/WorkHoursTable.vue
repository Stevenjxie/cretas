<script setup lang="ts">
import { computed } from 'vue';
import { Plus, Delete } from '@element-plus/icons-vue';
import type { LaborSegment } from '@/api/processSheet';

const props = defineProps<{
  modelValue: LaborSegment[];
}>();
const emit = defineEmits<{
  (e: 'update:modelValue', v: LaborSegment[]): void;
}>();

// 🔴 低文化仓管/操作员防呆 (fool-proof-design.md Rule 1/3): 原滚轮 el-time-picker 双输入
// (开始+结束) 点选易脱靶、打字后不按 Enter 会静默丢失。改为「时长(分钟)」直接数字输入
// 作为主输入 (打字/点+-都稳定提交), 开始时间用简单下拉列表 (el-time-select, 非滚轮)
// 且默认自动衔接上一段, 结束时间由「开始+时长」推算展示 (只读), 不再需要用户操作滚轮。
// 数据模型仍是 { startTime, endTime, workerCount } (与后端 LaborSegment DTO / 报工详情页
// "08:00-10:00" 展示 100% 兼容), segDurationH/成本公式 (hours × rate × 人数) 不变。

function segDurationH(seg: LaborSegment): number {
  if (!seg.startTime || !seg.endTime) return 0;
  const [sh, sm] = seg.startTime.split(':').map(Number);
  const [eh, em] = seg.endTime.split(':').map(Number);
  const mins = (eh * 60 + em) - (sh * 60 + sm);
  return Math.max(0, mins / 60);
}

function segDurationMinutes(seg: LaborSegment): number {
  return Math.round(segDurationH(seg) * 60);
}

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

/** HH:mm + N分钟 → HH:mm (跨天环绕, 防御性; 生产工时通常不跨天) */
function addMinutes(time: string, minutes: number): string {
  const [h, m] = time.split(':').map(Number);
  const dayMins = 24 * 60;
  const total = (((h * 60 + m) + minutes) % dayMins + dayMins) % dayMins;
  return `${pad2(Math.floor(total / 60))}:${pad2(total % 60)}`;
}

/** 默认开始时间: 衔接上一段结束时间(自然顺延, 操作员通常无需再改), 否则取当前整点/半点 */
function suggestStartTime(): string {
  const prev = props.modelValue[props.modelValue.length - 1];
  if (prev?.endTime) return prev.endTime;
  const now = new Date();
  return `${pad2(now.getHours())}:${now.getMinutes() < 30 ? '00' : '30'}`;
}

const totalHours = computed(() =>
  props.modelValue.reduce((sum, seg) => sum + segDurationH(seg) * (seg.workerCount || 0), 0)
);

function addRow() {
  const startTime = suggestStartTime();
  const endTime = addMinutes(startTime, 60); // 默认时长 1 小时, 操作员用「时长」框直接改
  emit('update:modelValue', [...props.modelValue, { startTime, endTime, workerCount: 1 }]);
}

function removeRow(idx: number) {
  const next = [...props.modelValue];
  next.splice(idx, 1);
  emit('update:modelValue', next);
}

function updateSeg(idx: number, field: keyof LaborSegment, value: string | number) {
  const next = [...props.modelValue];
  next[idx] = { ...next[idx], [field]: value };
  emit('update:modelValue', next);
}

/** 开始时间改变 → 保持已录时长, 结束时间跟着平移重新推算 */
function updateStartTime(idx: number, value: string) {
  const seg = props.modelValue[idx];
  const duration = segDurationMinutes(seg) || 60;
  const startTime = value || '';
  const next = [...props.modelValue];
  next[idx] = { ...next[idx], startTime, endTime: startTime ? addMinutes(startTime, duration) : '' };
  emit('update:modelValue', next);
}

/** 时长(分钟) 是主输入: el-input-number 打字/点+-都直接提交, 无滚轮脱靶风险 */
function updateDurationMinutes(idx: number, minutes: number) {
  const seg = props.modelValue[idx];
  const startTime = seg.startTime || suggestStartTime();
  const safeMinutes = Number.isFinite(minutes) && minutes > 0 ? minutes : 1;
  const next = [...props.modelValue];
  next[idx] = { ...next[idx], startTime, endTime: addMinutes(startTime, safeMinutes) };
  emit('update:modelValue', next);
}
</script>

<template>
  <div>
    <el-table :data="modelValue" size="small" border style="width:100%">
      <el-table-column label="开始时间" width="140">
        <template #default="{ row, $index }">
          <el-time-select
            :model-value="row.startTime"
            @update:model-value="(v: string) => updateStartTime($index, v || '')"
            start="00:00" step="00:15" end="23:45"
            placeholder="开始" style="width:125px"
          />
        </template>
      </el-table-column>
      <el-table-column label="时长(分钟)" width="160">
        <template #default="{ row, $index }">
          <el-input-number
            :model-value="segDurationMinutes(row)"
            @update:model-value="(v: number) => updateDurationMinutes($index, v)"
            :min="1" :step="5" :precision="0" controls-position="right" style="width:140px"
          />
        </template>
      </el-table-column>
      <el-table-column label="结束时间" width="90" align="center">
        <template #default="{ row }">
          <span style="color:#909399">{{ row.endTime || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="人数" width="120">
        <template #default="{ row, $index }">
          <el-input-number
            :model-value="row.workerCount"
            @update:model-value="(v: number) => updateSeg($index, 'workerCount', v)"
            :min="1" :precision="0" controls-position="right" style="width:100px"
          />
        </template>
      </el-table-column>
      <el-table-column label="工时(h)" width="80" align="right">
        <template #default="{ row }">
          {{ (segDurationH(row) * (row.workerCount || 0)).toFixed(2) }}
        </template>
      </el-table-column>
      <el-table-column width="60" align="center">
        <template #default="{ $index }">
          <el-button link type="danger" :icon="Delete" @click="removeRow($index)" />
        </template>
      </el-table-column>
    </el-table>

    <div style="display:flex;justify-content:space-between;align-items:center;margin-top:6px">
      <el-button size="small" :icon="Plus" @click="addRow">+ 工时段</el-button>
      <span style="font-size:12px;color:#606266">合计工时: <b>{{ totalHours.toFixed(2) }} h</b></span>
    </div>
  </div>
</template>
