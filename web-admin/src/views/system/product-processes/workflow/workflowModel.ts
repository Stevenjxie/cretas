import type {
  LegacyProductWorkProcess,
  MaterialNodeData,
  ProcessBranchInput,
  ProcessNodeData,
  ProductProcessNodeKind,
  ProductProcessWorkflowDefinition,
  ProductProcessWorkflowEdge,
  ProductProcessWorkflowNode,
  WorkflowPatch,
  WorkflowPosition,
  WorkflowValidationError,
} from './types';

const GRID_SIZE = 16;
const LAYER_GAP = 240;
const BRANCH_GAP = 160;
const CANVAS_ORIGIN = 32;

/**
 * Workflow 合同只包含 JSON 数据。通过 JSON 边界去掉 Vue reactive Proxy，
 * 保证撤销快照、API 序列化和 AI patch 都能安全复制。
 */
export function toPlainWorkflowValue<T>(value: T): T {
  if (value === undefined) return value;
  return JSON.parse(JSON.stringify(value)) as T;
}

export function snapPosition(position: WorkflowPosition): WorkflowPosition {
  return {
    x: Math.round(position.x / GRID_SIZE) * GRID_SIZE,
    y: Math.round(position.y / GRID_SIZE) * GRID_SIZE,
  };
}

export function createProcessBranch(input: ProcessBranchInput): {
  processNode: ProductProcessWorkflowNode;
  outputNode: ProductProcessWorkflowNode;
  edges: [ProductProcessWorkflowEdge, ProductProcessWorkflowEdge];
} {
  const { source, workProcess, timestamp } = input;
  const outputKind = workProcess.defaultOutputMaterialKind;
  const processId = `process:${workProcess.id}:${timestamp}`;
  const outputId = `material:${outputKind === 'FINISHED_GOOD' ? 'finished' : 'semi'}:${timestamp}`;
  const inputPortId = `input:${timestamp}`;
  const outputPortId = `output:${timestamp}`;
  const inputUnit = String(source.data.baseUnit || workProcess.unit || 'kg');
  const outputUnit = workProcess.outputUnit || workProcess.unit || inputUnit;

  const processNode: ProductProcessWorkflowNode = {
    id: processId,
    kind: 'PROCESS',
    position: snapPosition({ x: source.position.x + 240, y: source.position.y }),
    data: {
      workProcessId: workProcess.id,
      processName: workProcess.processName,
      inputUnit,
      outputUnit,
      ports: [
        {
          id: inputPortId,
          direction: 'INPUT',
          materialNodeId: source.id,
          unit: inputUnit,
          ordinal: 0,
        },
        {
          id: outputPortId,
          direction: 'OUTPUT',
          materialNodeId: outputId,
          materialKind: outputKind,
          unit: outputUnit,
          ordinal: 0,
        },
      ],
      conversionRule: { mode: 'ACTUAL_WEIGHT' },
      reportingRequired: true,
      allowMultipleUpstreamSources: false,
      allowFinishedGoodsSource: false,
    } satisfies ProcessNodeData,
  };
  const isFinished = outputKind === 'FINISHED_GOOD';
  const outputNode: ProductProcessWorkflowNode = {
    id: outputId,
    kind: outputKind,
    position: snapPosition({ x: source.position.x + 720, y: source.position.y }),
    data: {
      name: isFinished ? input.productName : `${workProcess.processName}后半成品`,
      skuId: isFinished ? input.productTypeId : '',
      skuCode: isFinished ? input.productTypeId : '待选择或现场创建 SKU',
      bound: isFinished,
      baseUnit: outputUnit,
    } satisfies MaterialNodeData,
  };

  return {
    processNode,
    outputNode,
    edges: [
      {
        id: `edge:${source.id}:${processId}`,
        source: source.id,
        sourceHandle: 'output',
        target: processId,
        targetHandle: inputPortId,
      },
      {
        id: `edge:${processId}:${outputId}`,
        source: processId,
        sourceHandle: outputPortId,
        target: outputId,
        targetHandle: 'input',
      },
    ],
  };
}

