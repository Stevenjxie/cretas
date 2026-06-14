import { BatchYieldDTO, WorkProcessTask, WorkProcessTaskStatus } from '../services/api/yieldReportApi';

export const SENTINEL_MATERIAL_INPUT = '__MATERIAL_INPUT__';
export const SENTINEL_FINAL_OUTPUT = '__FINAL_OUTPUT__';

export function isTerminalWorkProcessStatus(status: WorkProcessTaskStatus): boolean {
  return status === 'COMPLETED' || status === 'SKIPPED' || status === 'CANCELLED';
}

export function isTaskReportComplete(task: WorkProcessTask, yieldData?: BatchYieldDTO | null): boolean {
  if (isTerminalWorkProcessStatus(task.status)) return true;

  const step = yieldData?.steps?.find(
    (item) => item.workProcessTaskId === task.id || item.processOrder === task.processOrder,
  );

  if (task.workProcessId === SENTINEL_MATERIAL_INPUT) {
    return (step?.totalInput ?? 0) > 0
      || step?.phase === 'IN_PRODUCTION'
      || step?.phase === 'COMPLETED';
  }

  if (task.workProcessId === SENTINEL_FINAL_OUTPUT) {
    return (step?.totalOutput ?? 0) > 0 || step?.phase === 'COMPLETED';
  }

  return false;
}
