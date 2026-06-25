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

function segDurationH(seg: LaborSegment): number {
  if (!seg.startTime || !seg.endTime) return 0;
  const [sh, sm] = seg.startTime.split(':').map(Number);
  const [eh, em] = seg.endTime.split(':').map(Number);
  const mins = (eh * 60 + em) - (sh * 60 + sm);
  return Math.max(0, mins / 60);
}

const totalHours = computed(() =>
  props.modelValue.reduce((sum, seg) => sum + segDurationH(seg) * (seg.workerCount || 0), 0)
);

function addRow() {
  emit('update:modelValue', [...props.modelValue, { startTime: '', endTime: '', workerCount: 1 }]);
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
</script>

<template>
  <div>
    <el-table :data="modelValue" size="small" border style="width:100%">
      <el-table-column label="开始时间" width="130">
        <template #default="{ row, $index }">
          <el-time-select
            :model-value="row.startTime"
            @update:model-value="(v: string) => updateSeg($index, 'startTime', v)"
            start="00:00" step="00:15" end="23:45" placeholder="开始" style="width:115px"
          />
        </template>
      </el-table-column>
      <el-table-column label="结束时间" width="130">
        <template #default="{ row, $index }">
          <el-time-select
            :model-value="row.endTime"
            @update:model-value="(v: string) => updateSeg($index, 'endTime', v)"
            start="00:00" step="00:15" end="23:45" placeholder="结束" style="width:115px"
          />
        </template>
      </el-table-column>
      <el-table-column label="时长(h)" width="80" align="right">
        <template #default="{ row }">
          {{ segDurationH(row).toFixed(2) }}
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
