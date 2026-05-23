<template>
  <div class="tree-viewer">
    <div class="toolbar">
      <el-input
        v-model="searchKey"
        placeholder="搜索指标名/编码"
        clearable
        size="small"
        :prefix-icon="Search"
        class="search-input"
      />
      <el-button :icon="Refresh" size="small" @click="loadTree">刷新</el-button>
      <el-button :icon="Expand" size="small" @click="expandAll">展开</el-button>
      <el-button :icon="Fold" size="small" @click="collapseAll">折叠</el-button>
    </div>

    <div v-if="loading" class="loading-block">
      <el-skeleton :rows="8" animated />
    </div>

    <el-empty
      v-else-if="treeData.length === 0"
      :image-size="80"
      description="暂无指标树数据"
    />

    <div v-else class="tree-wrapper">
      <el-tree
        ref="treeRef"
        :data="treeData"
        :props="{ children: 'children', label: 'name' }"
        :filter-node-method="filterNode"
        :default-expand-all="false"
        :expand-on-click-node="false"
        node-key="nodeId"
        @node-click="onNodeClick"
      >
        <template #default="{ data }">
          <div class="tree-node" :class="alertClass(data.alertLevel)">
            <span class="node-name">{{ data.name }}</span>
            <span class="node-code">{{ data.code }}</span>
            <el-tag v-if="data.weight" size="small" type="info" effect="plain">
              权重 {{ data.weight }}
            </el-tag>
            <span v-if="hasValue(data)" class="node-value" :class="`value-${alertLevelClass(data.alertLevel)}`">
              {{ formatValue(data.lastValue) }}{{ data.unit || '' }}
            </span>
            <el-tag
              v-if="data.alertLevel"
              :type="alertTagType(data.alertLevel)"
              size="small"
              effect="dark"
              class="node-alert-tag"
            >
              {{ alertText(data.alertLevel) }}
            </el-tag>
            <el-button
              link
              type="primary"
              size="small"
              class="node-detail-btn"
              @click.stop="emit('select', data.code)"
            >
              详情
            </el-button>
          </div>
        </template>
      </el-tree>
    </div>

    <!-- 树统计信息 — Sprint 11 BI deep-audit Finding-3 P2 fix:
         old labels (正常/关注/告警) misleading because tree API doesn't carry alertLevel.
         use honest 已计算/未计算 based on lastValue presence. -->
    <div v-if="!loading && treeData.length > 0" class="tree-stats">
      <span>共 {{ totalNodes }} 个节点 / {{ maxDepth }} 层深度</span>
      <span class="stats-divider">|</span>
      <span>
        <span class="dot dot-green" /> 已计算 {{ valueStats.computed }}
        <span class="dot dot-yellow" /> 待配置 {{ valueStats.uncomputed }}
      </span>
      <el-tooltip
        content="树视图节点状态显示数据可用性 (已计算 vs 待配置). 红黄绿告警状态见'指标列表'视图各 card."
        placement="top"
      >
        <el-icon class="stats-hint"><InfoFilled /></el-icon>
      </el-tooltip>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * IndicatorTreeViewer — 指标树形展示 (递归).
 *
 * 用 Element Plus el-tree 渲染. 父子关系由后端 IndicatorTreeNodeResponse.children 提供.
 * 点击节点 emit 'select' 到父组件 (Dashboard).
 */
import { ref, computed, watch, onMounted, nextTick } from 'vue';
import { ElTree } from 'element-plus';
import { Search, Refresh, Expand, Fold, InfoFilled } from '@element-plus/icons-vue';
import { getIndicatorTree, type IndicatorTreeNodeResponse } from '@/api/indicator';

interface Props {
  factoryId: string;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  (e: 'select', code: string): void;
}>();

const treeData = ref<IndicatorTreeNodeResponse[]>([]);
const loading = ref(false);
const searchKey = ref('');
const treeRef = ref<InstanceType<typeof ElTree> | null>(null);

async function loadTree() {
  loading.value = true;
  try {
    treeData.value = await getIndicatorTree(props.factoryId);
  } catch (e) {
    console.warn('[IndicatorTreeViewer] load failed:', e);
    treeData.value = [];
  } finally {
    loading.value = false;
  }
}

watch(searchKey, (val) => {
  treeRef.value?.filter(val);
});

function filterNode(value: string, data: Record<string, unknown>): boolean {
  if (!value) return true;
  const v = value.toLowerCase();
  const name = String(data.name || '').toLowerCase();
  const code = String(data.code || '').toLowerCase();
  return name.includes(v) || code.includes(v);
}

function onNodeClick(data: IndicatorTreeNodeResponse) {
  emit('select', data.code);
}

