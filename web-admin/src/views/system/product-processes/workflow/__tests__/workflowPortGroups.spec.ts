import { describe, expect, it } from 'vitest';
import { applyWorkflowPatches, validateWorkflow } from '../workflowModel';
import type { ProcessNodeData, ProductProcessWorkflowDefinition } from '../types';

function definition(): ProductProcessWorkflowDefinition {
  return {
    schemaVersion: 1,
    status: 'DRAFT',
    version: 1,
    nodes: [
      { id: 'raw-a', kind: 'RAW_MATERIAL', position: { x: 0, y: 0 }, data: { name: '原料 A', skuId: 'RM-A' } },
      { id: 'raw-b', kind: 'RAW_MATERIAL', position: { x: 0, y: 100 }, data: { name: '原料 B', skuId: 'RM-B' } },
      {
        id: 'process', kind: 'PROCESS', position: { x: 200, y: 0 }, data: {
          workProcessId: 'WP-1', processName: '混合', inputUnit: 'kg', outputUnit: 'kg',
          ports: [
            { id: 'in-a', direction: 'INPUT', materialNodeId: 'raw-a', unit: 'kg', ordinal: 0 },
            { id: 'in-b', direction: 'INPUT', materialNodeId: 'raw-b', unit: 'kg', ordinal: 1 },
            { id: 'out', direction: 'OUTPUT', materialNodeId: 'finished', unit: 'kg', ordinal: 0 },
          ],
          conversionRule: { mode: 'ACTUAL_WEIGHT' }, reportingRequired: true,
        },
      },
      { id: 'finished', kind: 'FINISHED_GOOD', position: { x: 400, y: 0 }, data: { name: '成品', skuId: 'FG-1' } },
    ],
    edges: [
      { id: 'e-a', source: 'raw-a', sourceHandle: 'output', target: 'process', targetHandle: 'in-a' },
      { id: 'e-b', source: 'raw-b', sourceHandle: 'output', target: 'process', targetHandle: 'in-b' },
      { id: 'e-out', source: 'process', sourceHandle: 'out', target: 'finished', targetHandle: 'input' },
    ],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

describe('workflow port group contract', () => {
  it('accepts a strictly shaped portGroups patch and preserves the group array', () => {
    const result = applyWorkflowPatches(definition(), [{
      op: 'SET_NODE_FIELD', nodeId: 'process', path: 'portGroups', value: [{
        id: 'inputs', direction: 'INPUT', label: '替代原料', mode: 'EXACTLY_ONE',
        minSelections: 1, maxSelections: 1, portIds: ['in-a', 'in-b'],
      }],
    }]);
    expect(result.errors).toEqual([]);
    expect((result.definition.nodes[2].data as ProcessNodeData).portGroups).toEqual([
      expect.objectContaining({ id: 'inputs', mode: 'EXACTLY_ONE', portIds: ['in-a', 'in-b'] }),
    ]);
  });

  it('rejects malformed bounds and unknown group fields atomically', () => {
    const original = definition();
    for (const value of [
      [{ id: 'inputs', direction: 'INPUT', label: '替代原料', mode: 'EXACTLY_ONE', minSelections: 0, maxSelections: 1, portIds: ['in-a', 'in-b'] }],
      [{ id: 'inputs', direction: 'INPUT', label: '替代原料', mode: 'EXACTLY_ONE', minSelections: 1, maxSelections: 1, portIds: ['in-a', 'in-b'], extra: true }],
    ]) {
      const result = applyWorkflowPatches(original, [{ op: 'SET_NODE_FIELD', nodeId: 'process', path: 'portGroups', value }]);
      expect(result.definition).toEqual(original);
      expect(result.errors.length).toBeGreaterThan(0);
    }
  });

  it('blocks publish when a group references a missing or wrong-direction port', () => {
    const workflow = definition();
    (workflow.nodes[2].data as ProcessNodeData).portGroups = [{
      id: 'inputs', direction: 'INPUT', label: '投入关系', mode: 'ALL_REQUIRED',
      minSelections: 2, maxSelections: 2, portIds: ['in-a', 'out'],
    }];
    expect(validateWorkflow(workflow, 'publish')).toEqual(expect.arrayContaining([
      expect.objectContaining({ code: 'PORT_GROUP_INVALID', nodeId: 'process' }),
    ]));
  });
});