export function createWorkflowFromLegacy(input: {
  productTypeId: string;
  productName: string;
  processes: LegacyProductWorkProcess[];
}): ProductProcessWorkflowDefinition {
  const processes = [...input.processes].sort(
    (a, b) => (a.processOrder ?? 0) - (b.processOrder ?? 0),
  );
  const firstUnit = processUnit(processes[0]);
  const nodes: ProductProcessWorkflowNode[] = [
    {
      id: 'material:raw',
      kind: 'RAW_MATERIAL',
      position: { x: CANVAS_ORIGIN, y: CANVAS_ORIGIN },
      data: {
        name: `${input.productName} 原料`,
        skuId: '',
        skuCode: '待绑定原料 SKU',
        bound: false,
        baseUnit: firstUnit,
      } satisfies MaterialNodeData,
    },
  ];
  const edges: ProductProcessWorkflowEdge[] = [];
  let previousMaterialId = nodes[0].id;

  processes.forEach((legacy, index) => {
    const unit = processUnit(legacy);
    const processId = `process:${legacy.workProcessId}:${index}`;
    const outputId = index === processes.length - 1
      ? 'material:finished'
      : `material:semi:${index}`;
    const processX = CANVAS_ORIGIN + (index * 2 + 1) * LAYER_GAP;
    const outputX = CANVAS_ORIGIN + (index * 2 + 2) * LAYER_GAP;
    const inputPortId = `input:${index}`;
    const outputPortId = `output:${index}`;

    nodes.push({
      id: processId,
      kind: 'PROCESS',
      position: { x: processX, y: CANVAS_ORIGIN },
      data: {
        workProcessId: legacy.workProcessId,
        processName: legacy.processName || legacy.workProcessId,
        inputUnit: unit,
        outputUnit: unit,
        ports: [
          { id: inputPortId, direction: 'INPUT', materialNodeId: previousMaterialId, unit, ordinal: 0 },
          { id: outputPortId, direction: 'OUTPUT', materialNodeId: outputId, unit, ordinal: 0 },
        ],
        conversionRule: { mode: 'ACTUAL_WEIGHT' },
        reportingRequired: legacy.reportingRequired !== false,
        allowMultipleUpstreamSources: legacy.allowMultipleUpstreamSources === true,
        allowFinishedGoodsSource: legacy.allowFinishedGoodsSource === true,
      } satisfies ProcessNodeData,
    });

    const isFinished = index === processes.length - 1;
    nodes.push({
      id: outputId,
      kind: isFinished ? 'FINISHED_GOOD' : 'SEMI_FINISHED',
      position: { x: outputX, y: CANVAS_ORIGIN },
      data: {
        name: isFinished
          ? input.productName
          : `${legacy.processName || legacy.workProcessId}后半成品`,
        skuId: isFinished ? input.productTypeId : '',
        skuCode: isFinished ? input.productTypeId : '待创建半成品 SKU',
        bound: isFinished,
        baseUnit: unit,
      } satisfies MaterialNodeData,
    });

    edges.push(
      {
        id: `edge:${previousMaterialId}:${processId}`,
        source: previousMaterialId,
        sourceHandle: 'output',
        target: processId,
        targetHandle: inputPortId,
      },
      {
        id: `edge:${processId}:${outputId}`,
        source: processId,
        sourceHandle: outputPortId,
        target: outputId,
        targetHandle: 'input',
      },
    );
    previousMaterialId = outputId;
  });

  return {
    productTypeId: input.productTypeId,
    schemaVersion: 1,
    status: 'DRAFT',
    version: 1,
    nodes,
    edges,
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

export function autoLayoutWorkflow(
  definition: ProductProcessWorkflowDefinition,
): ProductProcessWorkflowDefinition {
  const result = cloneDefinition(definition);
  const nodeIds = new Set(result.nodes.map((node) => node.id));
  const incomingCount = new Map(result.nodes.map((node) => [node.id, 0]));
  const outgoing = new Map<string, string[]>();
  result.edges.forEach((edge) => {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) return;
    incomingCount.set(edge.target, (incomingCount.get(edge.target) ?? 0) + 1);
    outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge.target]);
  });
  const depth = new Map(result.nodes.map((node) => [node.id, 0]));
  const queue = result.nodes.filter((node) => (incomingCount.get(node.id) ?? 0) === 0).map((node) => node.id);

  while (queue.length > 0) {
    const source = queue.shift() as string;
    for (const target of outgoing.get(source) ?? []) {
      depth.set(target, Math.max(depth.get(target) ?? 0, (depth.get(source) ?? 0) + 1));
      incomingCount.set(target, (incomingCount.get(target) ?? 0) - 1);
      if (incomingCount.get(target) === 0) queue.push(target);
    }
  }

  const layers = new Map<number, ProductProcessWorkflowNode[]>();
  result.nodes.forEach((node) => {
    const layer = depth.get(node.id) ?? 0;
    layers.set(layer, [...(layers.get(layer) ?? []), node]);
  });
  layers.forEach((layerNodes, layer) => {
    layerNodes.sort((a, b) => a.id.localeCompare(b.id));
    layerNodes.forEach((node, index) => {
      node.position = snapPosition({
        x: CANVAS_ORIGIN + layer * LAYER_GAP,
        y: CANVAS_ORIGIN + index * BRANCH_GAP,
      });
    });
  });
  return result;
}

