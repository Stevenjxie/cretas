<template>
  <div class="workflow-editor" :class="{ 'ai-collapsed': aiCollapsed }">
    <div class="workflow-main">
      <div class="workflow-toolbar">
        <div class="toolbar-status">
          <el-tag v-if="definition" :type="definition.status === 'PUBLISHED' ? 'success' : 'warning'">
            {{ definition.status === 'PUBLISHED' ? '已发布' : '草稿' }} v{{ definition.version }}
          </el-tag>
          <span v-if="dirty" class="dirty-status">● 有未保存改动</span>
          <span v-else-if="definition" class="saved-status">✓ 已保存</span>
          <span class="stage-note">图定义独立保存，暂不改写现有报工运行时</span>
        </div>
        <div class="toolbar-actions">
          <el-button :disabled="!canWrite || !productTypeId" @click="addStandaloneRaw">+ 原料 Cell</el-button>
          <el-button :disabled="!canWrite || history.length === 0" @click="undo">撤销</el-button>
          <el-button :disabled="!canWrite || future.length === 0" @click="redo">重做</el-button>
          <el-button :disabled="!productTypeId || flowNodes.length === 0" @click="handleAutoLayout">自动布局</el-button>
          <el-button :disabled="!productTypeId || flowNodes.length === 0" @click="fitCanvas">适应画布</el-button>
          <el-button
            type="primary"
            :disabled="!canWrite || !productTypeId || !dirty"
            :loading="saving"
            @click="saveDraft"
          >保存草稿</el-button>
          <el-button
            type="success"
            :disabled="!canWrite || !productTypeId || flowNodes.length === 0"
            :loading="publishing"
            @click="publishWorkflow"
          >发布版本</el-button>
        </div>
      </div>

      <div class="canvas-shell" v-loading="loading">
        <el-empty v-if="!productTypeId" description="请先选择产品" :image-size="90" />
        <VueFlow
          v-else
          id="product-process-workflow"
          v-model:nodes="flowNodes"
          v-model:edges="flowEdges"
          class="workflow-canvas"
          :min-zoom="0.35"
          :max-zoom="1.8"
          :pan-on-drag="true"
          :zoom-on-scroll="true"
          :zoom-on-pinch="true"
          :nodes-draggable="canWrite"
          :nodes-connectable="canWrite"
          :snap-to-grid="true"
          :snap-grid="[16, 16]"
          :default-viewport="definition?.viewport || { x: 0, y: 0, zoom: 1 }"
          @connect="onConnect"
          @node-click="onNodeClick"
          @pane-click="selectedNodeId = ''"
          @node-drag-start="onNodeDragStart"
          @node-drag-stop="onNodeDragStop"
          @viewport-change-end="onViewportChangeEnd"
        >
          <Background :gap="16" pattern-color="#dce8f3" />
          <Controls />

          <template #node-material="slotProps">
            <WorkflowMaterialNode
              :kind="slotProps.data.kind"
              :data="slotProps.data"
              :selected="slotProps.selected"
              :can-write="canWrite"
              :raw-material-options="rawMaterialOptions"
              @add-next="openAddProcess(slotProps.id)"
              @select-raw-sku="(skuId) => selectRawSku(slotProps.id, skuId)"
            />
          </template>

          <template #node-process="slotProps">
            <WorkflowProcessNode
              :data="slotProps.data"
              :selected="slotProps.selected"
              :can-write="canWrite"
              :sku-options="skuOptions"
              @update="(patch) => updateProcessData(slotProps.id, patch)"
              @add-input="addInputToProcess(slotProps.id)"
              @add-output="addOutputToProcess(slotProps.id)"
              @select-output-sku="(portId, skuId) => selectOutputSku(slotProps.id, portId, skuId)"
              @change-output-kind="(portId, kind) => changeOutputKind(slotProps.id, portId, kind)"
            />
          </template>
        </VueFlow>

        <div v-if="productTypeId && flowNodes.length === 0 && !loading" class="empty-canvas-action">
          <el-empty description="该产品还没有工序图">
            <el-button v-if="canWrite" type="primary" @click="addStandaloneRaw">添加第一个原料 Cell</el-button>
          </el-empty>
        </div>
      </div>
    </div>

    <aside class="ai-sidebar">
      <button class="ai-collapse-button" type="button" @click="toggleAI">
        {{ aiCollapsed ? 'AI' : '收起 AI' }}
      </button>
      <div v-if="!aiCollapsed" class="ai-content">
        <div class="ai-context">
          <span>当前上下文</span>
          <strong>{{ selectedNodeLabel }}</strong>
        </div>
        <WorkProcessAIChatPanel
          v-if="factoryId"
          :factory-id="factoryId"
          :product-type-id="productTypeId"
          :endpoint="`/${factoryId}/config/v2/ai/chat`"
          module-code="product_work_process_config"
          :title="`AI 助手${selectedNodeId ? ' · 当前 Cell' : ''}`"
          :disabled="!productTypeId || !canWrite"
          :context="selectedNodeContext"
          :quick-prompts="aiQuickPrompts"
          @apply-draft="applyLegacyAIDraft"
        />
      </div>
    </aside>

    <el-dialog v-model="processDialogVisible" title="增加后续工序" width="460px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="上游物料">
          <el-tag>{{ sourceMaterialLabel }}</el-tag>
        </el-form-item>
        <el-form-item label="选择工序" required>
          <el-select v-model="selectedWorkProcessId" filterable placeholder="选择工序" style="width: 100%">
            <el-option
              v-for="process in workProcessOptions"
              :key="process.id"
              :label="`${process.processName} · ${process.unit}${process.outputUnit ? ` → ${process.outputUnit}` : ''}`"
              :value="process.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        title="确认后会根据工序主数据的默认产出类型，自动生成工序 Cell、产出 Cell 和两条连接。"
      />
      <template #footer>
        <el-button @click="processDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedWorkProcessId" @click="confirmAddProcess">确认增加</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="skuDialogVisible" title="现场创建半成品 SKU" width="500px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="半成品名称" required>
          <el-input v-model="skuForm.name" placeholder="例：红烧熟制后猪蹄" />
        </el-form-item>
        <el-form-item label="基本单位" required>
          <el-select v-model="skuForm.unit" style="width: 100%">
            <el-option v-for="unit in unitOptions" :key="unit" :label="unit" :value="unit" />
          </el-select>
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="skuForm.specification" placeholder="可选，如 400g/盒" />
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        title="确认时先检查同名 SKU；发现重复会直接复用已有 SKU，不会重复创建。"
      />
      <template #footer>
        <el-button @click="skuDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingSku" @click="confirmCreateSku">确认并绑定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import {
  MarkerType,
  VueFlow,
  useVueFlow,
  type Connection,
  type Edge,
  type Node,
  type ViewportTransform,
} from '@vue-flow/core';
import { Background } from '@vue-flow/background';
import { Controls } from '@vue-flow/controls';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get, post } from '@/api/request';
import {
  getActiveWorkProcesses,
  getProductWorkProcesses,
  type ProductWorkProcessItem,
  type WorkProcessItem,
} from '@/api/processProduction';
import WorkProcessAIChatPanel from '@/views/system/components/WorkProcessAIChatPanel.vue';
import WorkflowMaterialNode from './WorkflowMaterialNode.vue';
import WorkflowProcessNode from './WorkflowProcessNode.vue';
import {
  getProductProcessWorkflow,
  publishProductProcessWorkflow,
  saveProductProcessWorkflowDraft,
} from './workflowApi';
import {
  autoLayoutWorkflow,
  createProcessBranch,
  createWorkflowFromLegacy,
  snapPosition,
  toPlainWorkflowValue,
  validateWorkflow,
} from './workflowModel';
import type {
  MaterialNodeData,
  ProcessNodeData,
  ProcessPort,
  ProductProcessNodeKind,
  ProductProcessWorkflowDefinition,
  ProductProcessWorkflowEdge,
  ProductProcessWorkflowNode,
} from './types';

