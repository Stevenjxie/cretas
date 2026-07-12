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
// 自动布局间距: 层间(横向)须清得过最宽的工序 Cell(~390px), 同层(纵向)须清得过 Cell 高度,
// 否则多产出/多分支时 Cell 会叠成一坨(挤在一起)。放宽后各 Cell 有呼吸空间。
// BRANCH_GAP=480: 工序 Cell 是同层里最高的盒子——heading + 系统研判提示 + 投入/产出两个
// port-section + 数量关系 + 报工开关叠起来, 常规 1~2 端口场景下实测约 400~450px 高,
// 320 明显不够(相邻两行会视觉重叠), 480 留出安全余量。这是"够用的固定常量", 不是按
// 每个 Cell 实际 DOM 高度动态计算——极端多端口(4+ 入/出)场景仍可能偏紧, 需要时再引入
// 运行时测量。
const LAYER_GAP = 440;
const BRANCH_GAP = 480;
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
  // 每层各自独立起跑一个"行计数器"(rowInLayer), 不用跨层的全局 index——否则两个不同层、
  // 但恰好都是该层第 0 个节点的 Cell 会拿到同一个 y, 同层内任意两个 Cell 的 position
  // 就永远不会重合/重叠。
  layers.forEach((layerNodes, layer) => {
    layerNodes.sort((a, b) => a.id.localeCompare(b.id));
    let rowInLayer = 0;
    layerNodes.forEach((node) => {
      node.position = snapPosition({
        x: CANVAS_ORIGIN + layer * LAYER_GAP,
        y: CANVAS_ORIGIN + rowInLayer * BRANCH_GAP,
      });
      rowInLayer += 1;
    });
  });
  return result;
}

export function applyWorkflowPatches(
  definition: ProductProcessWorkflowDefinition,
  patches: unknown,
): { definition: ProductProcessWorkflowDefinition; summary: string[]; errors: string[] } {
  const original = cloneDefinition(definition);
  if (!Array.isArray(patches) || patches.length === 0) {
    return { definition: original, summary: [], errors: ['Workflow patch batch is empty'] };
  }
  const parsedPatches = patches.map(sanitizeWorkflowPatch);
  if (parsedPatches.some((patch) => patch === null)) {
    return { definition: original, summary: [], errors: ['Workflow patch batch contains an invalid member'] };
  }
  const safePatches = parsedPatches.filter((patch): patch is WorkflowPatch => patch !== null);
  const orderedPatches = [...safePatches].sort(
    (left, right) => patchPhase(left.op) - patchPhase(right.op),
  );
  const result = cloneDefinition(definition);
  const summary: string[] = [];
  let applicationError = '';

  orderedPatches.forEach((patch) => {
    if (applicationError) return;
    if (patch.op === 'UPSERT_NODE') {
      const index = result.nodes.findIndex((node) => node.id === patch.node.id);
      if (index >= 0 && result.nodes[index].kind !== patch.node.kind) {
        applicationError = `Workflow patch cannot change node kind: ${patch.node.id}`;
        return;
      }
      const action = index >= 0 ? '更新' : '新增';
      if (index >= 0) result.nodes.splice(index, 1, toPlainWorkflowValue(patch.node));
      else result.nodes.push(toPlainWorkflowValue(patch.node));
      summary.push(`${action}${kindLabel(patch.node.kind)} ${nodeName(patch.node)}`);
      return;
    }
    if (patch.op === 'REMOVE_NODE') {
      const existing = result.nodes.find((node) => node.id === patch.nodeId);
      if (!existing) {
        applicationError = `Workflow patch references a missing node: ${patch.nodeId}`;
        return;
      }
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
      if (!result.edges.some((edge) => edge.id === patch.edgeId)) {
        applicationError = `Workflow patch references a missing edge: ${patch.edgeId}`;
        return;
      }
      result.edges = result.edges.filter((edge) => edge.id !== patch.edgeId);
      summary.push('删除一条连接');
      return;
    }
    const node = result.nodes.find((candidate) => candidate.id === patch.nodeId);
    if (!node) {
      applicationError = `Workflow patch references a missing node: ${patch.nodeId}`;
      return;
    }
    if (!isPathCompatibleWithNodeKind(node.kind, patch.path)) {
      applicationError = `Workflow patch field is incompatible with node kind: ${patch.path}`;
      return;
    }
    setNestedValue(node.data, patch.path, patch.value);
    summary.push(`更新${kindLabel(node.kind)} ${nodeName(node)}`);
  });
  if (applicationError) {
    return { definition: original, summary: [], errors: [applicationError] };
  }

  result.status = 'DRAFT';
  const errors = [
    ...validateWorkflow(result, 'draft').map((error) => error.message),
    ...validateWorkflowPatchGraph(result),
  ];
  if (errors.length > 0) {
    return { definition: original, summary: [], errors: [...new Set(errors)] };
  }
  return { definition: result, summary, errors: [] };
}