function expandAll() {
  // el-tree 展开/折叠所有 — 需要遍历 collect nodeIds 然后逐个 setExpanded
  if (!treeRef.value) return;
  const allIds: string[] = [];
  const walk = (nodes: IndicatorTreeNodeResponse[]) => {
    nodes.forEach(n => {
      allIds.push(n.nodeId);
      if (n.children?.length) walk(n.children);
    });
  };
  walk(treeData.value);
  nextTick(() => {
    allIds.forEach(id => {
      const node = treeRef.value?.getNode(id);
      if (node && !node.expanded) node.expand();
    });
  });
}

function collapseAll() {
  if (!treeRef.value) return;
  const walk = (nodes: IndicatorTreeNodeResponse[]) => {
    nodes.forEach(n => {
      const node = treeRef.value?.getNode(n.nodeId);
      if (node?.expanded) node.collapse();
      if (n.children?.length) walk(n.children);
    });
  };
  walk(treeData.value);
}

// ============ 统计信息 ============

const flatNodes = computed((): IndicatorTreeNodeResponse[] => {
  const out: IndicatorTreeNodeResponse[] = [];
  const walk = (nodes: IndicatorTreeNodeResponse[]) => {
    nodes.forEach(n => {
      out.push(n);
      if (n.children?.length) walk(n.children);
    });
  };
  walk(treeData.value);
  return out;
});

const totalNodes = computed(() => flatNodes.value.length);
const maxDepth = computed(() => {
  if (flatNodes.value.length === 0) return 0;
  return Math.max(...flatNodes.value.map(n => n.depth)) + 1;
});

const alertCounts = computed(() => {
  const counts = { green: 0, yellow: 0, red: 0 };
  flatNodes.value.forEach(n => {
    if (n.alertLevel === 'GREEN') counts.green++;
    else if (n.alertLevel === 'YELLOW') counts.yellow++;
    else if (n.alertLevel === 'RED') counts.red++;
  });
  return counts;
});

// Sprint 11 BI deep-audit Finding-3 P2 fix: tree node alertLevel is always null,
// so use hasValue (lastValue presence) for honest 已计算/待配置 stats.
const valueStats = computed(() => {
  let computedCount = 0;
  let uncomputed = 0;
  flatNodes.value.forEach(n => {
    if (hasValue(n)) computedCount++;
    else uncomputed++;
  });
  return { computed: computedCount, uncomputed };
});

// ============ helpers ============

function hasValue(d: IndicatorTreeNodeResponse): boolean {
  return d.lastValue !== null && d.lastValue !== undefined;
}

function formatValue(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—';
  if (Math.abs(v) < 1000) {
    return Number.isInteger(v) ? v.toString() : v.toFixed(2);
  }
  return v.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

function alertLevelClass(level: string | null | undefined): string {
  if (!level) return 'neutral';
  return level.toLowerCase();
}

function alertClass(level: string | null | undefined): string {
  return `node-${alertLevelClass(level)}`;
}

function alertTagType(level: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (level) {
    case 'GREEN': return 'success';
    case 'YELLOW': return 'warning';
    case 'RED': return 'danger';
    default: return 'info';
  }
}

function alertText(level: string): string {
  return level === 'GREEN' ? '正常' : level === 'YELLOW' ? '关注' : level === 'RED' ? '告警' : level;
}

onMounted(() => {
  loadTree();
});

defineExpose({ loadTree });
</script>

<style scoped>
.tree-viewer {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.search-input {
  flex: 1;
  max-width: 280px;
}

.tree-wrapper {
  max-height: 560px;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 8px;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 2px 0;
}

.node-name {
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.node-code {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  font-family: var(--el-font-family-monospace, monospace);
  background: var(--el-fill-color-lighter);
  padding: 1px 4px;
  border-radius: 2px;
}

.node-value {
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  margin-left: auto;
}

.value-green { color: var(--el-color-success); }
.value-yellow { color: var(--el-color-warning); }
.value-red { color: var(--el-color-danger); }
.value-neutral { color: var(--el-text-color-regular); }

.node-alert-tag {
  flex-shrink: 0;
}

.node-detail-btn {
  flex-shrink: 0;
  padding: 0 4px;
}

.loading-block {
  padding: 16px;
}

.tree-stats {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.stats-hint {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  cursor: help;
}

.stats-divider {
  color: var(--el-border-color);
}

.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin: 0 4px 0 8px;
  vertical-align: middle;
}

.dot-green { background: var(--el-color-success); }
.dot-yellow { background: var(--el-color-warning); }
.dot-red { background: var(--el-color-danger); }
</style>