import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import '@vue-flow/controls/dist/style.css';

interface SkuOption {
  id: string;
  name: string;
  code?: string;
  unit?: string;
  specification?: string;
  productCategory?: string;
}

interface RawMaterialOption {
  id: string;
  name: string;
  code?: string;
  unit?: string;
}

const props = defineProps<{
  factoryId: string;
  productTypeId: string;
  productName: string;
  canWrite: boolean;
}>();

const { fitView, getViewport, setViewport } = useVueFlow('product-process-workflow');
const definition = ref<ProductProcessWorkflowDefinition | null>(null);
const flowNodes = ref<Node[]>([]);
const flowEdges = ref<Edge[]>([]);
const loading = ref(false);
const saving = ref(false);
const publishing = ref(false);
const dirty = ref(false);
const selectedNodeId = ref('');
const history = ref<ProductProcessWorkflowDefinition[]>([]);
const future = ref<ProductProcessWorkflowDefinition[]>([]);
const dragStartSnapshot = ref<ProductProcessWorkflowDefinition | null>(null);
const workProcessOptions = ref<WorkProcessItem[]>([]);
const skuOptions = ref<SkuOption[]>([]);
const rawMaterialOptions = ref<RawMaterialOption[]>([]);

const processDialogVisible = ref(false);
const processSourceMaterialId = ref('');
const selectedWorkProcessId = ref('');
const skuDialogVisible = ref(false);
const creatingSku = ref(false);
const skuBindingTarget = ref<{ processId: string; portId: string } | null>(null);
const skuForm = ref({ name: '', unit: 'kg', specification: '' });
const unitOptions = ['kg', 'g', '只', '半只', '盒', '袋', '箱', '筐'];