export function applyWorkflowPatches(
  definition: ProductProcessWorkflowDefinition,
  patches: WorkflowPatch[],
): { definition: ProductProcessWorkflowDefinition; summary: string[] } {
  const result = cloneDefinition(definition);
  const summary: string[] = [];

  patches.forEach((patch) => {
    if (patch.op === 'UPSERT_NODE') {
      const index = result.nodes.findIndex((node) => node.id === patch.node.id);
      const action = index >= 0 ? '更新' : '新增';
      if (index >= 0) result.nodes.splice(index, 1, toPlainWorkflowValue(patch.node));
      else result.nodes.push(toPlainWorkflowValue(patch.node));
      summary.push(`${action}${kindLabel(patch.node.kind)} ${nodeName(patch.node)}`);
      return;
    }
    if (patch.op === 'REMOVE_NODE') {
      const existing = result.nodes.find((node) => node.id === patch.nodeId);
      result.nodes = result.nodes.filter((node) => node.id !== patch.nodeId);
      result.edges = result.edges.filter(
        (edge) => edge.source !== patch.nodeId && edge.target !== patch.nodeId,
      );
      if (existing) summary.push(`删除${kindLabel(existing.kind)} ${nodeName(existing)}`);
      return;
    }
    if (patch.op === 'UPSERT_EDGE') {
      const index = result.edges.findIndex((edge) => edge.id === patch.edge.id);
      if (index >= 0) result.edges.splice(index, 1, toPlainWorkflowValue(patch.edge));
      else result.edges.push(toPlainWorkflowValue(patch.edge));
      summary.push(index >= 0 ? '更新一条连接' : '新增一条连接');
      return;
    }
    if (patch.op === 'REMOVE_EDGE') {
      result.edges = result.edges.filter((edge) => edge.id !== patch.edgeId);
      summary.push('删除一条连接');
      return;
    }
    const node = result.nodes.find((candidate) => candidate.id === patch.nodeId);
    if (!node) return;
    setNestedValue(node.data, patch.path, patch.value);
    summary.push(`更新${kindLabel(node.kind)} ${nodeName(node)}`);
  });
  return { definition: result, summary };
}

