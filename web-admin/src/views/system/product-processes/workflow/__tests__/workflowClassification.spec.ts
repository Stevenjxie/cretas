import { describe, expect, it } from 'vitest';
import { classifyWorkflowTopology, type WorkflowTopologyNode } from '../workflowClassification';

const raw = (id: string): WorkflowTopologyNode => ({ id, kind: 'RAW_MATERIAL', skuId: id });
const process = (id: string): WorkflowTopologyNode => ({ id, kind: 'PROCESS' });
const finished = (id: string): WorkflowTopologyNode => ({ id, kind: 'FINISHED_GOOD', skuId: id });

describe('Workflow topology classification', () => {
  it('classifies one output as product Workflow regardless of one or many inputs', () => {
    expect(classifyWorkflowTopology(
      [raw('R1'), raw('R2'), process('P'), finished('F1')],
      [{ source: 'R1', target: 'P' }, { source: 'R2', target: 'P' }, { source: 'P', target: 'F1' }],
    ).type).toBe('PRODUCT');
  });

  it('classifies one root and multiple outputs as raw split Workflow', () => {
    expect(classifyWorkflowTopology(
      [raw('R1'), process('P'), finished('F1'), finished('F2')],
      [{ source: 'R1', target: 'P' }, { source: 'P', target: 'F1' }, { source: 'P', target: 'F2' }],
    ).type).toBe('RAW_SPLIT');
  });

  it('classifies multiple roots and multiple outputs as joint production Workflow', () => {
    const result = classifyWorkflowTopology(
      [raw('R1'), raw('R2'), process('P'), finished('F1'), finished('F2')],
      [{ source: 'R1', target: 'P' }, { source: 'R2', target: 'P' }, { source: 'P', target: 'F1' }, { source: 'P', target: 'F2' }],
    );

    expect(result).toMatchObject({ type: 'JOINT_PRODUCTION', rootInputCount: 2, terminalOutputCount: 2 });
  });

  it('keeps an unfinished graph unclassified until it has a terminal finished output', () => {
    expect(classifyWorkflowTopology([raw('R1'), process('P')], [{ source: 'R1', target: 'P' }]).type)
      .toBe('INCOMPLETE');
  });
});