const productTypeId = computed(() => props.productTypeId);
const aiStorageKey = computed(() => `product-process-workflow:ai-collapsed:${props.factoryId}`);
const aiCollapsed = ref(false);
const aiQuickPrompts = [
  '检查当前 Workflow 的 SKU 上下游承接',
  '检查投入与产出单位、数量换算是否完整',
  '检查分流、合流和同 SKU 多投入是否合理',
  '根据我的描述生成工序调整草稿',
];

const selectedNode = computed(() => flowNodes.value.find((node) => node.id === selectedNodeId.value));
const selectedNodeLabel = computed(() => {
  const data = selectedNode.value?.data as Record<string, unknown> | undefined;
  return String(data?.processName || data?.name || (props.productTypeId ? '整个 Workflow' : '未选择产品'));
});
const selectedNodeContext = computed<Record<string, unknown>>(() => ({
  productTypeId: props.productTypeId,
  selectedNodeId: selectedNodeId.value || null,
  selectedNode: selectedNode.value ? serializeFlowNode(selectedNode.value) : null,
  graphSummary: {
    nodes: flowNodes.value.length,
    edges: flowEdges.value.length,
  },
}));
const sourceMaterialLabel = computed(() => {
  const node = flowNodes.value.find((candidate) => candidate.id === processSourceMaterialId.value);
  return String(node?.data?.name || '未选择');
});

onMounted(async () => {
  aiCollapsed.value = localStorage.getItem(aiStorageKey.value) === 'true';
  await Promise.all([loadCatalogs(), loadDefinition()]);
});

watch(() => props.productTypeId, async (next, previous) => {
  if (next === previous) return;
  selectedNodeId.value = '';
  history.value = [];
  future.value = [];
  await loadDefinition();
});

watch(aiStorageKey, () => {
  aiCollapsed.value = localStorage.getItem(aiStorageKey.value) === 'true';
});

async function loadCatalogs(): Promise<void> {
  if (!props.factoryId) return;
  try {
    const [processResponse, productResponse, rawResponse] = await Promise.all([
      getActiveWorkProcesses(props.factoryId),
      get<{ content: SkuOption[] }>(`/${props.factoryId}/product-types`, { params: { page: 1, size: 1000 } }),
      get<RawMaterialOption[]>(`/${props.factoryId}/raw-material-types/active`),
    ]);
    workProcessOptions.value = processResponse.success && Array.isArray(processResponse.data)
      ? processResponse.data
      : [];
    skuOptions.value = productResponse.success && Array.isArray(productResponse.data?.content)
      ? productResponse.data.content
      : [];
    rawMaterialOptions.value = rawResponse.success && Array.isArray(rawResponse.data)
      ? rawResponse.data
      : [];
  } catch (error) {
    console.error('[ProductProcessWorkflow] catalog loading failed', error);
    ElMessage.error('Workflow 所需的工序或 SKU 字典加载失败');
  }
}

