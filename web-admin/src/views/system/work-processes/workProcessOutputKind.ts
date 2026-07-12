import type { WorkProcessOutputMaterialKind } from '@/api/processProduction';

export const WORK_PROCESS_OUTPUT_KIND_OPTIONS = [
  { label: '半成品工序', value: 'SEMI_FINISHED' },
  { label: '成品出品工序', value: 'FINISHED_GOOD' },
] as const;

export function normalizeOutputMaterialKind(
  value?: string | null,
): WorkProcessOutputMaterialKind {
  return value === 'FINISHED_GOOD' ? 'FINISHED_GOOD' : 'SEMI_FINISHED';
}

export function usesSemiFinishedCode(kind: WorkProcessOutputMaterialKind): boolean {
  return kind === 'SEMI_FINISHED';
}
