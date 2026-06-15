import type { ProductWorkProcess } from '../types/workProcess';

export function buildPlannedQuantitiesForProcesses(
  processes: ProductWorkProcess[],
  plannedQuantity: number,
): Record<string, number> {
  if (!Number.isFinite(plannedQuantity) || plannedQuantity <= 0) {
    return {};
  }

  return processes.reduce<Record<string, number>>((acc, process) => {
    if (process.isActive && process.reportingRequired !== false && process.workProcessId) {
      acc[process.workProcessId] = plannedQuantity;
    }
    return acc;
  }, {});
}
