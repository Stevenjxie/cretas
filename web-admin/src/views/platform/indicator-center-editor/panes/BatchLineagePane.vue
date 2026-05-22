<!--
  BatchLineagePane — Tab 8: 批次溯源 (lineage closure)。

  给定 batchType + batchId, 用 closure 表查询 全部祖先 + 后代 (含跳数 depth)。
  关键能力 — vs GuanData V8.2 差异化护城河 (食品行业 forensic 追溯)。
-->
<template>
  <div class="pane">
    <el-alert
      type="success"
      :closable="false"
      title="批次溯源 — 食品行业核心能力"
      description="任意批次正向 / 反向追溯, 含跳数 depth + 流转数量。客户投诉追责必需。"
      show-icon
      style="margin-bottom: 12px"
    />

    <el-form :inline="true" :model="form" label-width="100px">
      <el-form-item label="批次类型">
        <el-select v-model="form.batchType" style="width: 200px">
          <el-option label="原料批次 (MATERIAL_BATCH)" value="MATERIAL_BATCH" />
          <el-option label="生产批次 (PRODUCTION_BATCH)" value="PRODUCTION_BATCH" />
          <el-option label="成品批次 (FINISHED_BATCH)" value="FINISHED_BATCH" />
          <el-option label="出货 (SHIPMENT_RECORD)" value="SHIPMENT_RECORD" />
        </el-select>
      </el-form-item>
      <el-form-item label="批次 ID">
        <el-input v-model="form.batchId" placeholder="输入批次 ID" style="width: 280px" />
      </el-form-item>
      <el-form-item label="最大跳数">
        <el-input-number v-model="form.maxDepth" :min="1" :max="10" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :icon="Search" :loading="loading" @click="onQuery">
          溯源
        </el-button>
      </el-form-item>
    </el-form>

    <el-empty v-if="!result && !loading" description="请输入批次 ID 并点击溯源" />

    <template v-if="result">
      <el-card shadow="never" class="summary-card">
        <template #header>
          <strong>批次 {{ result.batchId }}</strong>
          <el-tag size="small" style="margin-left: 8px">{{ result.batchType }}</el-tag>
          <span class="muted" style="margin-left: 12px">
            上游 {{ result.ancestors.length }} / 下游 {{ result.descendants.length }} / 关联边 {{ result.edges.length }}
          </span>
        </template>
      </el-card>

      <el-row :gutter="12" style="margin-top: 12px">
        <el-col :span="12">
          <el-card shadow="never">
            <template #header><strong>上游 (Ancestors — 此批次的原料 / 前置批次)</strong></template>
            <el-empty v-if="result.ancestors.length === 0" description="无上游" :image-size="40" />
            <el-table v-else :data="result.ancestors" border max-height="320">
              <el-table-column prop="ancestorType" label="类型" width="160" />
              <el-table-column prop="ancestorId" label="ID" show-overflow-tooltip />
              <el-table-column prop="depth" label="跳数" width="80">
                <template #default="{ row }">
                  <el-tag size="small">depth {{ row.depth }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never">
            <template #header><strong>下游 (Descendants — 由此批次衍生的批次)</strong></template>
            <el-empty v-if="result.descendants.length === 0" description="无下游" :image-size="40" />
            <el-table v-else :data="result.descendants" border max-height="320">
              <el-table-column prop="descendantType" label="类型" width="160" />
              <el-table-column prop="descendantId" label="ID" show-overflow-tooltip />
              <el-table-column prop="depth" label="跳数" width="80">
                <template #default="{ row }">
                  <el-tag size="small">depth {{ row.depth }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>

      <el-card v-if="result.edges.length > 0" shadow="never" style="margin-top: 12px">
        <template #header><strong>直接关联边 (raw {@code batch_lineage_edges})</strong></template>
        <el-table :data="result.edges" border max-height="320">
          <el-table-column prop="edgeType" label="边类型" width="180" />
          <el-table-column label="源 → 目标" min-width="320">
            <template #default="{ row }">
              <code>{{ row.sourceType }}/{{ row.sourceId }}</code>
              <span class="arrow"> → </span>
              <code>{{ row.targetType }}/{{ row.targetId }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="quantityUsed" label="使用数量" width="140">
            <template #default="{ row }">
              {{ row.quantityUsed ?? '—' }}
              <span class="muted">{{ row.unit ?? '' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="eventTime" label="业务时刻" width="160">
            <template #default="{ row }">
              {{ row.eventTime ? formatTs(row.eventTime) : '—' }}
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { indicatorsApi, type BatchLineageGraph } from '@/api/canvasIndicators'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const form = ref({
  batchType: 'PRODUCTION_BATCH',
  batchId: '',
  maxDepth: 5,
})
const result = ref<BatchLineageGraph | null>(null)
const loading = ref(false)

async function onQuery() {
  if (!form.value.batchId?.trim()) {
    ElMessage.warning('请输入批次 ID')
    return
  }
  loading.value = true
  try {
    const resp = await indicatorsApi.batchLineage(
      props.factoryId,
      form.value.batchType,
      form.value.batchId.trim(),
      form.value.maxDepth,
    )
    result.value = (resp.data ?? null) as BatchLineageGraph | null
  } catch (err) {
    console.error('batch lineage failed', err)
    result.value = null
  } finally {
    loading.value = false
  }
}

function formatTs(ts: string): string {
  try {
    return new Date(ts).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return ts
  }
}
</script>

<style scoped>
.pane {
  padding: 8px 0;
}

.summary-card {
  background: var(--el-fill-color-lighter);
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.arrow {
  margin: 0 6px;
  color: var(--el-color-primary);
  font-weight: bold;
}
</style>
