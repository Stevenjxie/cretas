<!--
  IndicatorComputationsPane — Tab 3: 计算策略 (展示)。

  显示主备计算策略列表 (按 priority 排序), 三种类型:
    - FORMULA: SpEL 公式 (需走 SandboxedSpelEvaluator)
    - PYTHON_ENDPOINT: HTTP 路径
    - JPA_QUERY: 命名查询 / SQL 草稿
-->
<template>
  <div class="pane">
    <el-alert
      v-if="detail.computations.length === 0"
      type="info"
      :closable="false"
      title="未配置计算策略"
      description="指标需要至少一条计算策略才能 recompute。"
      show-icon
    />

    <template v-else>
      <el-table :data="detail.computations" border style="width: 100%">
        <el-table-column label="优先级" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.priority === 1 ? 'primary' : 'info'">
              {{ row.priority === 1 ? '主' : `备 ${row.priority}` }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="160">
          <template #default="{ row }">
            <el-tag :type="typeTagType(row.computeType)">{{ typeLabel(row.computeType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="源" min-width="320">
          <template #default="{ row }">
            <code class="compute-source">{{ row.computeSource }}</code>
          </template>
        </el-table-column>
        <el-table-column label="参数" min-width="180">
          <template #default="{ row }">
            <code v-if="row.params && Object.keys(row.params).length > 0" class="params">
              {{ formatParams(row.params) }}
            </code>
            <span v-else class="empty">—</span>
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

      <div class="hint">
        <p class="hint-title">计算策略类型说明</p>
        <ul>
          <li>
            <strong>FORMULA</strong>: SpEL 沙箱表达式,
            如 <code>#yield / #total * 100</code>。
            可在「公式测试」 Tab 用样本数据 dry-run。
          </li>
          <li>
            <strong>PYTHON_ENDPOINT</strong>: HTTP 路径,
            指向 Python 服务 (端口 8083) 实际计算。
            如 <code>/api/smartbi/restaurant-ops/gross-margin</code>。
          </li>
          <li>
            <strong>JPA_QUERY</strong>: JPQL 命名查询或 SQL 草稿,
            由 IndicatorQueryService (Phase 1 Day 4) 解析执行。
          </li>
        </ul>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { type IndicatorDetail } from '@/api/canvasIndicators'

interface Props {
  detail: IndicatorDetail
}
defineProps<Props>()

function typeLabel(type: string): string {
  switch (type) {
    case 'FORMULA': return 'SpEL 公式'
    case 'PYTHON_ENDPOINT': return 'Python 端点'
    case 'JPA_QUERY': return 'JPA 查询'
    default: return type
  }
}

function typeTagType(type: string): 'primary' | 'success' | 'warning' | 'info' {
  switch (type) {
    case 'FORMULA': return 'warning'
    case 'PYTHON_ENDPOINT': return 'success'
    case 'JPA_QUERY': return 'primary'
    default: return 'info'
  }
}

function formatParams(p: Record<string, unknown>): string {
  try {
    return JSON.stringify(p)
  } catch {
    return String(p)
  }
}
</script>

<style scoped>
.pane {
  padding: 8px 0;
}

.compute-source {
  font-family: 'Consolas', monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.params {
  font-family: 'Consolas', monospace;
  font-size: 11px;
  color: var(--el-text-color-regular);
}

.empty {
  color: var(--el-text-color-disabled);
}

.hint {
  margin-top: 16px;
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
}

.hint-title {
  margin: 0 0 8px 0;
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.hint ul {
  margin: 0;
  padding-left: 20px;
}

.hint li {
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
}

.hint code {
  background: var(--el-fill-color);
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 11px;
}
</style>