export function validateWorkflow(
  definition: ProductProcessWorkflowDefinition,
  mode: 'draft' | 'publish',
): WorkflowValidationError[] {
  const errors: WorkflowValidationError[] = [];
  if (definition.schemaVersion !== 1) {
    errors.push({ code: 'SCHEMA', message: '当前仅支持 schemaVersion 1' });
  }
  const nodeById = new Map(definition.nodes.map((node) => [node.id, node]));
  definition.edges.forEach((edge) => {
    if (!nodeById.has(edge.source) || !nodeById.has(edge.target)) {
      errors.push({ code: 'NODE_REFERENCE', edgeId: edge.id, message: '连接引用了不存在的 Cell' });
    }
  });
  if (hasCycle(definition)) {
    errors.push({ code: 'CYCLE', message: 'Workflow 不能形成回路' });
  }
  if (mode === 'draft') return errors;

  if (!definition.nodes.some((node) => node.kind === 'RAW_MATERIAL')
    || !definition.nodes.some((node) => node.kind === 'FINISHED_GOOD')) {
    errors.push({ code: 'BOUNDARY_REQUIRED', message: '至少需要一个原料 Cell 和一个成品 Cell' });
  }
  definition.nodes.forEach((node) => {
    if (node.kind !== 'PROCESS') {
      if (!String(node.data.skuId ?? '').trim()) {
        errors.push({ code: 'SKU_REQUIRED', nodeId: node.id, message: `${nodeName(node)} 尚未绑定 SKU` });
      }
      return;
    }
    const data = node.data as ProcessNodeData;
    const ports = Array.isArray(data.ports) ? data.ports : [];
    ports.forEach((port) => {
      const connected = port.direction === 'INPUT'
        ? definition.edges.some((edge) => edge.target === node.id && edge.targetHandle === port.id)
        : definition.edges.some((edge) => edge.source === node.id && edge.sourceHandle === port.id);
      if (!port.unit || !connected) {
        errors.push({
          code: 'PORT_REQUIRED',
          nodeId: node.id,
          message: `${data.processName} 的${port.direction === 'INPUT' ? '投入' : '产出'}端口未连接或缺少单位`,
        });
      }
    });
  });
  return errors;
}

function processUnit(process?: LegacyProductWorkProcess): string {
  return process?.unitOverride || process?.defaultUnit || 'kg';
}

function setNestedValue(target: Record<string, unknown>, path: string, value: unknown): void {
  const parts = path.split('.').filter(Boolean);
  if (parts.length === 0) return;
  let cursor: Record<string, unknown> = target;
  parts.slice(0, -1).forEach((part) => {
    const current = cursor[part];
    if (!current || typeof current !== 'object' || Array.isArray(current)) cursor[part] = {};
    cursor = cursor[part] as Record<string, unknown>;
  });
  cursor[parts[parts.length - 1]] = toPlainWorkflowValue(value);
}

function cloneDefinition(
  definition: ProductProcessWorkflowDefinition,
): ProductProcessWorkflowDefinition {
  return toPlainWorkflowValue(definition);
}

function kindLabel(kind: ProductProcessNodeKind): string {
  return {
    RAW_MATERIAL: '原料',
    PROCESS: '工序',
    SEMI_FINISHED: '半成品',
    FINISHED_GOOD: '成品',
  }[kind];
}

function nodeName(node: ProductProcessWorkflowNode): string {
  return String(node.data.processName || node.data.name || node.id);
}

function hasCycle(definition: ProductProcessWorkflowDefinition): boolean {
  const indegree = new Map(definition.nodes.map((node) => [node.id, 0]));
  const outgoing = new Map<string, string[]>();
  definition.edges.forEach((edge) => {
    if (!indegree.has(edge.source) || !indegree.has(edge.target)) return;
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1);
    outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge.target]);
  });
  const queue = [...indegree.entries()].filter(([, count]) => count === 0).map(([id]) => id);
  let visited = 0;
  while (queue.length > 0) {
    const source = queue.shift() as string;
    visited += 1;
    (outgoing.get(source) ?? []).forEach((target) => {
      indegree.set(target, (indegree.get(target) ?? 0) - 1);
      if (indegree.get(target) === 0) queue.push(target);
    });
  }
  return visited !== definition.nodes.length;
}
