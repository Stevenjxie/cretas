<!--
  IndicatorLineagePane — Tab 7: 指标依赖图 (DAG via vue-flow)。

  显示某指标的 forward + backward dependencies, 形成 DAG。
  同时显示全工厂 DAG 概览。
-->
<template>
  <div class="pane">
    <el-tabs v-model="subView" type="card">
      <el-tab-pane label="当前指标依赖" name="current">
        <div v-if="currentLineage">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="上游指标 (依赖于本指标)">
              <el-empty v-if="currentLineage.ancestors.length === 0" description="无上游" :image-size="40" />
              <el-tag
                v-for="a in currentLineage.ancestors"
                :key="a.id"
                style="margin: 2px"
                type="primary"
              >
                {{ a.name }} ({{ a.code }})
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="下游指标 (本指标依赖)">
              <el-empty v-if="currentLineage.descendants.length === 0" description="无下游" :image-size="40" />
              <el-tag
                v-for="d in currentLineage.descendants"
                :key="d.id"
                style="margin: 2px"
                type="success"
              >
                {{ d.name }} ({{ d.code }})
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </el-tab-pane>

      <el-tab-pane label="工厂全局 DAG" name="global">
        <div class="dag-toolbar">
          <el-button :icon="Refresh" @click="loadTree">刷新</el-button>
          <span class="muted">
            节点数: {{ flowNodes.length }} / 边数: {{ flowEdges.length }}
          </span>
        </div>
        <div class="dag-container">
          <VueFlow
            v-if="flowNodes.length > 0"
            :nodes="flowNodes"
            :edges="flowEdges"
            :fit-view-on-init="true"
            :min-zoom="0.2"
            :max-zoom="2"
          >
            <Background pattern-color="#ddd" :gap="20" />
            <Controls />
          </VueFlow>
          <el-empty v-else description="暂无 DAG 数据" />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { VueFlow, type Node, type Edge } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { Refresh } from '@element-plus/icons-vue'
import {
  indicatorsApi,
  type IndicatorDetail,
  type IndicatorLineage,
  type IndicatorTreeFlow,
  INDICATOR_CATEGORY_LABELS,
} from '@/api/canvasIndicators'

import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

interface Props {
  factoryId: string
  detail: IndicatorDetail
}
const props = defineProps<Props>()

const subView = ref('current')
const currentLineage = ref<IndicatorLineage | null>(null)
const tree = ref<IndicatorTreeFlow | null>(null)

const categoryColor: Record<string, string> = {
  FACTORY: '#409EFF',
  RESTAURANT: '#E6A23C',
  QUALITY: '#67C23A',
  FINANCE: '#909399',
  INVENTORY: '#7B68EE',
  SALES: '#5cb87a',
  FOOD_SAFETY: '#F56C6C',
  AI: '#9F7AEA',
}

const flowNodes = computed<Node[]>(() => {
  if (!tree.value) return []
  return tree.value.nodes.map((n) => ({
    id: n.id,
    position: { x: n.x, y: n.y },
    data: { label: `${n.label}\n[${INDICATOR_CATEGORY_LABELS[n.category] ?? n.category}]` },
    label: n.label,
    style: {
      background: categoryColor[n.category] ?? '#909399',
      color: '#fff',
      border: props.detail.id === n.id ? '3px solid #ff5500' : '1px solid #ccc',
      borderRadius: '6px',
      padding: '8px',
      fontSize: '12px',
      width: '180px',
    },
  }))
})

const flowEdges = computed<Edge[]>(() => {
  if (!tree.value) return []
  return tree.value.edges.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    label: e.weight !== null && e.weight !== undefined ? `权重 ${e.weight}` : undefined,
    type: 'smoothstep',
    animated: false,
    style: { stroke: '#909399', strokeWidth: 2 },
  }))
})

async function loadLineage() {
  try {
    const resp = await indicatorsApi.lineage(props.factoryId, props.detail.id)
    currentLineage.value = (resp.data ?? null) as IndicatorLineage | null
  } catch (err) {
    console.error('lineage failed', err)
  }
}

async function loadTree() {
  try {
    const resp = await indicatorsApi.tree(props.factoryId)
    tree.value = (resp.data ?? null) as IndicatorTreeFlow | null
  } catch (err) {
    console.error('tree failed', err)
  }
}

watch(() => props.detail.id, () => loadLineage())

onMounted(() => {
  loadLineage()
  loadTree()
})
</script>

<style scoped>
.pane {
  padding: 8px 0;
}

.dag-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.dag-container {
  width: 100%;
  height: 480px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: #fafbfc;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
