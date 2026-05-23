<script setup lang="ts">
import type { CustomerTrackingRecord } from '@/api/customerTracking';

defineProps<{
  records: CustomerTrackingRecord[];
}>();
</script>

<template>
  <div class="tracking-timeline">
    <el-empty v-if="!records.length" description="暂无跟踪记录" />
    <el-timeline v-else>
      <el-timeline-item
        v-for="record in records"
        :key="record.id"
        :timestamp="record.recordTime"
        placement="top"
      >
        <el-card shadow="hover">
          <div class="tracking-timeline-header">
            <span class="tracking-timeline-recorder">{{ record.recorderName || '未署名' }}</span>
            <span v-if="record.contactPerson" class="tracking-timeline-contact">
              · 联系人: {{ record.contactPerson }}
            </span>
            <span v-if="record.contactPhone" class="tracking-timeline-phone">
              · {{ record.contactPhone }}
            </span>
          </div>
          <div class="tracking-timeline-content">{{ record.content }}</div>
          <div v-if="record.address" class="tracking-timeline-meta">
 {{ record.address }}
          </div>
          <div v-if="record.remark" class="tracking-timeline-meta">
            备注: {{ record.remark }}
          </div>
        </el-card>
      </el-timeline-item>
    </el-timeline>
  </div>
</template>

<style scoped>
.tracking-timeline { padding: 8px 16px; max-height: 70vh; overflow-y: auto; }
.tracking-timeline-header {
  display: flex; gap: 6px; align-items: baseline; margin-bottom: 8px;
  font-size: 13px;
}
.tracking-timeline-recorder { font-weight: 600; }
.tracking-timeline-contact, .tracking-timeline-phone {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.tracking-timeline-content {
  font-size: 13px;
  line-height: 1.55;
  color: var(--el-text-color-primary);
  white-space: pre-wrap;
}
.tracking-timeline-meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