async function loadDefinition(): Promise<void> {
  if (!props.factoryId || !props.productTypeId) {
    definition.value = null;
    flowNodes.value = [];
    flowEdges.value = [];
    dirty.value = false;
    return;
  }
  loading.value = true;
  try {
    const response = await getProductProcessWorkflow(props.factoryId, props.productTypeId);
    let nextDefinition = response.success ? response.data : null;
    if (!nextDefinition) {
      const legacyResponse = await getProductWorkProcesses(props.factoryId, props.productTypeId);
      const legacyProcesses = legacyResponse.success && Array.isArray(legacyResponse.data)
        ? legacyResponse.data
        : [];
      nextDefinition = createWorkflowFromLegacy({
        productTypeId: props.productTypeId,
        productName: props.productName || props.productTypeId,
        processes: legacyProcesses,
      });
    }
    hydrate(nextDefinition);
    dirty.value = !nextDefinition.id;
    await nextTick();
    if (nextDefinition.id) {
      await setViewport(nextDefinition.viewport);
    } else if (flowNodes.value.length > 0) {
      await fitCanvas();
    }
  } catch (error) {
    console.error('[ProductProcessWorkflow] definition loading failed', error);
    ElMessage.error('Workflow 图定义加载失败');
  } finally {
    loading.value = false;
  }
}

function hydrate(nextDefinition: ProductProcessWorkflowDefinition): void {
  definition.value = toPlainWorkflowValue(nextDefinition);
  flowNodes.value = nextDefinition.nodes.map((node) => ({
    id: node.id,
    type: node.kind === 'PROCESS' ? 'process' : 'material',
    position: { ...node.position },
    data: { ...toPlainWorkflowValue(node.data), kind: node.kind },
  }));
  flowEdges.value = nextDefinition.edges.map((edge) => ({
    ...edge,
    markerEnd: MarkerType.ArrowClosed,
    style: { stroke: '#1b65a8', strokeWidth: 2 },
  }));
  refreshPortMaterialMetadata();
}

