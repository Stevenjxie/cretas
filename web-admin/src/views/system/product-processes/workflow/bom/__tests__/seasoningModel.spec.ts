import { describe, expect, it } from 'vitest';
import type { SeasoningProcessView } from '@/api/bom';
import { buildMaterialSummaries, findDuplicateBinding, groupBindingsByProcess, otherProcessUsages, uniqueProcessesByNode } from '../seasoningModel';

const processes: SeasoningProcessView[] = [
  {
    workflowProcessNodeId: 'node-roll', workProcessId: 'ROLL', processOrder: 2, processName: '滚揉', basisQuantity: 1, basisUnit: 'kg', standardUsageSupported: true, bindings: [{
      id: 1, workProcessId: 'ROLL', materialTypeId: 'M1', name: '辣椒粉', unit: 'g',
      dosagePerKgG: 5, subsequentPotRatio: 0.5, countInSeasoning: true, priceSnapshot: 18,
    }],
  },
  {
    workflowProcessNodeId: 'node-fry', workProcessId: 'FRY', processOrder: 3, processName: '炸水', basisQuantity: 1000, basisUnit: 'g', standardUsageSupported: true, bindings: [{
      id: 2, workProcessId: 'FRY', materialTypeId: 'M1', name: '辣椒粉', unit: 'g',
      dosagePerKgG: 1.5, subsequentPotRatio: null, countInSeasoning: true, priceSnapshot: 18,
    }],
  },
];

describe('seasoning workspace model', () => {
  it('groups bindings by workflow process', () => {
    expect(groupBindingsByProcess(processes)['node-roll']).toHaveLength(1);
    expect(groupBindingsByProcess(processes)['node-fry'][0].dosagePerKgG).toBe(1.5);
  });

  it('deduplicates a material into one summary while preserving two independent usages', () => {
    const summaries = buildMaterialSummaries(processes);
    expect(summaries).toHaveLength(1);
    expect(summaries[0].usages.map((usage) => usage.dosagePerKgG)).toEqual([5, 1.5]);
    expect(summaries[0].usages.map((usage) => usage.workProcessId)).toEqual(['ROLL', 'FRY']);
    expect(summaries[0]).not.toHaveProperty('totalDosagePerKgG');
    expect(summaries[0]).not.toHaveProperty('dosagePerKgG');
  });

  it('blocks duplicates only inside the same process', () => {
    expect(findDuplicateBinding(processes[0], 'M1')?.id).toBe(1);
    expect(findDuplicateBinding(processes[0], 'M1', 1)).toBeUndefined();
    expect(findDuplicateBinding(processes[0], 'M2')).toBeUndefined();
  });

  it('finds reuse in other processes without treating it as a duplicate', () => {
    expect(otherProcessUsages(processes, 'M1', 'node-roll').map((process) => process.workProcessId)).toEqual(['FRY']);
  });

  it('renders a process shared by two reachable roots only once', () => {
    const duplicated = [processes[0], { ...processes[0], processOrder: 99 }];
    expect(uniqueProcessesByNode(duplicated)).toHaveLength(1);
    expect(buildMaterialSummaries(uniqueProcessesByNode(duplicated))[0].usages).toHaveLength(1);
  });
});
