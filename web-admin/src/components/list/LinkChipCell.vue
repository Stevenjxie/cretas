<script setup lang="ts">
import { computed } from 'vue';
import type { LinkChipCounts } from '@/composables/useLinkChipCounts';

/**
 * Sprint 6 W3-A — Inline 3-chip link counter cell (文件 / 图片 / 合同).
 *
 * Reusable across 4 list views (sales / procurement / inventory / voucher)
 * to render the attachment count breakdown. Replaces Sprint 5 PR #58
 * unified `链:N` chip per HJ baseline (Round 12 §B.6 X2 + Round 13 §3).
 *
 * Usage:
 *   <el-table-column label="附件" width="200" align="center">
 *     <template #default="{ row }">
 *       <LinkChipCell :counts="countsFor(row.id)" />
 *     </template>
 *   </el-table-column>
 *
 * Empty state: all 3 counts = 0 renders "-" (column not visually noisy).
 */
const props = defineProps<{
  counts: LinkChipCounts;
}>();

const total = computed(() =>
  props.counts.fileCount + props.counts.imageCount + props.counts.contractCount
);
</script>

<template>
  <span v-if="total === 0" style="color: var(--text-color-secondary, #909399);">-</span>
  <span v-else class="link-chip-row">
    <el-tooltip placement="top">
      <template #content>
        <div style="line-height: 1.6;">
          <div><b>文件</b>: {{ counts.fileCount }} (DOCUMENT / OTHER)</div>
          <div><b>图片</b>: {{ counts.imageCount }} (PHOTO / VIDEO)</div>
          <div><b>合同</b>: {{ counts.contractCount }} (CONTRACT)</div>
          <div style="margin-top: 4px; color: var(--text-color-secondary);">点详情查看附件清单</div>
        </div>
      </template>
      <span class="link-chip-row">
        <el-tag
          v-if="counts.fileCount > 0"
          type="info"
          size="small"
          effect="plain"
          class="link-chip"
        >文件:{{ counts.fileCount }}</el-tag>
        <el-tag
          v-if="counts.imageCount > 0"
          type="primary"
          size="small"
          effect="plain"
          class="link-chip"
        >图片:{{ counts.imageCount }}</el-tag>
        <el-tag
          v-if="counts.contractCount > 0"
          type="success"
          size="small"
          effect="plain"
          class="link-chip"
        >合同:{{ counts.contractCount }}</el-tag>
      </span>
    </el-tooltip>
  </span>
</template>

<style scoped>
.link-chip-row {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  justify-content: center;
}
.link-chip {
  /* Element Plus default small chip is fine; just tighten horizontal padding
     so 3 chips fit in a ~200px column without wrapping when counts are <=99. */
  padding-left: 6px;
  padding-right: 6px;
}
</style>
