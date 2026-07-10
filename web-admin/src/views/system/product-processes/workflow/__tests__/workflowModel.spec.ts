import { describe, expect, it } from 'vitest';
import { reactive } from 'vue';
import {
  applyWorkflowPatches,
  autoLayoutWorkflow,
  createProcessBranch,
  createWorkflowFromLegacy,
  snapPosition,
  toPlainWorkflowValue,
  validateWorkflow,
} from '../workflowModel';
import type {
  ProductProcessWorkflowDefinition,
  ProductProcessWorkflowNode,
  WorkflowPatch,
} from '../types';

describe('product process workflow model', () => {
  it('snaps node positions to the 16px grid', () => {
    expect(snapPosition({ x: 17, y: 31 })).toEqual({ x: 16, y: 32 });
    expect(snapPosition({ x: 247, y: -9 })).toEqual({ x: 240, y: -16 });
  });

  it('creates a JSON-safe snapshot from Vue reactive graph state', () => {
    const reactiveDefinition = reactive(branchedDefinition());

    const snapshot = toPlainWorkflowValue(reactiveDefinition);

    expect(snapshot).toEqual(reactiveDefinition);
    expect(snapshot).not.toBe(reactiveDefinition);
    expect(() => structuredClone(snapshot)).not.toThrow();
  });

  it('creates an alternating material-process graph from the legacy chain', () => {
    const definition = createWorkflowFromLegacy({
      productTypeId: 'FG-CHICKEN-350',
      productName: '干式熟成鸡 350g',
      processes: [
        { id: 1, workProcessId: 'WP-PRE', processName: '前处理', processOrder: 1, defaultUnit: '只' },
        { id: 2, workProcessId: 'WP-DUST', processName: '干式除尘', processOrder: 2, defaultUnit: 'kg' },
        { id: 3, workProcessId: 'WP-PACK', processName: '装箱复称', processOrder: 3, defaultUnit: '盒' },
      ],
    });

    expect(definition.nodes.map((node) => node.kind)).toEqual([
      'RAW_MATERIAL',
      'PROCESS', 'SEMI_FINISHED',
      'PROCESS', 'SEMI_FINISHED',
      'PROCESS', 'FINISHED_GOOD',
    ]);
    expect(definition.edges).toHaveLength(6);
    expect(definition.nodes[1].data).toMatchObject({ inputUnit: '只', outputUnit: '只' });
    expect(definition.nodes[5].data).toMatchObject({ inputUnit: '盒', outputUnit: '盒' });
  });

  it('creates a green semi-finished output branch for a normal process', () => {
    const branch = createProcessBranch({
      source: materialNode('raw', 'RAW_MATERIAL', { x: 16, y: 32 }, 'kg'),
      workProcess: processOption({ defaultOutputMaterialKind: 'SEMI_FINISHED' }),
      productTypeId: 'PT-PIG',
      productName: '五香去骨猪蹄',
      timestamp: 100,
    });

    expect(branch.processNode).toMatchObject({
      id: 'process:WP-CUT:100',
      kind: 'PROCESS',
      position: { x: 256, y: 32 },
      data: {
        inputUnit: 'kg',
        outputUnit: '盒',
        ports: [
          { id: 'input:100', materialNodeId: 'raw', unit: 'kg' },
          {
            id: 'output:100',
            materialNodeId: 'material:semi:100',
            materialKind: 'SEMI_FINISHED',
            unit: '盒',
          },
        ],
      },
    });
    expect(branch.outputNode).toMatchObject({
      id: 'material:semi:100',
      kind: 'SEMI_FINISHED',
      position: { x: 736, y: 32 },
      data: {
        name: '切配后半成品',
        skuId: '',
        bound: false,
        baseUnit: '盒',
      },
    });
    expect(branch.edges).toEqual([
      {
        id: 'edge:raw:process:WP-CUT:100',
        source: 'raw',
        sourceHandle: 'output',
        target: 'process:WP-CUT:100',
        targetHandle: 'input:100',
      },
      {
        id: 'edge:process:WP-CUT:100:material:semi:100',
        source: 'process:WP-CUT:100',
        sourceHandle: 'output:100',
        target: 'material:semi:100',
        targetHandle: 'input',
      },
    ]);
  });

  it('creates a terminal purple finished-good branch for an output process', () => {
    const branch = createProcessBranch({
      source: materialNode('semi', 'SEMI_FINISHED', { x: 32, y: 48 }, 'kg'),
      workProcess: processOption({ defaultOutputMaterialKind: 'FINISHED_GOOD' }),
      productTypeId: 'PT-PIG-400',
      productName: '五香去骨猪蹄 400g',
      timestamp: 101,
    });

    expect(branch.outputNode).toMatchObject({
      id: 'material:finished:101',
      kind: 'FINISHED_GOOD',
      data: {
        name: '五香去骨猪蹄 400g',
        skuId: 'PT-PIG-400',
        skuCode: 'PT-PIG-400',
        bound: true,
      },
    });
    expect(branch.processNode.data.ports).toEqual(expect.arrayContaining([
      expect.objectContaining({
        direction: 'OUTPUT',
        materialNodeId: 'material:finished:101',
        materialKind: 'FINISHED_GOOD',
      }),
    ]));
    expect(branch.edges).toHaveLength(2);
  });

  it('lays out branches and joins by topological depth', () => {
    const definition = branchedDefinition();
    const laidOut = autoLayoutWorkflow(definition);
    const positions = Object.fromEntries(laidOut.nodes.map((node) => [node.id, node.position]));

    expect(positions.raw).toEqual({ x: 32, y: 32 });
    expect(positions.split.x).toBe(272);
    expect(positions.cookA.x).toBe(512);
    expect(positions.cookB.x).toBe(512);
    expect(Math.abs(positions.cookA.y - positions.cookB.y)).toBeGreaterThanOrEqual(160);
    expect(positions.finished.x).toBe(752);
  });

  it('applies a valid workflow patch immutably and reports a concise summary', () => {
    const definition = simpleWorkflowDefinition();
    const patches: WorkflowPatch[] = [
      { op: 'SET_NODE_FIELD', nodeId: 'process:1', path: 'conversionRule.mode', value: 'SUM_OUTPUTS' },
    ];

    const result = applyWorkflowPatches(definition, patches);

    expect(result.definition).not.toBe(definition);
    expect(result.summary).toHaveLength(1);
    expect(result.errors).toEqual([]);
    expect(result.definition.nodes.find((node) => node.id === 'process:1')?.data).toMatchObject({
      conversionRule: { mode: 'SUM_OUTPUTS' },
    });
    expect(definition.nodes.find((node) => node.id === 'process:1')?.data).not.toMatchObject({
      conversionRule: { mode: 'SUM_OUTPUTS' },
    });
  });

  it('applies only strictly shaped workflow patches to a local DRAFT', () => {
    const definition = simpleWorkflowDefinition();
    definition.status = 'PUBLISHED';
    const before = structuredClone(definition);
    const untrustedPatches: unknown[] = [
      {
        op: 'SET_NODE_FIELD',
        nodeId: 'process:1',
        path: 'conversionRule.mode',
        value: 'SUM_OUTPUTS',
      },
      {
        op: 'SET_NODE_FIELD',
        nodeId: 'process:1',
        path: 'productTypeId',
        value: 'ATTACKER-PRODUCT',
      },
      {
        op: 'SET_NODE_FIELD',
        nodeId: 'process:1',
        path: '__proto__.polluted',
        value: true,
      },
      {
        op: 'UPSERT_NODE',
        node: {
          id: 'report:1',
          kind: 'REPORT',
          position: { x: 16, y: 16 },
          data: { name: 'Unauthorized', skuId: 'BAD' },
        },
      },
      {
        op: 'UPSERT_EDGE',
        edge: { id: 'bad-edge', source: 'raw', target: 'split' },
      },
      { op: 'ACTIVATE_WORKFLOW', workflowId: 9 },
    ];

    const result = applyWorkflowPatches(definition, untrustedPatches);

    expect(result.definition).toEqual(before);
    expect(result.definition.nodes.some((node) => node.id === 'report:1')).toBe(false);
    expect(result.definition.edges.some((edge) => edge.id === 'bad-edge')).toBe(false);
    expect(result.definition.nodes[1].data).not.toHaveProperty('productTypeId');
    expect(result.definition.status).toBe('PUBLISHED');
    expect(result.summary).toEqual([]);
    expect(result.errors.length).toBeGreaterThan(0);
  });

  it('rejects graph attacks and node-kind field crossover atomically', () => {
    const definition = simpleWorkflowDefinition();
    const attacks: unknown[][] = [
      [{
        op: 'UPSERT_EDGE',
        edge: {
          id: 'edge:ghost', source: 'ghost', sourceHandle: 'output',
          target: 'process:1', targetHandle: 'input:1',
        },
      }],
      [{
        op: 'UPSERT_EDGE',
        edge: {
          id: 'edge:self', source: 'process:1', sourceHandle: 'output:1',
          target: 'process:1', targetHandle: 'input:1',
        },
      }],
      [{
        op: 'UPSERT_EDGE',
        edge: {
          id: 'edge:handle', source: 'raw', sourceHandle: 'wrong',
          target: 'process:1', targetHandle: 'input:1',
        },
      }],
      [{ op: 'SET_NODE_FIELD', nodeId: 'raw', path: 'ports', value: [] }],
      [{ op: 'SET_NODE_FIELD', nodeId: 'missing', path: 'name', value: 'ghost' }],
      [{
        op: 'UPSERT_NODE',
        node: {
          ...definition.nodes[0],
          kind: 'PROCESS',
          data: definition.nodes[1].data,
        },
      }],
    ];

    attacks.forEach((patches) => {
      const result = applyWorkflowPatches(definition, patches);
      expect(result.definition).toEqual(definition);
      expect(result.summary).toEqual([]);
      expect(result.errors.length).toBeGreaterThan(0);
    });
  });

  it('accepts dependent node then ports then edge patches as one atomic batch', () => {
    const definition = simpleWorkflowDefinition();
    const process = definition.nodes.find((node) => node.id === 'process:1');
    const ports = structuredClone((process?.data as ProcessNodeData).ports);
    ports.push({
      id: 'output:2', direction: 'OUTPUT', materialNodeId: 'semi:2',
      materialKind: 'SEMI_FINISHED', unit: 'kg', ordinal: 1,
    });

    const result = applyWorkflowPatches(definition, [
      {
        op: 'UPSERT_NODE',
        node: {
          id: 'semi:2', kind: 'SEMI_FINISHED', position: { x: 736, y: 192 },
          data: { name: 'Second output', skuId: 'SFI-2', baseUnit: 'kg' },
        },
      },
      { op: 'SET_NODE_FIELD', nodeId: 'process:1', path: 'ports', value: ports },
      {
        op: 'UPSERT_EDGE',
        edge: {
          id: 'edge:process:semi2', source: 'process:1', sourceHandle: 'output:2',
          target: 'semi:2', targetHandle: 'input',
        },
      },
    ]);

    expect(result.errors).toEqual([]);
    expect(result.summary).toHaveLength(3);
    expect(result.definition.nodes.some((node) => node.id === 'semi:2')).toBe(true);
    expect(result.definition.edges.some((edge) => edge.id === 'edge:process:semi2')).toBe(true);
  });

  it('rejects invalid material field values and port ordinals atomically', () => {
    const definition = simpleWorkflowDefinition();
    const process = definition.nodes.find((node) => node.id === 'process:1');
    if (!process) throw new Error('process fixture is missing');
    const basePorts = structuredClone((process.data as ProcessNodeData).ports);
    const attacks: unknown[][] = [
      [{ op: 'SET_NODE_FIELD', nodeId: 'raw', path: 'name', value: null }],
      [{ op: 'SET_NODE_FIELD', nodeId: 'raw', path: 'skuId', value: null }],
      ...[-1, 0.5, Number.NaN, Number.POSITIVE_INFINITY].map((ordinal) => {
        const ports = structuredClone(basePorts);
        ports[0].ordinal = ordinal;
        return [{ op: 'SET_NODE_FIELD', nodeId: 'process:1', path: 'ports', value: ports }];
      }),
    ];

    attacks.forEach((patches) => expectWorkflowPatchRejected(definition, patches));
  });

  it('rejects invalid process optional values and other optional field types', () => {
    const definition = simpleWorkflowDefinition();
    const process = definition.nodes.find((node) => node.id === 'process:1');
    const raw = definition.nodes.find((node) => node.id === 'raw');
    if (!process || !raw) throw new Error('workflow fixtures are missing');
    const invalidStandardTimes = [{ execute: true }, '120', Number.NaN, Number.NEGATIVE_INFINITY];
    const processDataAttacks = [
      ...invalidStandardTimes.map((standardTime) => ({ standardTime })),
      { processCategory: { execute: true } },
      { allowMultipleUpstreamSources: { execute: true } },
      { allowFinishedGoodsSource: { execute: true } },
      { conversionRule: { mode: 'ACTUAL_WEIGHT', expression: { execute: true } } },
    ];
    const materialDataAttacks = [
      { skuCode: { execute: true } },
      { specification: { execute: true } },
      { baseUnit: { execute: true } },
      { bound: { execute: true } },
    ];

    processDataAttacks.forEach((dataPatch) => expectWorkflowPatchRejected(definition, [{
      op: 'UPSERT_NODE',
      node: { ...process, data: { ...process.data, ...dataPatch } },
    }]));
    materialDataAttacks.forEach((dataPatch) => expectWorkflowPatchRejected(definition, [{
      op: 'UPSERT_NODE',
      node: { ...raw, data: { ...raw.data, ...dataPatch } },
    }]));

    const portOptionalFields = ['materialNodeId', 'materialName', 'skuId', 'materialKind'] as const;
    portOptionalFields.forEach((field) => {
      const ports = structuredClone((process.data as ProcessNodeData).ports);
      Object.assign(ports[0], { [field]: { execute: true } });
      expectWorkflowPatchRejected(definition, [{
        op: 'SET_NODE_FIELD', nodeId: 'process:1', path: 'ports', value: ports,
      }]);
    });
  });

  it('accepts aligned nullable and finite boundary values', () => {
    const definition = simpleWorkflowDefinition();
    const process = definition.nodes.find((node) => node.id === 'process:1');
    const raw = definition.nodes.find((node) => node.id === 'raw');
    if (!process || !raw) throw new Error('workflow fixtures are missing');

    for (const standardTime of [null, 0, 120.5]) {
      const result = applyWorkflowPatches(definition, [{
        op: 'UPSERT_NODE',
        node: { ...process, data: { ...process.data, processCategory: null, standardTime } },
      }]);
      expect(result.errors).toEqual([]);
      expect(result.summary).toHaveLength(1);
    }

    const nullableMaterial = applyWorkflowPatches(definition, [{
      op: 'UPSERT_NODE',
      node: { ...raw, data: { ...raw.data, skuCode: null, specification: null } },
    }]);
    expect(nullableMaterial.errors).toEqual([]);
    expect(nullableMaterial.summary).toHaveLength(1);

    const nullableExpression = applyWorkflowPatches(definition, [{
      op: 'SET_NODE_FIELD', nodeId: 'process:1', path: 'conversionRule.expression', value: null,
    }]);
    expect(nullableExpression.errors).toEqual([]);
    expect(nullableExpression.summary).toHaveLength(1);

    const ordinalZero = applyWorkflowPatches(definition, [{
      op: 'SET_NODE_FIELD', nodeId: 'process:1', path: 'ports',
      value: structuredClone((process.data as ProcessNodeData).ports),
    }]);
    expect(ordinalZero.errors).toEqual([]);
  });

  it('detects an unbound SKU and a cycle before publish', () => {
    const definition = branchedDefinition();
    const finished = definition.nodes.find((node) => node.id === 'finished');
    if (finished) finished.data.skuId = '';
    definition.edges.push({
      id: 'cycle', source: 'finished', sourceHandle: 'out', target: 'raw', targetHandle: 'in',
    });

    const errors = validateWorkflow(definition, 'publish');

    expect(errors.some((error) => error.code === 'SKU_REQUIRED' && error.nodeId === 'finished')).toBe(true);
    expect(errors.some((error) => error.code === 'CYCLE')).toBe(true);
  });
});

