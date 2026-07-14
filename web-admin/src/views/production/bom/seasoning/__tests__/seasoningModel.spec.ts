import { describe, expect, it } from 'vitest';
import type { SeasoningProcessView } from '@/api/bom';
import { buildMaterialSummaries, findDuplicateBinding, groupBindingsByProcess, otherProcessUsages } from '../seasoningModel';

const processes: SeasoningProcessView[] = [
  {
    workProcessId: 'ROLL', processOrder: 2, processName: '滚揉', bindings: [{
      id: 1, workProcessId: 'ROLL', materialTypeId: 'M1', name: '辣椒粉', unit: 'g',
      dosagePerKgG: 5, subsequentPotRatio: 0.5, countInSeasoning: true, priceSnapshot: 18,
    }],
  },
  {
    workProcessId: 'FRY', processOrder: 3, processName: '炸水', bindings: [{
      id: 2, workProcessId: 'FRY', materialTypeId: 'M1', name: '辣椒粉', unit: 'g',
      dosagePerKgG: 1.5, subsequentPotRatio: null, countInSeasoning: true, priceSnapshot: 18,
    }],
  },
];

describe('seasoning workspace model', () => {
  it('groups bindings by workflow process', () => {
    expect(groupBindingsByProcess(processes).ROLL).toHaveLength(1);
    expect(groupBindingsByProcess(processes).FRY[0].dosagePerKgG).toBe(1.5);
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
    expect(otherProcessUsages(processes, 'M1', 'ROLL').map((process) => process.workProcessId)).toEqual(['FRY']);
  });
});