function patchPhase(operation: WorkflowPatch['op']): number {
  if (operation === 'UPSERT_NODE' || operation === 'REMOVE_NODE') return 0;
  if (operation === 'SET_NODE_FIELD') return 1;
  return 2;
}

function isPathCompatibleWithNodeKind(kind: ProductProcessNodeKind, path: string): boolean {
  if (kind === 'PROCESS') {
    return path === 'ports'
      || path === 'conversionRule'
      || path.startsWith('conversionRule.')
      || path === 'reportingRequired';
  }
  return ['name', 'skuId', 'skuCode', 'specification'].includes(path);
}

const workflowPatchOperations = new Set([
  'UPSERT_NODE', 'REMOVE_NODE', 'UPSERT_EDGE', 'REMOVE_EDGE', 'SET_NODE_FIELD',
]);
const workflowNodeKinds = new Set<ProductProcessNodeKind>([
  'RAW_MATERIAL', 'PROCESS', 'SEMI_FINISHED', 'FINISHED_GOOD',
]);
const materialNodeKinds = new Set(['RAW_MATERIAL', 'SEMI_FINISHED', 'FINISHED_GOOD']);
const conversionModes = new Set(['ACTUAL_WEIGHT', 'FIXED_RATIO', 'SUM_OUTPUTS', 'FORMULA']);
const materialDataKeys = new Set(['name', 'skuId', 'skuCode', 'specification', 'baseUnit', 'bound']);
const processDataKeys = new Set([
  'workProcessId', 'processName', 'processCategory', 'inputUnit', 'outputUnit', 'standardTime',
  'ports', 'conversionRule', 'reportingRequired', 'allowMultipleUpstreamSources',
  'allowFinishedGoodsSource',
]);
const portKeys = new Set([
  'id', 'direction', 'materialNodeId', 'materialName', 'skuId', 'materialKind', 'unit', 'ordinal',
]);
const fieldPaths = new Set([
  'name', 'skuId', 'skuCode', 'specification', 'ports', 'conversionRule',
  'conversionRule.mode', 'conversionRule.expression', 'reportingRequired',
]);

function sanitizeWorkflowPatch(raw: unknown): WorkflowPatch | null {
  if (!isRecord(raw) || typeof raw.op !== 'string' || !workflowPatchOperations.has(raw.op)) {
    return null;
  }
  if (raw.op === 'UPSERT_NODE') {
    return hasExactKeys(raw, ['op', 'node']) && isWorkflowNode(raw.node)
      ? { op: 'UPSERT_NODE', node: toPlainWorkflowValue(raw.node) }
      : null;
  }
  if (raw.op === 'REMOVE_NODE') {
    return hasExactKeys(raw, ['op', 'nodeId']) && isNonBlankString(raw.nodeId)
      ? { op: 'REMOVE_NODE', nodeId: raw.nodeId }
      : null;
  }
  if (raw.op === 'UPSERT_EDGE') {
    return hasExactKeys(raw, ['op', 'edge']) && isWorkflowEdge(raw.edge)
      ? { op: 'UPSERT_EDGE', edge: toPlainWorkflowValue(raw.edge) }
      : null;
  }
  if (raw.op === 'REMOVE_EDGE') {
    return hasExactKeys(raw, ['op', 'edgeId']) && isNonBlankString(raw.edgeId)
      ? { op: 'REMOVE_EDGE', edgeId: raw.edgeId }
      : null;
  }
  if (!hasExactKeys(raw, ['op', 'nodeId', 'path', 'value'])
    || !isNonBlankString(raw.nodeId)
    || !isNonBlankString(raw.path)
    || !fieldPaths.has(raw.path)
    || !isAllowedFieldValue(raw.path, raw.value)) {
    return null;
  }
  return {
    op: 'SET_NODE_FIELD',
    nodeId: raw.nodeId,
    path: raw.path,
    value: toPlainWorkflowValue(raw.value),
  };
}