function expectWorkflowPatchRejected(
  definition: ProductProcessWorkflowDefinition,
  patches: unknown,
): void {
  const before = structuredClone(definition);
  const result = applyWorkflowPatches(definition, patches);
  expect(result.definition).toEqual(before);
  expect(result.summary).toEqual([]);
  expect(result.errors.length).toBeGreaterThan(0);
}

function materialNode(
  id: string,
  kind: 'RAW_MATERIAL' | 'SEMI_FINISHED',
  position: { x: number; y: number },
  baseUnit: string,
): ProductProcessWorkflowNode {
  return {
    id,
    kind,
    position,
    data: { name: id, skuId: `${id}-sku`, baseUnit },
  };
}

function processOption(
  overrides: Partial<{
    id: string;
    processName: string;
    unit: string;
    outputUnit: string | null;
    defaultOutputMaterialKind: 'SEMI_FINISHED' | 'FINISHED_GOOD';
  }> = {},
) {
  return {
    id: 'WP-CUT',
    processName: '切配',
    unit: 'kg',
    outputUnit: '盒',
    defaultOutputMaterialKind: 'SEMI_FINISHED' as const,
    ...overrides,
  };
}

function simpleWorkflowDefinition(): ProductProcessWorkflowDefinition {
  return {
    schemaVersion: 1,
    status: 'DRAFT',
    version: 1,
    nodes: [
      {
        id: 'raw', kind: 'RAW_MATERIAL', position: { x: 16, y: 32 },
        data: { name: 'Raw', skuId: 'RM-1', baseUnit: 'kg' },
      },
      {
        id: 'process:1', kind: 'PROCESS', position: { x: 256, y: 32 },
        data: {
          workProcessId: 'WP-1', processName: 'Cut', inputUnit: 'kg', outputUnit: 'kg',
          ports: [
            {
              id: 'input:1', direction: 'INPUT', materialNodeId: 'raw',
              materialKind: 'RAW_MATERIAL', unit: 'kg', ordinal: 0,
            },
            {
              id: 'output:1', direction: 'OUTPUT', materialNodeId: 'semi',
              materialKind: 'SEMI_FINISHED', unit: 'kg', ordinal: 0,
            },
          ],
          conversionRule: { mode: 'ACTUAL_WEIGHT' },
          reportingRequired: true,
        },
      },
      {
        id: 'semi', kind: 'SEMI_FINISHED', position: { x: 496, y: 32 },
        data: { name: 'Semi', skuId: 'SFI-1', baseUnit: 'kg' },
      },
    ],
    edges: [
      {
        id: 'edge:raw:process', source: 'raw', sourceHandle: 'output',
        target: 'process:1', targetHandle: 'input:1',
      },
      {
        id: 'edge:process:semi', source: 'process:1', sourceHandle: 'output:1',
        target: 'semi', targetHandle: 'input',
      },
    ],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

function branchedDefinition(): ProductProcessWorkflowDefinition {
  return {
    schemaVersion: 1,
    status: 'DRAFT',
    version: 1,
    nodes: [
      {
        id: 'raw', kind: 'RAW_MATERIAL', position: { x: 0, y: 0 },
        data: { name: '原料猪蹄', skuId: 'RM-PIG', skuCode: 'RM-PIG' },
      },
      {
        id: 'split', kind: 'PROCESS', position: { x: 0, y: 0 },
        data: {
          workProcessId: 'WP-SPLIT', processName: '拆包 / 分切', inputUnit: 'kg', outputUnit: 'kg',
          ports: [
            { id: 'in', direction: 'INPUT', unit: 'kg', ordinal: 0 },
            { id: 'out-a', direction: 'OUTPUT', unit: 'kg', ordinal: 0 },
            { id: 'out-b', direction: 'OUTPUT', unit: 'kg', ordinal: 1 },
          ],
          conversionRule: { mode: 'ACTUAL_WEIGHT' }, reportingRequired: true,
        },
      },
      {
        id: 'cookA', kind: 'SEMI_FINISHED', position: { x: 0, y: 0 },
        data: { name: '五香线猪蹄', skuId: 'SFI-FIVE', skuCode: 'SFI-FIVE' },
      },
      {
        id: 'cookB', kind: 'SEMI_FINISHED', position: { x: 0, y: 0 },
        data: { name: '泰式酸辣线猪蹄', skuId: 'SFI-THAI', skuCode: 'SFI-THAI' },
      },
      {
        id: 'finished', kind: 'FINISHED_GOOD', position: { x: 0, y: 0 },
        data: { name: '猪蹄组合装', skuId: 'FG-PIG-COMBO', skuCode: 'FG-PIG-COMBO' },
      },
    ],
    edges: [
      { id: 'e1', source: 'raw', sourceHandle: 'out', target: 'split', targetHandle: 'in' },
      { id: 'e2', source: 'split', sourceHandle: 'out-a', target: 'cookA', targetHandle: 'in' },
      { id: 'e3', source: 'split', sourceHandle: 'out-b', target: 'cookB', targetHandle: 'in' },
      { id: 'e4', source: 'cookA', sourceHandle: 'out', target: 'finished', targetHandle: 'in-a' },
      { id: 'e5', source: 'cookB', sourceHandle: 'out', target: 'finished', targetHandle: 'in-b' },
    ],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}