function currentDefinition(): ProductProcessWorkflowDefinition {
  const base = definition.value || {
    schemaVersion: 1 as const,
    status: 'DRAFT' as const,
    version: 1,
    nodes: [],
    edges: [],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
  const viewport = getViewport();
  return {
    ...toPlainWorkflowValue(base),
    status: base.status,
    nodes: flowNodes.value.map(serializeFlowNode),
    edges: flowEdges.value.map(serializeFlowEdge),
    viewport: { x: viewport.x, y: viewport.y, zoom: viewport.zoom },
  };
}

function serializeFlowNode(node: Node): ProductProcessWorkflowNode {
  const clonedData = toPlainWorkflowValue(node.data || {}) as Record<string, unknown>;
  const kind = clonedData.kind as ProductProcessNodeKind;
  delete clonedData.kind;
  return {
    id: node.id,
    kind,
    position: snapPosition(node.position),
    data: clonedData as MaterialNodeData | ProcessNodeData,
  };
}

function serializeFlowEdge(edge: Edge): ProductProcessWorkflowEdge {
  return {
    id: edge.id,
    source: edge.source,
    sourceHandle: edge.sourceHandle || 'output',
    target: edge.target,
    targetHandle: edge.targetHandle || 'input',
  };
}

function remember(snapshot = currentDefinition()): void {
  history.value.push(toPlainWorkflowValue(snapshot));
  if (history.value.length > 50) history.value.shift();
  future.value = [];
}

function mutate(action: () => void): void {
  remember();
  action();
  refreshPortMaterialMetadata();
  dirty.value = true;
}

function undo(): void {
  const previous = history.value.pop();
  if (!previous) return;
  future.value.push(currentDefinition());
  hydrate(previous);
  dirty.value = true;
}

function redo(): void {
  const next = future.value.pop();
  if (!next) return;
  history.value.push(currentDefinition());
  hydrate(next);
  dirty.value = true;
}

function onNodeClick({ node }: { node: Node }): void {
  selectedNodeId.value = node.id;
}

function onNodeDragStart(): void {
  dragStartSnapshot.value = currentDefinition();
}

function onNodeDragStop({ node }: { node: Node }): void {
  if (dragStartSnapshot.value) remember(dragStartSnapshot.value);
  dragStartSnapshot.value = null;
  const target = flowNodes.value.find((candidate) => candidate.id === node.id);
  if (target) target.position = snapPosition(node.position);
  dirty.value = true;
}

function onViewportChangeEnd(viewport: ViewportTransform): void {
  if (!definition.value || loading.value) return;
  definition.value.viewport = { x: viewport.x, y: viewport.y, zoom: viewport.zoom };
  dirty.value = true;
}

function onConnect(connection: Connection): void {
  if (!props.canWrite || !connection.source || !connection.target) return;
  mutate(() => {
    flowEdges.value.push({
      id: `edge:${connection.source}:${connection.sourceHandle || 'output'}:${connection.target}:${connection.targetHandle || 'input'}:${Date.now()}`,
      source: connection.source,
      target: connection.target,
      sourceHandle: connection.sourceHandle || 'output',
      targetHandle: connection.targetHandle || 'input',
      markerEnd: MarkerType.ArrowClosed,
      style: { stroke: '#1b65a8', strokeWidth: 2 },
    });
  });
}

function addStandaloneRaw(): void {
  mutate(() => {
    const count = flowNodes.value.filter((node) => node.data?.kind === 'RAW_MATERIAL').length;
    flowNodes.value.push({
      id: `material:raw:${Date.now()}`,
      type: 'material',
      position: { x: 32, y: 32 + count * 160 },
      data: {
        kind: 'RAW_MATERIAL',
        name: `入口原料 ${count + 1}`,
        skuId: '',
        skuCode: '待绑定原料 SKU',
        bound: false,
      },
    });
  });
}

function openAddProcess(materialNodeId: string): void {
  processSourceMaterialId.value = materialNodeId;
  selectedWorkProcessId.value = '';
  processDialogVisible.value = true;
}

function confirmAddProcess(): void {
  const source = flowNodes.value.find((node) => node.id === processSourceMaterialId.value);
  const workProcess = workProcessOptions.value.find((item) => item.id === selectedWorkProcessId.value);
  if (!source || !workProcess) return;
  mutate(() => {
    const branch = createProcessBranch({
      source: serializeFlowNode(source),
      workProcess,
      productTypeId: props.productTypeId,
      productName: props.productName || props.productTypeId,
      timestamp: Date.now(),
    });
    flowNodes.value.push(
      {
        id: branch.processNode.id,
        type: 'process',
        position: branch.processNode.position,
        data: {
          ...toPlainWorkflowValue(branch.processNode.data),
          kind: branch.processNode.kind,
        },
      },
      {
        id: branch.outputNode.id,
        type: 'material',
        position: branch.outputNode.position,
        data: {
          ...toPlainWorkflowValue(branch.outputNode.data),
          kind: branch.outputNode.kind,
        },
      },
    );
    flowEdges.value.push(
      ...branch.edges.map((edge) => ({
        ...edge,
        markerEnd: MarkerType.ArrowClosed,
        style: { stroke: '#1b65a8', strokeWidth: 2 },
      })),
    );
  });
  processDialogVisible.value = false;
}

function addInputToProcess(processId: string): void {
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process) return;
  mutate(() => {
    const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
    const inputCount = data.ports.filter((port) => port.direction === 'INPUT').length;
    const timestamp = Date.now();
    const materialId = `material:input:${timestamp}`;
    const portId = `input:${timestamp}`;
    flowNodes.value.push({
      id: materialId,
      type: 'material',
      position: snapPosition({ x: process.position.x - 240, y: process.position.y + inputCount * 160 }),
      data: {
        kind: 'RAW_MATERIAL', name: `追加投入 ${inputCount + 1}`, skuId: '',
        skuCode: '待绑定原料 SKU', bound: false, baseUnit: data.inputUnit,
      },
    });
    data.ports = [
      ...data.ports,
      { id: portId, direction: 'INPUT', materialNodeId: materialId, unit: data.inputUnit, ordinal: inputCount },
    ];
    flowEdges.value.push(flowEdge(materialId, 'output', processId, portId));
  });
}

function addOutputToProcess(processId: string): void {
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process) return;
  mutate(() => {
    const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
    const outputCount = data.ports.filter((port) => port.direction === 'OUTPUT').length;
    const timestamp = Date.now();
    const materialId = `material:output:${timestamp}`;
    const portId = `output:${timestamp}`;
    flowNodes.value.push({
      id: materialId,
      type: 'material',
      position: snapPosition({ x: process.position.x + 480, y: process.position.y + outputCount * 160 }),
      data: {
        kind: 'SEMI_FINISHED', name: `产出半成品 ${outputCount + 1}`, skuId: '',
        skuCode: '待选择或现场创建 SKU', bound: false, baseUnit: data.outputUnit,
      },
    });
    data.ports = [
      ...data.ports,
      {
        id: portId, direction: 'OUTPUT', materialNodeId: materialId, materialKind: 'SEMI_FINISHED',
        unit: data.outputUnit, ordinal: outputCount,
      },
    ];
    flowEdges.value.push(flowEdge(processId, portId, materialId, 'input'));
  });
}