function isWorkflowNode(value: unknown): value is ProductProcessWorkflowNode {
  if (!isRecord(value)
    || !hasExactKeys(value, ['id', 'kind', 'position', 'data'])
    || !isNonBlankString(value.id)
    || !isProductProcessNodeKind(value.kind)
    || !isWorkflowPosition(value.position)
    || !isRecord(value.data)) {
    return false;
  }
  return value.kind === 'PROCESS'
    ? isProcessNodeData(value.data)
    : isMaterialNodeData(value.data);
}

function isWorkflowEdge(value: unknown): value is ProductProcessWorkflowEdge {
  return isRecord(value)
    && hasExactKeys(value, ['id', 'source', 'sourceHandle', 'target', 'targetHandle'])
    && isNonBlankString(value.id)
    && isNonBlankString(value.source)
    && isNonBlankString(value.sourceHandle)
    && isNonBlankString(value.target)
    && isNonBlankString(value.targetHandle);
}

function isWorkflowPosition(value: unknown): value is WorkflowPosition {
  return isRecord(value)
    && hasExactKeys(value, ['x', 'y'])
    && isFiniteNumber(value.x)
    && isFiniteNumber(value.y);
}

function isMaterialNodeData(value: Record<string, unknown>): value is MaterialNodeData {
  return hasAllowedKeys(value, materialDataKeys)
    && isNonBlankString(value.name)
    && typeof value.skuId === 'string'
    && optionalNullableString(value.skuCode)
    && optionalNullableString(value.specification)
    && optionalString(value.baseUnit)
    && optionalBoolean(value.bound);
}

function isProcessNodeData(value: Record<string, unknown>): value is ProcessNodeData {
  return hasAllowedKeys(value, processDataKeys)
    && isNonBlankString(value.workProcessId)
    && isNonBlankString(value.processName)
    && isNonBlankString(value.inputUnit)
    && isNonBlankString(value.outputUnit)
    && Array.isArray(value.ports)
    && value.ports.every(isProcessPort)
    && isConversionRule(value.conversionRule)
    && typeof value.reportingRequired === 'boolean'
    && optionalNullableString(value.processCategory)
    && optionalNullableFiniteNumber(value.standardTime)
    && optionalBoolean(value.allowMultipleUpstreamSources)
    && optionalBoolean(value.allowFinishedGoodsSource);
}

function isProcessPort(value: unknown): boolean {
  if (!isRecord(value)
    || !hasAllowedKeys(value, portKeys)
    || !isNonBlankString(value.id)
    || (value.direction !== 'INPUT' && value.direction !== 'OUTPUT')
    || !isNonBlankString(value.unit)
    || !Number.isInteger(value.ordinal)
    || (value.ordinal as number) < 0) {
    return false;
  }
  return optionalString(value.materialNodeId)
    && optionalString(value.materialName)
    && optionalString(value.skuId)
    && (value.materialKind === undefined
      || (typeof value.materialKind === 'string' && materialNodeKinds.has(value.materialKind)));
}

function isConversionRule(value: unknown): boolean {
  return isRecord(value)
    && hasAllowedKeys(value, new Set(['mode', 'expression']))
    && typeof value.mode === 'string'
    && conversionModes.has(value.mode)
    && optionalNullableString(value.expression);
}

function isAllowedFieldValue(path: string, value: unknown): boolean {
  if (path === 'name') return isNonBlankString(value);
  if (path === 'skuId') return typeof value === 'string';
  if (['skuCode', 'specification', 'conversionRule.expression'].includes(path)) {
    return value === null || typeof value === 'string';
  }
  if (path === 'reportingRequired') return typeof value === 'boolean';
  if (path === 'conversionRule.mode') return typeof value === 'string' && conversionModes.has(value);
  if (path === 'conversionRule') return isConversionRule(value);
  if (path === 'ports') return Array.isArray(value) && value.every(isProcessPort);
  return false;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, keys: string[]): boolean {
  return Object.keys(value).length === keys.length && keys.every((key) => key in value);
}

