<!--
  IndicatorThresholdsPane — Tab 2: 阈值与告警配置 (展示)。

  显示 GREEN/YELLOW/RED 三色阈值, 含 operator 与阈值数值。
  Phase A 只读模式 — 写入留 Phase B (避免 阈值改动产生 alert 风暴)。
-->
<template>
  <div class="pane">
    <el-alert
      v-if="detail.thresholds.length === 0"
      type="info"
      :closable="false"
      title="未配置阈值"
      description="此指标尚未配置告警阈值。仅在 GREEN/YELLOW/RED 阈值配置后, 系统才会写入 IndicatorVersion.alertLevel 字段。"
      show-icon
    />

    <template v-else>
      <el-table :data="sortedThresholds" border style="width: 100%">
        <el-table-column label="级别" width="120">
          <template #default="{ row }">
            <el-tag :type="levelTagType(row.alertLevel)" effect="dark">
              {{ ALERT_LEVEL_LABELS[String(row.alertLevel) as AlertLevel] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="比较" width="80">
          <template #default="{ row }">
            <code>{{ THRESHOLD_OPERATOR_LABELS[String(row.operator) as ThresholdOperator] }}</code>
          </template>
        </el-table-column>
        <el-table-column label="阈值" min-width="120">
          <template #default="{ row }">
            <span class="threshold-value">
              {{ row.thresholdValue }}
              <span v-if="row.operator === 'BETWEEN' && row.thresholdValueUpper">
                ~ {{ row.thresholdValueUpper }}
              </span>
              <span class="unit">{{ detail.unit || '' }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="解释" min-width="220">
          <template #default="{ row }">
            <span class="explain">{{ explain(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.isActive ? 'success' : 'info'">
              {{ row.isActive ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="legend">
        <p class="legend-title">三色预警规则</p>
        <ul>
          <li><el-tag type="success" size="small" effect="dark">正常</el-tag> 指标处于安全区间</li>
          <li><el-tag type="warning" size="small" effect="dark">黄色预警</el-tag> 需要关注, 接近异常</li>
          <li><el-tag type="danger" size="small" effect="dark">红色预警</el-tag> 立即整改</li>
        </ul>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  type IndicatorDetail,
  type IndicatorThreshold,
  type AlertLevel,
  type ThresholdOperator,
  ALERT_LEVEL_LABELS,
  THRESHOLD_OPERATOR_LABELS,
} from '@/api/canvasIndicators'

interface Props {
  detail: IndicatorDetail
}
const props = defineProps<Props>()

const sortedThresholds = computed(() => {
  // GREEN > YELLOW > RED 顺序更直觉
  const order: Record<AlertLevel, number> = { GREEN: 1, YELLOW: 2, RED: 3 }
  return [...props.detail.thresholds].sort(
    (a, b) => order[a.alertLevel] - order[b.alertLevel],
  )
})

function levelTagType(level: AlertLevel): 'success' | 'warning' | 'danger' {
  switch (level) {
    case 'GREEN': return 'success'
    case 'YELLOW': return 'warning'
    case 'RED': return 'danger'
  }
}

function explain(t: IndicatorThreshold): string {
  const op = THRESHOLD_OPERATOR_LABELS[t.operator]
  if (t.operator === 'BETWEEN') {
    return `当指标值在 [${t.thresholdValue}, ${t.thresholdValueUpper ?? '?'}] 区间内触发 ${ALERT_LEVEL_LABELS[t.alertLevel]}`
  }
  return `当指标值 ${op} ${t.thresholdValue} 时触发 ${ALERT_LEVEL_LABELS[t.alertLevel]}`
}
</script>

<style scoped>
.pane {
  padding: 8px 0;
}

.threshold-value {
  font-family: 'Consolas', monospace;
  font-weight: 500;
}

.unit {
  margin-left: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.explain {
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.legend {
  margin-top: 16px;
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
}

.legend-title {
  margin: 0 0 8px 0;
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.legend ul {
  margin: 0;
  padding-left: 20px;
}

.legend li {
  margin-bottom: 4px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
</style>