function updateProcessData(processId: string, patch: Partial<ProcessNodeData>): void {
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process) return;
  mutate(() => {
    process.data = { ...process.data, ...toPlainWorkflowValue(patch), kind: 'PROCESS' };
  });
}

function selectRawSku(materialNodeId: string, skuId: string): void {
  const material = flowNodes.value.find((node) => node.id === materialNodeId);
  const option = rawMaterialOptions.value.find((item) => item.id === skuId);
  if (!material || !option) return;
  mutate(() => {
    material.data = {
      ...material.data,
      name: option.name,
      skuId: option.id,
      skuCode: option.code || option.id,
      baseUnit: option.unit || material.data?.baseUnit || 'kg',
      bound: true,
    };
  });
}

function selectOutputSku(processId: string, portId: string, skuId: string): void {
  if (skuId === '__CREATE__') {
    const process = flowNodes.value.find((node) => node.id === processId);
    const data = process?.data as ProcessNodeData | undefined;
    const port = data?.ports.find((candidate) => candidate.id === portId);
    skuBindingTarget.value = { processId, portId };
    skuForm.value = {
      name: port?.materialName || `${data?.processName || '工序'}后半成品`,
      unit: port?.unit || data?.outputUnit || 'kg',
      specification: '',
    };
    skuDialogVisible.value = true;
    return;
  }
  const option = skuOptions.value.find((item) => item.id === skuId);
  if (option) bindOutputSku(processId, portId, option);
}

function bindOutputSku(processId: string, portId: string, option: SkuOption): void {
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process) return;
  mutate(() => {
    const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
    const port = data.ports.find((candidate) => candidate.id === portId);
    const material = flowNodes.value.find((node) => node.id === port?.materialNodeId);
    if (!port || !material) return;
    const kind: Exclude<ProductProcessNodeKind, 'RAW_MATERIAL' | 'PROCESS'> =
      option.productCategory === 'FINISHED_PRODUCT' ? 'FINISHED_GOOD' : 'SEMI_FINISHED';
    Object.assign(port, {
      skuId: option.id,
      materialName: option.name,
      materialKind: kind,
      unit: option.unit || port.unit,
    });
    material.data = {
      ...material.data,
      kind,
      name: option.name,
      skuId: option.id,
      skuCode: option.code || option.id,
      specification: option.specification,
      baseUnit: option.unit || port.unit,
      bound: true,
    };
  });
}

function changeOutputKind(
  processId: string,
  portId: string,
  kind: 'SEMI_FINISHED' | 'FINISHED_GOOD',
): void {
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process) return;
  mutate(() => {
    const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
    const port = data.ports.find((candidate) => candidate.id === portId);
    const material = flowNodes.value.find((node) => node.id === port?.materialNodeId);
    if (port) port.materialKind = kind;
    if (material) material.data = { ...material.data, kind };
  });
}

async function confirmCreateSku(): Promise<void> {
  const name = skuForm.value.name.trim();
  const unit = skuForm.value.unit;
  if (!name || !unit || !skuBindingTarget.value) {
    ElMessage.warning('请填写半成品名称和单位');
    return;
  }
  const duplicate = skuOptions.value.find((item) => item.name.trim() === name);
  if (duplicate) {
    ElMessage.info(`发现同名 SKU，已复用 ${duplicate.code || duplicate.id}`);
    bindOutputSku(skuBindingTarget.value.processId, skuBindingTarget.value.portId, duplicate);
    skuDialogVisible.value = false;
    return;
  }
  creatingSku.value = true;
  try {
    const response = await post<SkuOption>(`/${props.factoryId}/product-types`, {
      name,
      unit,
      specification: skuForm.value.specification.trim() || null,
      productCategory: 'SEMI_FINISHED',
      isActive: true,
      notes: '在产品工序 Workflow 中现场创建',
    });
    if (!response.success || !response.data) {
      ElMessage.error(response.message || '半成品 SKU 创建失败');
      return;
    }
    skuOptions.value.unshift(response.data);
    bindOutputSku(skuBindingTarget.value.processId, skuBindingTarget.value.portId, response.data);
    skuDialogVisible.value = false;
    ElMessage.success('半成品 SKU 已创建并绑定');
  } catch (error) {
    console.error('[ProductProcessWorkflow] create sku failed', error);
    ElMessage.error('半成品 SKU 创建失败');
  } finally {
    creatingSku.value = false;
  }
}

