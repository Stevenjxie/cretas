<template>
  <div class="workflow-route-preview" data-testid="workflow-route-preview">
    <div class="preview-legend" aria-label="Cell 类型图例">
      <span v-for="item in legend" :key="item.kind" :class="['legend-item', `is-${kindClass(item.kind)}`]">
        <i />{{ item.label }}
      </span>
    </div>

    <div v-if="layout.nodes.length === 0" class="preview-empty">
      该版本暂无可预览的 Cell 数据
    </div>
    <div v-else class="preview-scroll">
      <div
        class="preview-canvas"
        :style="{ width: `${layout.width}px`, height: `${layout.height}px` }"
      >
        <svg
          class="preview-links"
          :width="layout.width"
          :height="layout.height"
          aria-hidden="true"
        >
          <defs>
            <marker id="workflow-preview-arrow" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto">
              <path d="M0,0 L7,3.5 L0,7 Z" fill="#7a91ad" />
            </marker>
          </defs>
          <path
            v-for="edge in layout.edges"
            :key="edge.id"
            :d="edge.path"
            class="preview-edge"
            marker-end="url(#workflow-preview-arrow)"
          />
        </svg>

        <div
          v-for="node in layout.nodes"
          :key="node.id"
          :class="['preview-cell', `is-${kindClass(node.kind)}`]"
          :style="{
            left: `${node.x}px`,
            top: `${node.y}px`,
            width: `${node.width}px`,
            height: `${node.height}px`,
          }"
          :aria-label="`${kindLabel(node.kind)} Cell：${node.label}`"
        >
          <span class="cell-kind">{{ kindGlyph(node.kind) }}</span>
          <div class="cell-copy">
            <span class="cell-type">{{ kindLabel(node.kind) }} Cell</span>
            <strong :title="node.label">{{ node.label }}</strong>
            <small v-if="node.unit">{{ node.unit }}</small>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type {
  WorkflowResolutionPreviewEdge,
  WorkflowResolutionPreviewNode,
} from '@/api/productionPlan';

type PreviewKind = WorkflowResolutionPreviewNode['kind'];

const props = withDefaults(defineProps<{
  nodes?: WorkflowResolutionPreviewNode[];
  edges?: WorkflowResolutionPreviewEdge[];
}>(), {
  nodes: () => [],
  edges: () => [],
});

const legend: Array<{ kind: PreviewKind; label: string }> = [
  { kind: 'RAW_MATERIAL', label: '原料' },
  { kind: 'SEMI_FINISHED', label: '半成品' },
  { kind: 'PROCESS', label: '工序' },
  { kind: 'FINISHED_GOOD', label: '成品' },
];

const CELL_HEIGHT = 72;
const MATERIAL_WIDTH = 154;
const PROCESS_WIDTH = 184;
const LAYER_GAP = 72;
const ROW_GAP = 26;
const PADDING = 24;

interface LayoutNode extends WorkflowResolutionPreviewNode {
  x: number;
  y: number;
  width: number;
  height: number;
}

interface LayoutEdge {
  id: string;
  path: string;
}

const layout = computed<{ nodes: LayoutNode[]; edges: LayoutEdge[]; width: number; height: number }>(() => {
  const sourceNodes = props.nodes.filter((node) => node.id && node.kind);
  const nodeById = new Map(sourceNodes.map((node) => [node.id, node]));
  const validEdges = props.edges.filter(
    (edge) => nodeById.has(edge.source) && nodeById.has(edge.target),
  );
  const indegree = new Map(sourceNodes.map((node) => [node.id, 0]));
  const outgoing = new Map<string, string[]>();
  validEdges.forEach((edge) => {
    indegree.set(edge.target, (indegree.get(edge.target) || 0) + 1);
    outgoing.set(edge.source, [...(outgoing.get(edge.source) || []), edge.target]);
  });

  const depth = new Map(sourceNodes.map((node) => [node.id, 0]));
  const queue = sourceNodes.filter((node) => indegree.get(node.id) === 0).map((node) => node.id);
  while (queue.length > 0) {
    const source = queue.shift() as string;
    (outgoing.get(source) || []).forEach((target) => {
      depth.set(target, Math.max(depth.get(target) || 0, (depth.get(source) || 0) + 1));
      const remaining = (indegree.get(target) || 0) - 1;
      indegree.set(target, remaining);
      if (remaining === 0) queue.push(target);
    });
  }

  const layers = new Map<number, WorkflowResolutionPreviewNode[]>();
  sourceNodes.forEach((node) => {
    const nodeDepth = depth.get(node.id) || 0;
    layers.set(nodeDepth, [...(layers.get(nodeDepth) || []), node]);
  });
  const orderedLayers = [...layers.entries()].sort(([left], [right]) => left - right);
  const maxRows = Math.max(1, ...orderedLayers.map(([, nodes]) => nodes.length));
  const contentHeight = maxRows * CELL_HEIGHT + Math.max(0, maxRows - 1) * ROW_GAP;
  const positioned: LayoutNode[] = [];
  let x = PADDING;
  orderedLayers.forEach(([, nodes]) => {
    const layerWidth = nodes.some((node) => node.kind === 'PROCESS') ? PROCESS_WIDTH : MATERIAL_WIDTH;
    const layerHeight = nodes.length * CELL_HEIGHT + Math.max(0, nodes.length - 1) * ROW_GAP;
    let y = PADDING + (contentHeight - layerHeight) / 2;
    nodes.forEach((node) => {
      positioned.push({ ...node, x, y, width: layerWidth, height: CELL_HEIGHT });
      y += CELL_HEIGHT + ROW_GAP;
    });
    x += layerWidth + LAYER_GAP;
  });

  const positionedById = new Map(positioned.map((node) => [node.id, node]));
  const edgePaths: LayoutEdge[] = validEdges.flatMap((edge, index) => {
    const source = positionedById.get(edge.source);
    const target = positionedById.get(edge.target);
    if (!source || !target) return [];
    const startX = source.x + source.width;
    const startY = source.y + source.height / 2;
    const endX = target.x;
    const endY = target.y + target.height / 2;
    const bend = Math.max(24, (endX - startX) * 0.48);
    return [{
      id: edge.id || `${edge.source}-${edge.target}-${index}`,
      path: `M ${startX} ${startY} C ${startX + bend} ${startY}, ${endX - bend} ${endY}, ${endX} ${endY}`,
    }];
  });
  return {
    nodes: positioned,
    edges: edgePaths,
    width: Math.max(420, x - LAYER_GAP + PADDING),
    height: contentHeight + PADDING * 2,
  };
});

