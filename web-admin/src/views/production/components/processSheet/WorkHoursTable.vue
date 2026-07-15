<script setup lang="ts">
import { computed } from 'vue';
import { Plus, Delete } from '@element-plus/icons-vue';
import type { LaborSegment } from '@/api/processSheet';

const props = defineProps<{ modelValue: LaborSegment[] }>();
const emit = defineEmits<{ (e: 'update:modelValue', value: LaborSegment[]): void }>();

function clockMinutes(time: string): number | null {
  const match = /^(\d{2}):(\d{2})$/.exec(time || '');
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > 23 || minute > 59) return null;
  return hour * 60 + minute;
}

/** End clock earlier than start means the segment ends on the following day. */
function segmentDurationMinutes(segment: LaborSegment): number {
  const start = clockMinutes(segment.startTime);
  const end = clockMinutes(segment.endTime);
  if (start == null || end == null) return 0;
  const minutes = end - start;
  return minutes < 0 ? minutes + 24 * 60 : minutes;
}

function segmentDurationHours(segment: LaborSegment): number {
  return segmentDurationMinutes(segment) / 60;
}

function crossesMidnight(segment: LaborSegment): boolean {
  const start = clockMinutes(segment.startTime);
  const end = clockMinutes(segment.endTime);
  return start != null && end != null && end < start;
}

function pad2(value: number): string {
  return String(value).padStart(2, '0');
}

function addMinutes(time: string, minutes: number): string {
  const start = clockMinutes(time) ?? 0;
  const total = (start + minutes) % (24 * 60);
  return `${pad2(Math.floor(total / 60))}:${pad2(total % 60)}`;
}

function suggestedStartTime(): string {
  const previous = props.modelValue.at(-1);
  if (previous?.endTime) return previous.endTime;
  const now = new Date();
  return `${pad2(now.getHours())}:${now.getMinutes() < 30 ? '00' : '30'}`;
}

const elapsedHours = computed(() =>
  props.modelValue.reduce((sum, segment) => sum + segmentDurationHours(segment), 0),
);

const personHours = computed(() =>
  props.modelValue.reduce(
    (sum, segment) => sum + segmentDurationHours(segment) * Math.max(0, Number(segment.workerCount) || 0),
    0,
  ),
);

function updateSegment(index: number, field: keyof LaborSegment, value: string | number) {
  const next = [...props.modelValue];
  next[index] = { ...next[index], [field]: value };
  emit('update:modelValue', next);
}

function addRow() {
  const startTime = suggestedStartTime();
  emit('update:modelValue', [
    ...props.modelValue,
    { startTime, endTime: addMinutes(startTime, 60), workerCount: 1 },
  ]);
}

function removeRow(index: number) {
  const next = [...props.modelValue];
  next.splice(index, 1);
  emit('update:modelValue', next);
}
</script>

<template>
  <div>
    <el-table :data="modelValue" size="small" border style="width:100%">
      <el-table-column label="开始时间" width="145">
        <template #default="{ row, $index }">
          <el-time-select
            :model-value="row.startTime"
            @update:model-value="(value: string) => updateSegment($index, 'startTime', value || '')"
            start="00:00"
            step="00:15"
            end="23:45"
            placeholder="开始"
            style="width:125px"
          />
        </template>
      </el-table-column>
      <el-table-column label="结束时间" width="180">
        <template #default="{ row, $index }">
          <div style="display:flex;align-items:center;gap:6px">
            <el-time-select
              :model-value="row.endTime"
              @update:model-value="(value: string) => updateSegment($index, 'endTime', value || '')"
              start="00:00"
              step="00:15"
              end="23:45"
              placeholder="结束"
              style="width:125px"
            />
            <el-tag v-if="crossesMidnight(row)" size="small" type="warning">次日</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="人数" width="120">
        <template #default="{ row, $index }">
          <el-input-number
            :model-value="row.workerCount"
            @update:model-value="(value: number) => updateSegment($index, 'workerCount', value)"
            :min="1"
            :precision="0"
            controls-position="right"
            style="width:100px"
          />
        </template>
      </el-table-column>
      <el-table-column label="时长" width="90" align="right">
        <template #default="{ row }">{{ segmentDurationHours(row).toFixed(2) }} h</template>
      </el-table-column>
      <el-table-column label="人工工时" width="100" align="right">
        <template #default="{ row }">
          {{ (segmentDurationHours(row) * (row.workerCount || 0)).toFixed(2) }} h
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
      <span style="font-size:12px;color:#606266">
        合计时长 <b>{{ elapsedHours.toFixed(2) }} h</b> · 人工工时 <b>{{ personHours.toFixed(2) }} h</b>
      </span>
    </div>
  </div>
</template>