function hasAllowedKeys(value: Record<string, unknown>, keys: Set<string>): boolean {
  return Object.keys(value).every((key) => keys.has(key));
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function optionalString(value: unknown): boolean {
  return value === undefined || typeof value === 'string';
}

function optionalNullableString(value: unknown): boolean {
  return value === undefined || value === null || typeof value === 'string';
}

function optionalNullableFiniteNumber(value: unknown): boolean {
  return value === undefined || value === null || isFiniteNumber(value);
}

function optionalBoolean(value: unknown): boolean {
  return value === undefined || typeof value === 'boolean';
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isProductProcessNodeKind(value: unknown): value is ProductProcessNodeKind {
  return typeof value === 'string' && workflowNodeKinds.has(value as ProductProcessNodeKind);
}

function validateWorkflowPatchGraph(definition: ProductProcessWorkflowDefinition): string[] {
  const errors: string[] = [];
  const nodes = new Map<string, ProductProcessWorkflowNode>();
  definition.nodes.forEach((node) => {
    if (nodes.has(node.id)) errors.push(`Duplicate workflow node: ${node.id}`);
    nodes.set(node.id, node);
  });
  const edgeIds = new Set<string>();
  const portsByProcess = new Map<string, Map<string, Record<string, unknown>>>();

  definition.nodes.forEach((node) => {
    if (node.kind !== 'PROCESS') return;
    const data = node.data as ProcessNodeData;
    if (!Array.isArray(data.ports)) {
      errors.push(`Process ports are invalid: ${node.id}`);
      return;
    }
    const ports = new Map<string, Record<string, unknown>>();
    data.ports.forEach((rawPort) => {
      const port = rawPort as unknown;
      if (!isRecord(port) || !isProcessPort(port)) {
        errors.push(`Process port is invalid: ${node.id}`);
        return;
      }
      const portId = String(port.id);
      if (ports.has(portId)) errors.push(`Duplicate process port: ${node.id}/${portId}`);
      const materialNodeId = String(port.materialNodeId || '');
      const material = nodes.get(materialNodeId);
      if (!material || material.kind === 'PROCESS') {
        errors.push(`Process port references a missing material node: ${node.id}/${portId}`);
      } else if (port.materialKind !== undefined && port.materialKind !== material.kind) {
        errors.push(`Process port material kind mismatch: ${node.id}/${portId}`);
      }
      ports.set(portId, port);
    });
    portsByProcess.set(node.id, ports);
  });

  const matchedPorts = new Set<string>();
  definition.edges.forEach((edge) => {
    if (edgeIds.has(edge.id)) errors.push(`Duplicate workflow edge: ${edge.id}`);
    edgeIds.add(edge.id);
    const source = nodes.get(edge.source);
    const target = nodes.get(edge.target);
    if (!source || !target) return;
    if (source.id === target.id) {
      errors.push(`Workflow edge cannot be a self-loop: ${edge.id}`);
      return;
    }
    if (source.kind === 'PROCESS' && target.kind !== 'PROCESS') {
      const port = portsByProcess.get(source.id)?.get(edge.sourceHandle);
      if (!port || port.direction !== 'OUTPUT'
        || port.materialNodeId !== target.id || edge.targetHandle !== 'input') {
        errors.push(`Workflow output edge does not match its process port: ${edge.id}`);
        return;
      }
      matchedPorts.add(`${source.id}::${edge.sourceHandle}`);
      return;
    }
    if (source.kind !== 'PROCESS' && target.kind === 'PROCESS') {
      const port = portsByProcess.get(target.id)?.get(edge.targetHandle);
      if (!port || port.direction !== 'INPUT'
        || port.materialNodeId !== source.id || edge.sourceHandle !== 'output') {
        errors.push(`Workflow input edge does not match its process port: ${edge.id}`);
        return;
      }
      matchedPorts.add(`${target.id}::${edge.targetHandle}`);
      return;
    }
    errors.push(`Workflow edge must connect material and process nodes: ${edge.id}`);
  });

  portsByProcess.forEach((ports, processId) => ports.forEach((port, portId) => {
    if (port.materialNodeId && !matchedPorts.has(`${processId}::${portId}`)) {
      errors.push(`Process port has no matching workflow edge: ${processId}/${portId}`);
    }
  }));
  return errors;
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
