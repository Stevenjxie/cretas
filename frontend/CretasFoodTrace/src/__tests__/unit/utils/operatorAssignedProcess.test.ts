import { isTaskReportComplete } from '../../../utils/operatorAssignedProcess';
import { BatchYieldDTO, WorkProcessTask } from '../../../services/api/yieldReportApi';

// ─── helpers ───────────────────────────────────────────────────────────────

const baseTask: WorkProcessTask = {
  id: 101,
  factoryId: 'F006',
  productionBatchId: 2025,
  productWorkProcessId: 1,
  workProcessId: '__MATERIAL_INPUT__',
  productTypeId: 'PT-1',
  processOrder: 0,
  status: 'IN_PROGRESS',
  plannedQuantity: 10,
  plannedUnit: 'kg',
  assignedTo: 7,
};

function makeYield(step: Partial<BatchYieldDTO['steps'][number]>): BatchYieldDTO {
  return {
    batchId: 2025,
    batchNumber: 'B-2025',
    firstStepInput: null,
    lastStepOutput: null,
    firstStepInputUnit: null,
    lastStepOutputUnit: null,
    cumulativeYieldRate: null,
    complete: null,
    totalWorkMinutes: null,
    totalWorkers: null,
    totalLaborCost: null,
    totalMaterialCost: null,
    totalCost: null,
    steps: [
      {
        workProcessTaskId: 101,
        processOrder: 0,
        processName: '领料报工',
        totalInput: null,
        totalOutput: null,
        inputUnit: 'kg',
        outputUnit: 'kg',
        yieldRate: null,
        unitComparable: null,
        carryover: null,
        yieldAlert: null,
        totalWorkMinutes: null,
        totalWorkers: null,
        laborCost: null,
        materialCost: null,
        stepCost: null,
        ...step,
      },
    ],
  };
}

// ─── material-input sentinel ────────────────────────────────────────────────

describe('sentinel: material input (__MATERIAL_INPUT__)', () => {
  it('hides when totalInput > 0 and phase IN_PRODUCTION', () => {
    expect(isTaskReportComplete(baseTask, makeYield({ totalInput: 10, phase: 'IN_PRODUCTION' }))).toBe(true);
  });

  it('hides when phase COMPLETED even if totalInput is null', () => {
    expect(isTaskReportComplete(baseTask, makeYield({ totalInput: null, phase: 'COMPLETED' }))).toBe(true);
  });

  it('hides when status is COMPLETED regardless of yield', () => {
    expect(isTaskReportComplete({ ...baseTask, status: 'COMPLETED' }, null)).toBe(true);
  });

  it('keeps visible when totalInput is 0 and phase AWAITING_INPUT', () => {
    expect(isTaskReportComplete(baseTask, makeYield({ totalInput: 0, phase: 'AWAITING_INPUT' }))).toBe(false);
  });

  it('keeps visible when yieldData is null (conservative)', () => {
    expect(isTaskReportComplete(baseTask, null)).toBe(false);
  });

  it('keeps visible when step not found in yield (conservative)', () => {
    // Step belongs to a different task id and processOrder — no match for baseTask (id=101, order=0)
    const yieldNoMatch = makeYield({});
    yieldNoMatch.steps = [
      { ...yieldNoMatch.steps[0]!, workProcessTaskId: 999, processOrder: 999 },
    ];
    expect(isTaskReportComplete(baseTask, yieldNoMatch)).toBe(false);
  });
});

// ─── final-output sentinel ──────────────────────────────────────────────────

describe('sentinel: final output (__FINAL_OUTPUT__)', () => {
  const finalTask: WorkProcessTask = {
    ...baseTask,
    id: 102,
    workProcessId: '__FINAL_OUTPUT__',
    processOrder: 999,
  };

  it('hides when totalOutput > 0 and phase COMPLETED', () => {
    const y = makeYield({ workProcessTaskId: 102, processOrder: 999, totalOutput: 8, phase: 'COMPLETED' });
    expect(isTaskReportComplete(finalTask, y)).toBe(true);
  });

  it('hides when totalOutput > 0 even without phase', () => {
    const y = makeYield({ workProcessTaskId: 102, processOrder: 999, totalOutput: 5, phase: null });
    expect(isTaskReportComplete(finalTask, y)).toBe(true);
  });

  it('keeps visible when totalOutput is 0 and phase is null', () => {
    const y = makeYield({ workProcessTaskId: 102, processOrder: 999, totalOutput: 0, phase: null });
    expect(isTaskReportComplete(finalTask, y)).toBe(false);
  });
});

// ─── normal (non-sentinel) processes ────────────────────────────────────────

describe('normal process (non-sentinel)', () => {
  const normalTask: WorkProcessTask = {
    ...baseTask,
    workProcessId: 'CUTTING',
    processOrder: 1,
    status: 'IN_PROGRESS',
  };

  it('keeps visible while IN_PROGRESS regardless of yield data', () => {
    expect(isTaskReportComplete(normalTask, makeYield({ totalInput: 10 }))).toBe(false);
    expect(isTaskReportComplete(normalTask, null)).toBe(false);
  });

  it('hides when status becomes COMPLETED', () => {
    expect(isTaskReportComplete({ ...normalTask, status: 'COMPLETED' }, null)).toBe(true);
  });

  it('hides when status is SKIPPED', () => {
    expect(isTaskReportComplete({ ...normalTask, status: 'SKIPPED' }, null)).toBe(true);
  });

  it('hides when status is CANCELLED', () => {
    expect(isTaskReportComplete({ ...normalTask, status: 'CANCELLED' }, null)).toBe(true);
  });
});