function refreshPortMaterialMetadata(): void {
  const materialById = new Map(flowNodes.value
    .filter((node) => node.data?.kind !== 'PROCESS')
    .map((node) => [node.id, node]));
  flowNodes.value.forEach((node) => {
    if (node.data?.kind !== 'PROCESS') return;
    const data = node.data as ProcessNodeData & { kind: 'PROCESS' };
    data.ports = data.ports.map((port) => {
      const material = port.materialNodeId ? materialById.get(port.materialNodeId) : undefined;
      return {
        ...port,
        materialName: String(material?.data?.name || port.materialName || ''),
        skuId: String(material?.data?.skuId || port.skuId || ''),
        materialKind: (material?.data?.kind || port.materialKind) as ProcessPort['materialKind'],
        unit: String(material?.data?.baseUnit || port.unit || 'kg'),
      };
    });
  });
}

function flowEdge(source: string, sourceHandle: string, target: string, targetHandle: string): Edge {
  return {
    id: `edge:${source}:${sourceHandle}:${target}:${targetHandle}`,
    source,
    sourceHandle,
    target,
    targetHandle,
    markerEnd: MarkerType.ArrowClosed,
    style: { stroke: '#1b65a8', strokeWidth: 2 },
  };
}

async function handleAutoLayout(): Promise<void> {
  remember();
  const laidOut = autoLayoutWorkflow(currentDefinition());
  hydrate(laidOut);
  dirty.value = true;
  await nextTick();
  await fitCanvas();
}

async function fitCanvas(): Promise<void> {
  await nextTick();
  await fitView({ padding: 0.16, duration: 300, maxZoom: 1.1 });
}

async function saveDraft(): Promise<boolean> {
  if (!props.factoryId || !props.productTypeId || !props.canWrite) return false;
  const nextDefinition = currentDefinition();
  const errors = validateWorkflow(nextDefinition, 'draft');
  if (errors.length > 0) {
    ElMessage.error(errors[0].message);
    return false;
  }
  saving.value = true;
  try {
    const response = await saveProductProcessWorkflowDraft(
      props.factoryId,
      props.productTypeId,
      nextDefinition,
    );
    if (!response.success || !response.data) {
      ElMessage.error(response.message || 'Workflow 草稿保存失败');
      return false;
    }
    hydrate(response.data);
    dirty.value = false;
    history.value = [];
    future.value = [];
    ElMessage.success('Workflow 草稿已独立保存');
    return true;
  } catch (error) {
    console.error('[ProductProcessWorkflow] save failed', error);
    return false;
  } finally {
    saving.value = false;
  }
}

async function publishWorkflow(): Promise<void> {
  if (dirty.value && !(await saveDraft())) return;
  if (!definition.value?.lockVersion && definition.value?.lockVersion !== 0) {
    ElMessage.warning('请先保存草稿');
    return;
  }
  const errors = validateWorkflow(currentDefinition(), 'publish');
  if (errors.length > 0) {
    ElMessageBox.alert(errors.slice(0, 8).map((error) => `• ${error.message}`).join('\n'), '发布前检查未通过', {
      type: 'warning',
    });
    return;
  }
  try {
    await ElMessageBox.confirm(
      '发布后会生成可审计的 Workflow 图版本。当前阶段不会自动改写生产任务或报工链，确认发布？',
      '发布 Workflow',
      { type: 'warning', confirmButtonText: '确认发布' },
    );
  } catch {
    return;
  }
  publishing.value = true;
  try {
    const response = await publishProductProcessWorkflow(
      props.factoryId,
      props.productTypeId,
      definition.value.lockVersion,
    );
    if (!response.success || !response.data) {
      ElMessage.error(response.message || 'Workflow 发布失败');
      return;
    }
    hydrate(response.data);
    dirty.value = false;
    ElMessage.success('Workflow 版本已发布');
  } catch (error) {
    console.error('[ProductProcessWorkflow] publish failed', error);
  } finally {
    publishing.value = false;
  }
}

