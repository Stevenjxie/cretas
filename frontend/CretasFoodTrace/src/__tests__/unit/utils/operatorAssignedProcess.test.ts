import { isTaskReportComplete } from '../../../utils/operatorAssignedProcess';
import { BatchYieldDTO, WorkProcessTask } from '../../../services/api/yieldReportApi';

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

function yieldWithStep(step: Partial<BatchYieldDTO['steps'][number]>): BatchYieldDTO {
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
    steps: [{
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
    }],
  };
}

describe('operator assigned process completion', () => {
  it('hides a material-input sentinel task after input was already reported', () => {
    const yieldData = yieldWithStep({ totalInput: 10, phase: 'IN_PRODUCTION' });

    expect(isTaskReportComplete(baseTask, yieldData)).toBe(true);
  });

  it('hides a final-output sentinel task after output was already reported', () => {
    const finalTask: WorkProcessTask = {
      ...baseTask,
      id: 102,
      workProcessId: '__FINAL_OUTPUT__',
      processOrder: 999,
    };
    const yieldData = yieldWithStep({
      workProcessTaskId: 102,
      processOrder: 999,
      totalOutput: 8,
      phase: 'COMPLETED',
    });

    expect(isTaskReportComplete(finalTask, yieldData)).toBe(true);
  });

  it('keeps a normal in-progress task reportable until backend marks it terminal', () => {
    const normalTask: WorkProcessTask = {
      ...baseTask,
      workProcessId: 'CUTTING',
      processOrder: 1,
      status: 'IN_PROGRESS',
    };

    expect(isTaskReportComplete(normalTask, yieldWithStep({ totalInput: 10 }))).toBe(false);
    expect(isTaskReportComplete({ ...normalTask, status: 'COMPLETED' }, null)).toBe(true);
  });
});
