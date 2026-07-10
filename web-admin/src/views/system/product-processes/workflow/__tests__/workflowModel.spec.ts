import { describe, expect, it } from 'vitest';
import { reactive } from 'vue';
import {
  applyWorkflowPatches,
  autoLayoutWorkflow,
  createWorkflowFromLegacy,
  snapPosition,
  toPlainWorkflowValue,
  validateWorkflow,
} from '../workflowModel';
import type {
  ProductProcessWorkflowDefinition,
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

  it('applies AI patches immutably and reports a concise summary', () => {
    const definition = branchedDefinition();
    const patches: WorkflowPatch[] = [
      { op: 'SET_NODE_FIELD', nodeId: 'split', path: 'conversionRule.mode', value: 'SUM_OUTPUTS' },
      {
        op: 'UPSERT_NODE',
        node: {
          id: 'loss',
          kind: 'SEMI_FINISHED',
          position: { x: 510, y: 400 },
          data: { name: '不合格品损耗', skuId: 'SFI-LOSS', skuCode: 'SFI-LOSS' },
        },
      },
    ];

    const result = applyWorkflowPatches(definition, patches);

    expect(result.definition).not.toBe(definition);
    expect(result.summary).toEqual(['更新工序 拆包 / 分切', '新增半成品 不合格品损耗']);
    expect(result.definition.nodes.find((node) => node.id === 'split')?.data).toMatchObject({
      conversionRule: { mode: 'SUM_OUTPUTS' },
    });
    expect(definition.nodes.find((node) => node.id === 'split')?.data).not.toMatchObject({
      conversionRule: { mode: 'SUM_OUTPUTS' },
    });
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