async function applyLegacyAIDraft(payload: Record<string, unknown>): Promise<void> {
  const draft = Array.isArray(payload.draft) ? payload.draft as Array<Record<string, unknown>> : [];
  if (draft.length === 0) {
    ElMessage.warning('AI 草稿没有可应用的工序');
    return;
  }
  const processes = draft.map((step, index): ProductWorkProcessItem => ({
    id: Number(step.productWorkProcessId || index + 1),
    productTypeId: props.productTypeId,
    workProcessId: String(step.workProcessId || `ai-${index}`),
    processOrder: Number(step.processOrder || index + 1),
    unitOverride: step.unit ? String(step.unit) : null,
    estimatedMinutesOverride: null,
    processName: String(step.processName || `工序 ${index + 1}`),
    processCategory: String(step.processCategory || ''),
    defaultUnit: String(step.unit || 'kg'),
    defaultEstimatedMinutes: null,
    reportingRequired: true,
  }));
  const proposed = createWorkflowFromLegacy({
    productTypeId: props.productTypeId,
    productName: props.productName,
    processes,
  });
  try {
    await ElMessageBox.confirm(
      `AI 建议会生成 ${proposed.nodes.length} 个 Cell、${proposed.edges.length} 条连接，并替换当前画布草稿。是否应用？`,
      '应用 AI 草稿',
      { type: 'warning', confirmButtonText: '应用到草稿' },
    );
  } catch {
    return;
  }
  remember();
  hydrate({ ...proposed, id: definition.value?.id, lockVersion: definition.value?.lockVersion });
  dirty.value = true;
  await fitCanvas();
}

function toggleAI(): void {
  aiCollapsed.value = !aiCollapsed.value;
  localStorage.setItem(aiStorageKey.value, String(aiCollapsed.value));
  window.setTimeout(() => fitCanvas(), 220);
}
</script>

<style scoped>
.workflow-editor { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 12px; min-height: 720px; }
.workflow-editor.ai-collapsed { grid-template-columns: minmax(0, 1fr) 44px; }
.workflow-main { min-width: 0; display: flex; flex-direction: column; }
.workflow-toolbar {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  min-height: 52px; padding: 8px 12px; border: 1px solid #edf2f7; border-radius: 10px 10px 0 0; background: #fff;
}
.toolbar-status, .toolbar-actions { display: flex; align-items: center; gap: 8px; }
.toolbar-actions { flex-wrap: wrap; justify-content: flex-end; }
.dirty-status { color: #e6a23c; font-size: 12px; }
.saved-status { color: #67c23a; font-size: 12px; }
.stage-note { color: #7a8599; font-size: 11px; }
.canvas-shell {
  position: relative; flex: 1; min-height: 660px; overflow: hidden;
  border: 1px solid #dce8f3; border-top: none; border-radius: 0 0 10px 10px; background: #fbfdff;
}
.workflow-canvas { width: 100%; height: 100%; min-height: 660px; }
.empty-canvas-action { position: absolute; inset: 0; display: grid; place-items: center; pointer-events: none; }
.empty-canvas-action :deep(.el-button) { pointer-events: auto; }
.ai-sidebar {
  position: relative; min-width: 0; overflow: hidden; border: 1px solid #edf2f7;
  border-radius: 10px; background: #fff; transition: width 0.2s ease;
}
.ai-collapse-button {
  width: 100%; min-height: 44px; border: 0; border-bottom: 1px solid #edf2f7;
  color: #1b65a8; background: #f4f9ff; cursor: pointer; font-weight: 650;
}
.ai-content { padding: 12px; }
.ai-context { display: flex; flex-direction: column; gap: 3px; padding: 8px 10px; border-radius: 8px; background: #f7f9fc; }
.ai-context span { color: #7a8599; font-size: 11px; }
.ai-context strong { overflow: hidden; color: #344054; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.ai-content :deep(.work-process-ai-chat-panel) { min-height: 560px; margin-top: 8px; }
.workflow-editor.ai-collapsed .ai-collapse-button { writing-mode: vertical-rl; min-height: 120px; padding: 12px 0; }
:deep(.vue-flow__edge-path) { stroke-linecap: round; }
:deep(.vue-flow__node) { border: 0; background: transparent; }
</style>