function kindClass(kind: PreviewKind): string {
  return kind.toLowerCase().replace('_', '-');
}

function kindLabel(kind: PreviewKind): string {
  return ({
    RAW_MATERIAL: '原料',
    PROCESS: '工序',
    SEMI_FINISHED: '半成品',
    FINISHED_GOOD: '成品',
  } as Record<PreviewKind, string>)[kind];
}

function kindGlyph(kind: PreviewKind): string {
  return ({
    RAW_MATERIAL: '原',
    PROCESS: '序',
    SEMI_FINISHED: '半',
    FINISHED_GOOD: '成',
  } as Record<PreviewKind, string>)[kind];
}
</script>

<style scoped>
.workflow-route-preview { color: #243447; }
.preview-legend { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; font-size: 12px; color: #667085; }
.legend-item { display: inline-flex; align-items: center; gap: 5px; }
.legend-item i { width: 8px; height: 8px; border-radius: 999px; background: currentColor; }
.legend-item.is-raw-material { color: #60758d; }
.legend-item.is-semi-finished { color: #3d9b5f; }
.legend-item.is-process { color: #3278d5; }
.legend-item.is-finished-good { color: #8155cb; }
.preview-scroll { max-width: min(760px, calc(100vw - 96px)); max-height: 430px; overflow: auto; border: 1px solid #e5eaf0; border-radius: 10px; background: #f8fafc; }
.preview-canvas { position: relative; min-width: 100%; background-image: radial-gradient(#dbe3ed 0.75px, transparent 0.75px); background-size: 16px 16px; }
.preview-links { position: absolute; inset: 0; overflow: visible; pointer-events: none; }
.preview-edge { fill: none; stroke: #7a91ad; stroke-width: 2; opacity: 0.9; }
.preview-cell { position: absolute; display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px; border: 1px solid; border-left-width: 4px; border-radius: 10px; background: #fff; box-shadow: 0 5px 14px rgba(30, 55, 82, 0.08); box-sizing: border-box; }
.preview-cell.is-raw-material { border-color: #a8b6c5; border-left-color: #60758d; }
.preview-cell.is-semi-finished { border-color: #a8d7b8; border-left-color: #3d9b5f; }
.preview-cell.is-process { border-color: #a9c9f5; border-left-color: #3278d5; }
.preview-cell.is-finished-good { border-color: #c8b5ea; border-left-color: #8155cb; }
.cell-kind { flex: 0 0 25px; width: 25px; height: 25px; display: grid; place-items: center; border-radius: 7px; background: #edf3fa; color: #49637f; font-size: 12px; font-weight: 700; }
.is-process .cell-kind { background: #eaf3ff; color: #236bc4; }
.is-semi-finished .cell-kind { background: #edf9f0; color: #2e8a4e; }
.is-finished-good .cell-kind { background: #f3edfc; color: #7042b9; }
.cell-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; }
.cell-type { color: #8a96a6; font-size: 10px; line-height: 1.2; }
.cell-copy strong { overflow: hidden; color: #1f2d3d; font-size: 12px; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.cell-copy small { color: #718096; font-size: 10px; }
.preview-empty { display: grid; min-height: 130px; place-items: center; border: 1px dashed #cfd8e3; border-radius: 10px; color: #8a96a6; background: #f8fafc; }
</style>
